package com.example.sporty.match.deposit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="match.deposit.enabled",havingValue="true",matchIfMissing=true)
public class DepositMaintenance {
    private final DepositSaga saga; private final DepositStore store;
    private final DepositPromotion promotion; private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public DepositMaintenance(DepositSaga saga,DepositStore store,DepositPromotion promotion,
            JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.saga=saga; this.store=store; this.promotion=promotion; this.jdbc=jdbc; tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${match.deposit.maintenance-delay-ms:10000}",initialDelayString="${match.deposit.maintenance-delay-ms:10000}")
    public void maintain() {
        var pending=tx.execute(s->store.pending());
        var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        for(var r:pending) {
            if(now.isBefore(r.deadlineAt)) continue;
            try { saga.expire(r.requestId); }
            catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Deposit timeout retry: {}",r.requestId,e); }
        }
        // Explicit retry path after member-service failures, busy members, or rejected promotions.
        for(var id:jdbc.queryForList("SELECT m.id FROM matches m WHERE m.status IN ('CLOSED','RECRUITING')"
                + " AND m.start_at>? AND (m.status='CLOSED' OR EXISTS(SELECT 1 FROM match_waitlist w WHERE w.match_id=m.id))"
                + " AND (SELECT COUNT(*) FROM match_participants p WHERE p.match_id=m.id AND p.participation_status='CONFIRMED')<m.max_participant"
                + " ORDER BY m.id",Long.class,java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")))) {
            try { promotion.promote(id); }
            catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Waitlist promotion retry: {}",id,e); }
        }
    }
}
