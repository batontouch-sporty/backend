package com.example.sporty.user.points;

import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.withdrawal.domain.entity.ProcessedEventEntity;
import com.example.sporty.user.withdrawal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.sporty.user.points.PointHistory.Reason.*;

@Service
@Transactional
@RequiredArgsConstructor
public class PointSettlementService {
    public enum Action { CHARGE, REFUND, ATTENDED, NO_SHOW, UNDER_MIN_PARTICIPANTS }
    public enum Outcome {
        CHARGED, INSUFFICIENT_POINTS, PARTICIPATION_SUSPENDED, USER_NOT_ACTIVE, REQUEST_CANCELLED,
        RETURNED, CANCELLED_BEFORE_CHARGE, FORFEITED, ALREADY_SETTLED, DUPLICATE, WITHDRAWN,
        // 확정 차감 이력이 없는 출석 정산(보증금 기능이 꺼져 있을 때 확정된 참가). 처리 완료로 기록만 한다.
        NO_CHARGE_RECORD
    }

    public record Result(Outcome outcome, int creditedPoints, LocalDateTime newSuspension) {
        static Result of(Outcome outcome) { return new Result(outcome, 0, null); }
    }

    public record Command(String eventId, UUID requestId, long userId, long matchId, Action action) {
        public Command {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(action);
            if (eventId == null || eventId.isBlank() || eventId.length() > 64 || userId < 1 || matchId < 1) {
                throw new IllegalArgumentException("잘못된 정산 요청");
            }
        }
    }

    private static final int DEPOSIT = 10;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final UserRepository users;
    private final PointHistoryRepository history;
    private final ProcessedEventRepository processed;
    private final Clock clock;

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), SEOUL); }

    public void recordSignup(UserEntity user) {
        history.save(new PointHistory(user.getId(), null, null, SIGNUP, 0, user.getPoints(), null, now()));
    }

    // 다음 브랜치의 소비자가 이 메서드와 결과 Outbox 저장을 같은 트랜잭션으로 묶는다.
    public Result apply(Command cmd) {
        UserEntity user = users.findForUpdate(cmd.userId()).orElseThrow(UserNotFoundException::new);
        if (processed.existsById(cmd.eventId())) return Result.of(Outcome.DUPLICATE);
        processed.save(new ProcessedEventEntity(cmd.eventId(), cmd.action().name()));
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            return Result.of(cmd.action() == Action.CHARGE ? Outcome.USER_NOT_ACTIVE : Outcome.WITHDRAWN);
        }
        LocalDateTime now = now();
        expire(user, now);
        var entries = new EnumMap<PointHistory.Reason, PointHistory>(PointHistory.Reason.class);
        for (PointHistory entry : history.findByRequestId(cmd.requestId().toString())) {
            if (entry.getUserId() != cmd.userId() || entry.getMatchId() != cmd.matchId()) {
                throw new IllegalArgumentException("요청 ID의 회원 또는 매치가 다릅니다.");
            }
            entries.put(entry.getReason(), entry);
        }
        Set<PointHistory.Reason> reasons = entries.keySet();
        if (cmd.action() == Action.CHARGE) return charge(cmd, user, reasons, now);
        // 매칭이 같은 반환을 새 이벤트로 요청하면 최초 실제 지급액을 다시 알려준다.
        if (cmd.action() == Action.REFUND && entries.containsKey(DEPOSIT_RETURNED)) {
            return new Result(Outcome.RETURNED, entries.get(DEPOSIT_RETURNED).getDelta(), null);
        }
        if (reasons.contains(DEPOSIT_CANCELLED)) return Result.of(Outcome.CANCELLED_BEFORE_CHARGE);
        if (reasons.contains(DEPOSIT_RETURNED) || reasons.contains(NO_SHOW_FORFEITED)) {
            return Result.of(Outcome.ALREADY_SETTLED);
        }
        if (!reasons.contains(DEPOSIT_CHARGED)) {
            if (cmd.action() != Action.REFUND) return Result.of(Outcome.NO_CHARGE_RECORD);
            change(user, cmd, DEPOSIT_CANCELLED, 0, now);
            return Result.of(Outcome.CANCELLED_BEFORE_CHARGE);
        }
        if (cmd.action() == Action.NO_SHOW) {
            change(user, cmd, NO_SHOW_FORFEITED, 0, now);
            LocalDateTime until = null;
            if (user.getPoints() == 0 && user.getSuspendedUntil() == null) {
                until = now.toLocalDate().plusDays(7).atStartOfDay();
                user.suspendUntil(until);
            }
            return new Result(Outcome.FORFEITED, 0, until);
        }
        int credited = change(user, cmd, DEPOSIT_RETURNED, DEPOSIT, now);
        if (cmd.action() == Action.ATTENDED && user.recordAttendance()) {
            change(user, cmd, ATTENDANCE_REWARDED, DEPOSIT, now);
        }
        return new Result(Outcome.RETURNED, credited, null);
    }

    private Result charge(Command cmd, UserEntity user, Set<PointHistory.Reason> reasons, LocalDateTime now) {
        if (reasons.contains(DEPOSIT_CANCELLED)) return Result.of(Outcome.REQUEST_CANCELLED);
        if (reasons.contains(DEPOSIT_CHARGED)) return Result.of(Outcome.CHARGED);
        // 거절은 잔액 이력이 없다. 재발급된 eventId로 같은 요청을 보내도 이전 거절 결정을 유지한다.
        String decisionId = "point-rejection:" + cmd.requestId();
        var previous = processed.findById(decisionId);
        if (previous.isPresent()) return Result.of(Outcome.valueOf(previous.get().getEventType()));
        Outcome rejection = null;
        if (user.getStatus() != UserStatus.ACTIVE) rejection = Outcome.USER_NOT_ACTIVE;
        else if (user.getSuspendedUntil() != null) rejection = Outcome.PARTICIPATION_SUSPENDED;
        else if (user.getPoints() < DEPOSIT) rejection = Outcome.INSUFFICIENT_POINTS;
        if (rejection != null) {
            processed.save(new ProcessedEventEntity(decisionId, rejection.name()));
            return Result.of(rejection);
        }
        change(user, cmd, DEPOSIT_CHARGED, -DEPOSIT, now);
        return Result.of(Outcome.CHARGED);
    }

    private int change(UserEntity user, Command cmd, PointHistory.Reason reason, int amount, LocalDateTime now) {
        int before = user.getPoints();
        int after = Math.min(100, before + amount);
        user.changePointsTo(after);
        history.save(new PointHistory(user.getId(), cmd.matchId(), cmd.requestId().toString(),
                reason, before, after, cmd.eventId(), now));
        return after - before;
    }

    // 조회 경로에서도 부르므로 해제할 때가 된 회원만 잠근다.
    public void expireIfDue(long userId) {
        LocalDateTime now = now();
        if (!users.existsByIdAndSuspendedUntilLessThanEqual(userId, now)) return;
        users.findForUpdate(userId).ifPresent(user -> expire(user, now));
    }

    private void expire(UserEntity user, LocalDateTime now) {
        if (user.getStatus() == UserStatus.WITHDRAWN || user.getSuspendedUntil() == null
                || user.getSuspendedUntil().isAfter(now)) return;
        int before = user.getPoints();
        user.clearSuspension();
        user.changePointsTo(30);
        history.save(new PointHistory(user.getId(), null, null, SUSPENSION_EXPIRED, before, 30, null, now));
    }
}
