package com.example.sporty.match.withdrawal;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "match.withdrawal.enabled", havingValue = "true")
public class WithdrawalOutboxRelay {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    public WithdrawalOutboxRelay(
            JdbcTemplate jdbc,
            @Qualifier("withdrawalKafkaTemplate") KafkaTemplate<String, String> kafka,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        tx = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${match.withdrawal.outbox-delay-ms:1000}")
    /** Lock pending Saga rows and mark published only after Kafka ACK; failures stay retryable. */
    public void publish() {
        try {
            var ids =
                    jdbc.queryForList(
                            "SELECT event_id FROM outbox_event WHERE published_at IS NULL AND"
                                + " (event_type IN"
                                + " ('match.withdrawal-cleaned.v1','match.withdrawal-rejected.v1')"
                                + " OR (event_type='match.status-changed.v1' AND"
                                + " JSON_VALUE(payload,'$.sagaId') IS NOT NULL)) ORDER BY"
                                + " created_at,event_id LIMIT 50",
                            String.class);
            for (String id : ids)
                tx.executeWithoutResult(
                        s -> {
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT aggregate_id,event_type,payload FROM"
                                                + " outbox_event WHERE event_id=? AND published_at"
                                                + " IS NULL FOR UPDATE",
                                            id);
                            if (rows.isEmpty()) return;
                            var row = rows.get(0);
                            try {
                                kafka.send(
                                                (String) row.get("event_type"),
                                                (String) row.get("aggregate_id"),
                                                (String) row.get("payload"))
                                        .get(5, TimeUnit.SECONDS);
                            } catch (Exception ex) {
                                if (ex instanceof InterruptedException)
                                    Thread.currentThread().interrupt();
                                throw new IllegalStateException("Saga Outbox publish failed", ex);
                            }
                            jdbc.update(
                                    "UPDATE outbox_event SET published_at=CURRENT_TIMESTAMP(6)"
                                        + " WHERE event_id=?",
                                    id);
                        });
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).warn("Saga Outbox retry required", ex);
        }
    }
}
