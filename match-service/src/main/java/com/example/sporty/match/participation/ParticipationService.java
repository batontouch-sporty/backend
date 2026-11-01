package com.example.sporty.match.participation;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.MatchRemoteChecks;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

@Service
public class ParticipationService {
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final MatchRemoteChecks checks;
    private final MemberLocks locks;

    public ParticipationService(
            MatchRepository matches,
            MatchParticipantRepository participants,
            MatchRemoteChecks checks,
            @Qualifier("participationMemberLocks") MemberLocks locks) {
        this.matches = matches;
        this.participants = participants;
        this.checks = checks;
        this.locks = locks;
    }

    public record Participation(
            Long matchParticipantId, Long matchId, Long userId, MatchParticipantRole role) {}

    /** Same member -> match lock order as withdrawal; capacity check and insertion are atomic. */
    @Transactional
    public Participation join(Long matchId, Long userId) {
        requireId(matchId);
        requireId(userId);
        locks.lock(userId);
        var member = checks.activeMember(userId);
        var match =
                matches.findByIdForUpdate(matchId)
                        .orElseThrow(() -> problem(404, "MATCH_NOT_FOUND", "매치를 찾을 수 없습니다."));
        if (participants.existsByMatch_IdAndUserId(matchId, userId))
            throw problem(409, "MATCH_ALREADY_JOINED", "이미 참가 중인 매치입니다.");
        if (match.getStatus() != MatchStatus.RECRUITING)
            throw problem(400, "MATCH_RECRUITMENT_CLOSED", "모집이 마감된 매치입니다.");
        if (!match.getStartAt().isAfter(now()))
            throw problem(400, "MATCH_ALREADY_STARTED", "이미 시작된 매치입니다.");
        if (participants.countByMatch_Id(matchId) >= match.getMaxParticipant())
            throw problem(400, "MATCH_FULL", "매치 정원이 가득 찼습니다.");
        checks.checkParticipantGender(member, match.getGenderGroup().name());
        var participant =
                participants.saveAndFlush(
                        new MatchParticipantEntity(
                                match, userId, MatchParticipantRole.PARTICIPANT));
        if (participants.countByMatch_Id(matchId) >= match.getMaxParticipant())
            match.closeRecruitment(now());
        return new Participation(participant.getId(), matchId, userId, participant.getRole());
    }

    /** General participants may leave after start; only pre-start CLOSED matches reopen. */
    @Transactional
    public void leave(Long matchId, Long userId) {
        requireId(matchId);
        requireId(userId);
        locks.lock(userId);
        var match =
                matches.findByIdForUpdate(matchId)
                        .orElseThrow(() -> problem(404, "MATCH_NOT_FOUND", "매치를 찾을 수 없습니다."));
        if (match.getStatus() == MatchStatus.CANCELLED
                || match.getStatus() == MatchStatus.COMPLETED)
            throw problem(400, "MATCH_LEAVE_NOT_ALLOWED", "취소되거나 완료된 매치는 참가 취소할 수 없습니다.");
        var participant =
                participants
                        .findByMatch_IdAndUserId(matchId, userId)
                        .orElseThrow(
                                () ->
                                        problem(
                                                404,
                                                "MATCH_PARTICIPANT_NOT_FOUND",
                                                "매치 참가 정보를 찾을 수 없습니다."));
        if (participant.getRole() == MatchParticipantRole.OWNER)
            throw problem(403, "MATCH_OWNER_CANNOT_LEAVE", "방장은 참가 취소할 수 없습니다.");
        participants.delete(participant);
        participants.flush();
        var now = now();
        if (match.getStatus() == MatchStatus.CLOSED
                && match.getStartAt().isAfter(now)
                && participants.countByMatch_Id(matchId) < match.getMaxParticipant())
            match.reopenRecruitment(now);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    private static void requireId(Long id) {
        if (id == null || id <= 0) throw problem(400, "INVALID_INPUT", "ID를 확인해주세요.");
    }

    private static ParticipationProblem problem(int status, String code, String message) {
        return new ParticipationProblem(status, code, message);
    }
}
