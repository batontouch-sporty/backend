package com.example.sporty.match.waitlist;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.MatchRemoteChecks;
import com.example.sporty.match.participation.ParticipationProblem;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

@Service
@Transactional
public class WaitlistService {
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final MatchWaitlistRepository waitlist;
    private final MatchRemoteChecks checks;
    private final MemberLocks locks;

    public WaitlistService(
            MatchRepository matches,
            MatchParticipantRepository participants,
            MatchWaitlistRepository waitlist,
            MatchRemoteChecks checks,
            @Qualifier("participationMemberLocks") MemberLocks locks) {
        this.matches = matches;
        this.participants = participants;
        this.waitlist = waitlist;
        this.checks = checks;
        this.locks = locks;
    }

    public record Registration(
            Long matchId, Long userId, long position, LocalDateTime registeredAt) {}

    public Registration register(Long matchId, Long userId) {
        valid(matchId);
        valid(userId);
        locks.lock(userId);
        var member = checks.activeMember(userId);
        var match =
                matches.findByIdForUpdate(matchId)
                        .orElseThrow(() -> problem(404, "MATCH_NOT_FOUND", "매치를 찾을 수 없습니다."));
        var existing = participants.findByMatch_IdAndUserId(matchId, userId);
        if (existing.isPresent()) {
            if (existing.get().getRole() == MatchParticipantRole.OWNER)
                throw problem(400, "MATCH_OWNER_CANNOT_WAIT", "방장은 대기 등록할 수 없습니다.");
            throw problem(409, "MATCH_ALREADY_JOINED", "이미 참가 중인 매치입니다.");
        }
        if (waitlist.existsByMatch_IdAndUserId(matchId, userId))
            throw problem(409, "WAITLIST_ALREADY_JOINED", "이미 대기 중인 매치입니다.");
        if (match.getStatus() != MatchStatus.CLOSED
                || !match.getStartAt().isAfter(now())
                || participants.countByMatch_Id(matchId) < match.getMaxParticipant())
            throw problem(400, "MATCH_NOT_FULL", "시작 전 정원이 찬 매치만 대기 등록할 수 있습니다.");
        checks.checkParticipantGender(member, match.getGenderGroup().name());
        var row = waitlist.saveAndFlush(new MatchWaitlistEntity(match, userId));
        return new Registration(
                matchId,
                userId,
                waitlist.position(matchId, row.getRegisteredAt(), row.getId()),
                LocalDateTime.ofInstant(row.getRegisteredAt(), ZoneId.of("Asia/Seoul")));
    }

    public void cancel(Long matchId, Long userId) {
        valid(matchId);
        valid(userId);
        locks.lock(userId);
        var match =
                matches.findByIdForUpdate(matchId)
                        .orElseThrow(() -> problem(404, "MATCH_NOT_FOUND", "매치를 찾을 수 없습니다."));
        if (match.getStatus() == MatchStatus.CANCELLED
                || match.getStatus() == MatchStatus.COMPLETED)
            throw problem(400, "WAITLIST_CANCEL_NOT_ALLOWED", "취소되거나 완료된 매치는 대기 이력을 보존합니다.");
        var row =
                waitlist.findByMatch_IdAndUserId(matchId, userId)
                        .orElseThrow(() -> problem(404, "WAITLIST_NOT_FOUND", "대기 기록을 찾을 수 없습니다."));
        waitlist.delete(row);
        waitlist.flush();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    private static void valid(Long id) {
        if (id == null || id <= 0) throw problem(400, "INVALID_INPUT", "ID를 확인해주세요.");
    }

    private static ParticipationProblem problem(int status, String code, String message) {
        return new ParticipationProblem(status, code, message);
    }
}
