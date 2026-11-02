package com.example.sporty.match.deposit;
import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.event.*;
import java.time.*;
import java.util.*;

@Service
public class DepositPromotion {
    public record Vacancy(long matchId) {}
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final MatchWaitlistRepository waitlist;
    private final MatchRemoteChecks checks;
    private final MemberLocks locks;
    private final DepositSaga saga;
    private final DepositStore store;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public DepositPromotion(MatchRepository matches, MatchParticipantRepository participants,
            MatchWaitlistRepository waitlist, MatchRemoteChecks checks,
            @Qualifier("participationMemberLocks") MemberLocks locks, DepositSaga saga, DepositStore store, org.springframework.transaction.PlatformTransactionManager manager) {
        this.matches=matches; this.participants=participants; this.waitlist=waitlist;
        this.checks=checks; this.locks=locks; this.saga=saga;
        this.store=store;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    // Failed promotion can never roll back an already committed cancellation/withdrawal.
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void afterCancellation(Vacancy vacancy) {
        try { transactions.executeWithoutResult(s->promote(vacancy.matchId())); }
        catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("승급 재시도 예약: {}",vacancy.matchId(),e); }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void promote(long matchId) {
        var match=matches.findByIdForUpdate(matchId).orElse(null);
        if(match==null || !Set.of(MatchStatus.CLOSED,MatchStatus.RECRUITING).contains(match.getStatus())
                || !match.getStartAt().isAfter(LocalDateTime.now(ZoneId.of("Asia/Seoul")))) return;
        long pendingPromotions=participants.findAllByMatch_Id(matchId).stream()
                .filter(p->p.getParticipationStatus()==ParticipationStatus.PENDING)
                .filter(p->{ var r=store.get(p.getRequestId()); return r!=null && "PROMOTE".equals(r.requestType); }).count();
        long available=match.getMaxParticipant()-participants.countByMatch_Id(matchId)-pendingPromotions;
        while(available>0) {
            var next=waitlist.findFirstByMatch_IdOrderByRegisteredAtAscIdAsc(matchId);
            if(next.isEmpty()) return;
            var row=next.get();
            // Nonblocking lock avoids match -> member deadlocks. Scheduler retries a busy head.
            locks.lockImmediately(row.getUserId());
            if(participants.existsByMatch_IdAndUserId(matchId,row.getUserId())) {
                waitlist.delete(row); waitlist.flush(); continue;
            }
            try {
                var member=checks.activeMember(row.getUserId());
                checks.checkParticipantGender(member,match.getGenderGroup().name());
            } catch(MatchDependencyException e) {
                if(!Set.of("USER_NOT_ACTIVE","MATCH_USER_NOT_FOUND","MATCH_GENDER_NOT_ALLOWED","PARTICIPATION_SUSPENDED").contains(e.code())) return;
                waitlist.delete(row); waitlist.flush(); continue;
            }
            var p=participants.findByMatch_IdAndUserId(matchId,row.getUserId())
                    .orElseGet(()->new MatchParticipantEntity(match,row.getUserId(),MatchParticipantRole.PARTICIPANT));
            saga.begin(p,"PROMOTE"); waitlist.delete(row); waitlist.flush(); available--;
        }
    }
}
