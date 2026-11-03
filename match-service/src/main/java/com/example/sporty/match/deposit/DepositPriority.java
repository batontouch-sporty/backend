package com.example.sporty.match.deposit;

import com.example.sporty.match.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Recruitment gate for FIFO, independent of the CONFIRMED-only participant count.
 * Call with the match row locked; current reads avoid an older result consumer's snapshot.
 */
@Component
@Transactional(propagation=Propagation.MANDATORY)
public class DepositPriority {
    private final MatchParticipantRepository participants;
    private final MatchWaitlistRepository waitlist;
    private final DepositStore store;
    public DepositPriority(MatchParticipantRepository participants,MatchWaitlistRepository waitlist,DepositStore store) {
        this.participants=participants; this.waitlist=waitlist; this.store=store;
    }
    public boolean hasPriority(long matchId) {
        if(!waitlist.findPriorityRowsForUpdate(matchId).isEmpty()) return true;
        for(var p:participants.findPendingForUpdate(matchId)) {
            var r=store.locked(p.getRequestId());
            if(r!=null && "PENDING".equals(r.status) && "PROMOTE".equals(r.requestType)) return true;
        }
        return false;
    }
}
