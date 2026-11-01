package com.example.sporty.match.waitlist;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.participation.ParticipationEvents;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.Set;

@Service
public class WaitlistPromotion {
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final MatchWaitlistRepository waitlist;
    private final MatchRemoteChecks checks;
    private final MemberLocks locks;
    private final ParticipationEvents events;

    public WaitlistPromotion(
            MatchRepository matches,
            MatchParticipantRepository participants,
            MatchWaitlistRepository waitlist,
            MatchRemoteChecks checks,
            @Qualifier("participationMemberLocks") MemberLocks locks,
            ParticipationEvents events) {
        this.matches = matches;
        this.participants = participants;
        this.waitlist = waitlist;
        this.checks = checks;
        this.locks = locks;
        this.events = events;
    }

    /**
     * Caller owns the cancellation transaction. Busy candidates roll it back rather than lose FIFO.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int promote(Long matchId) {
        var match = matches.findByIdForUpdate(matchId).orElseThrow();
        if (match.getStatus() != MatchStatus.CLOSED || !match.getStartAt().isAfter(now())) return 0;
        int promoted = 0;
        while (participants.countByMatch_Id(matchId) < match.getMaxParticipant()) {
            var next = waitlist.findFirstByMatch_IdOrderByRegisteredAtAscIdAsc(matchId);
            if (next.isEmpty()) break;
            var row = next.get();
            locks.lockImmediately(row.getUserId());
            if (participants.existsByMatch_IdAndUserId(matchId, row.getUserId())) {
                waitlist.delete(row);
                waitlist.flush();
                continue;
            }
            try {
                var member = checks.activeMember(row.getUserId());
                checks.checkParticipantGender(member, match.getGenderGroup().name());
            } catch (MatchDependencyException ex) {
                if (!Set.of("USER_NOT_ACTIVE", "MATCH_USER_NOT_FOUND", "MATCH_GENDER_NOT_ALLOWED")
                        .contains(ex.code())) throw ex;
                waitlist.delete(row);
                waitlist.flush();
                continue;
            }
            if (!match.getStartAt().isAfter(now())) break;
            participants.saveAndFlush(
                    new MatchParticipantEntity(
                            match, row.getUserId(), MatchParticipantRole.PARTICIPANT));
            waitlist.delete(row);
            waitlist.flush();
            events.promoted(match, row.getUserId());
            promoted++;
        }
        return promoted;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
