package com.example.sporty.user.points;

import com.example.sporty.user.users.domain.entity.*;
import com.example.sporty.user.users.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static com.example.sporty.user.points.PointSettlementService.Action.*;
import static com.example.sporty.user.points.PointSettlementService.Outcome;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PointSettlementTest {
    @Autowired UserRepository users;
    @Autowired PointSettlementService points;
    @Autowired PointHistoryRepository history;

    private UserEntity member(int balance) {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@x.test")
                .password("test").gender(Gender.MALE).points(balance).build());
    }

    private PointSettlementService.Command command(long uid, UUID request, PointSettlementService.Action action) {
        return new PointSettlementService.Command(UUID.randomUUID().toString(), request, uid, 1, action);
    }

    private UserEntity read(long uid) { return users.findById(uid).orElseThrow(); }

    @Test
    void simultaneousApplicationsSpendLastTenPointsOnlyOnce() throws Exception {
        long uid = member(10).getId();
        var gate = new CyclicBarrier(2);
        Callable<Outcome> join = () -> {
            gate.await(5, TimeUnit.SECONDS);
            return points.apply(command(uid, UUID.randomUUID(), CHARGE)).outcome();
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(join);
            var b = pool.submit(join);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Outcome.CHARGED, Outcome.INSUFFICIENT_POINTS);
            assertThat(read(uid).getPoints()).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void refundBeforeChargeBlocksLaterChargeAndDoesNotCreditPoints() {
        long uid = member(50).getId();
        UUID request = UUID.randomUUID();
        var refund = command(uid, request, REFUND);
        assertThat(points.apply(refund).outcome()).isEqualTo(Outcome.CANCELLED_BEFORE_CHARGE);
        assertThat(points.apply(refund).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(points.apply(command(uid, request, CHARGE)).outcome()).isEqualTo(Outcome.REQUEST_CANCELLED);
        assertThat(read(uid).getPoints()).isEqualTo(50);
        assertThat(history.findByRequestId(request.toString())).singleElement()
                .extracting(PointHistory::getReason).isEqualTo(PointHistory.Reason.DEPOSIT_CANCELLED);
    }

    @Test
    void fifthAttendanceAtCapRecordsZeroDeltaRewardAndResetsCount() {
        long uid = member(100).getId();
        UUID last = null;
        for (int i = 0; i < 5; i++) {
            last = UUID.randomUUID();
            points.apply(command(uid, last, CHARGE));
            points.apply(command(uid, last, ATTENDED));
            points.apply(command(uid, last, ATTENDED));
        }
        assertThat(read(uid).getPoints()).isEqualTo(100);
        assertThat(read(uid).getAttendanceRewardCount()).isZero();
        assertThat(history.findByRequestId(last.toString())).filteredOn(
                h -> h.getReason() == PointHistory.Reason.ATTENDANCE_REWARDED)
                .singleElement().extracting(PointHistory::getDelta).isEqualTo(0);
    }

    @Test
    void noShowAtZeroSuspendsOnceAndExpiryRestoresThirtyOnlyOnce() {
        long uid = member(20).getId();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        points.apply(command(uid, first, CHARGE));
        points.apply(command(uid, second, CHARGE));
        assertThat(read(uid).getSuspendedUntil()).isNull();
        var suspension = points.apply(command(uid, first, NO_SHOW)).newSuspension();
        assertThat(suspension).isNotNull();
        assertThat(suspension.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        assertThat(points.apply(command(uid, second, NO_SHOW)).newSuspension()).isNull();
        assertThat(points.apply(command(uid, first, REFUND)).outcome()).isEqualTo(Outcome.ALREADY_SETTLED);
        assertThat(read(uid).getSuspendedUntil()).isEqualTo(suspension);
        UserEntity due = read(uid);
        due.suspendUntil(LocalDateTime.now().minusDays(1));
        users.saveAndFlush(due);
        points.expireIfDue(uid);
        points.expireIfDue(uid);
        assertThat(read(uid).getPoints()).isEqualTo(30);
        assertThat(read(uid).getSuspendedUntil()).isNull();
        assertThat(history.findByUserIdOrderByCreatedAtDescIdDesc(uid, org.springframework.data.domain.Pageable.unpaged()))
                .filteredOn(h -> h.getReason() == PointHistory.Reason.SUSPENSION_EXPIRED).hasSize(1);
    }

    @Test
    void underMinimumRefundsWithoutAttendanceAndWithdrawalGuardsCharging() {
        long uid = member(50).getId();
        UUID request = UUID.randomUUID();
        points.apply(command(uid, request, CHARGE));
        assertThat(points.apply(command(uid, request, UNDER_MIN_PARTICIPANTS)).creditedPoints()).isEqualTo(10);
        assertThat(read(uid).getAttendanceRewardCount()).isZero();
        UserEntity member = read(uid);
        member.startWithdrawal();
        users.saveAndFlush(member);
        assertThat(points.apply(command(uid, UUID.randomUUID(), CHARGE)).outcome()).isEqualTo(Outcome.USER_NOT_ACTIVE);
        member.completeWithdrawal();
        users.saveAndFlush(member);
        var late = command(uid, UUID.randomUUID(), REFUND);
        assertThat(points.apply(late).outcome()).isEqualTo(Outcome.WITHDRAWN);
        assertThat(points.apply(late).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(read(uid).getPoints()).isEqualTo(50);
    }
}
