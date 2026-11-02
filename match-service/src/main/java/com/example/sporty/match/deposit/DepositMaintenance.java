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
        for(var r:pending) try { saga.expire(r.requestId); }
            catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Deposit timeout retry: {}",r.requestId,e); }
        // Explicit retry path after member-service failures, busy members, or rejected promotions.
        for(var id:jdbc.queryForList("SELECT DISTINCT match_id FROM match_waitlist ORDER BY match_id",Long.class)) {
            try { promotion.promote(id); }
            catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Waitlist promotion retry: {}",id,e); }
        }
    }
}
