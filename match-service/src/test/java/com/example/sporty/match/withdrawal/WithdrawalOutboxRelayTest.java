package com.example.sporty.match.withdrawal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.*;

import java.util.*;
import java.util.concurrent.*;

class WithdrawalOutboxRelayTest {
    static class Manager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;

        protected Object doGetTransaction() {
            return new Object();
        }

        protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) {}

        protected void doCommit(DefaultTransactionStatus s) {
            commits++;
        }

        protected void doRollback(DefaultTransactionStatus s) {
            rollbacks++;
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void acknowledgmentPrecedesPublishedMarker() {
        var jdbc = mock(JdbcTemplate.class);
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("event-1"));
        when(jdbc.queryForList(anyString(), eq("event-1")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "aggregate_id",
                                        "2",
                                        "event_type",
                                        "match.withdrawal-cleaned.v1",
                                        "payload",
                                        "{\"eventId\":\"event-1\"}")));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        new WithdrawalOutboxRelay(jdbc, kafka, new Manager()).publish();
        var order = inOrder(kafka, jdbc);
        order.verify(kafka).send(eq("match.withdrawal-cleaned.v1"), eq("2"), anyString());
        order.verify(jdbc).update(contains("published_at=CURRENT_TIMESTAMP"), eq("event-1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedSendLeavesOutboxUnpublished() {
        var jdbc = mock(JdbcTemplate.class);
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("event-1"));
        when(jdbc.queryForList(anyString(), eq("event-1")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "aggregate_id",
                                        "2",
                                        "event_type",
                                        "match.withdrawal-cleaned.v1",
                                        "payload",
                                        "{}")));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        new WithdrawalOutboxRelay(jdbc, kafka, new Manager()).publish();
        verify(jdbc, never()).update(anyString(), eq("event-1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void alreadyPublishedRowIsNotSentAgain() {
        var jdbc = mock(JdbcTemplate.class);
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("event-1"));
        when(jdbc.queryForList(anyString(), eq("event-1"))).thenReturn(List.of());
        new WithdrawalOutboxRelay(jdbc, kafka, new Manager()).publish();
        verifyNoInteractions(kafka);
    }

    private static Map<String, Object> row(String id, String user) {
        return Map.of(
                "aggregate_id",
                user,
                "event_type",
                "match.withdrawal-cleaned.v1",
                "payload",
                "{\"eventId\":\"" + id + "\"}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedHeadDoesNotBlockNextMemberAndRetriesTheOriginalPayload() {
        var jdbc = mock(JdbcTemplate.class);
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var manager = new Manager();
        var first = row("event-1", "2");
        var second = row("event-2", "3");
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("event-1", "event-2"), List.of("event-1"));
        when(jdbc.queryForList(anyString(), eq("event-1"))).thenReturn(List.of(first));
        when(jdbc.queryForList(anyString(), eq("event-2"))).thenReturn(List.of(second));
        when(kafka.send(anyString(), eq("2"), eq((String) first.get("payload"))))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("row rejected")),
                        CompletableFuture.completedFuture(null));
        when(kafka.send(anyString(), eq("3"), eq((String) second.get("payload"))))
                .thenReturn(CompletableFuture.completedFuture(null));
        var relay = new WithdrawalOutboxRelay(jdbc, kafka, manager);

        relay.publish();

        verify(jdbc, never()).update(contains("published_at=CURRENT_TIMESTAMP"), eq("event-1"));
        verify(jdbc).update(contains("published_at=CURRENT_TIMESTAMP"), eq("event-2"));
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(1);

        relay.publish();

        verify(kafka, times(2))
                .send(
                        eq("match.withdrawal-cleaned.v1"),
                        eq("2"),
                        eq((String) first.get("payload")));
        verify(kafka, times(1))
                .send(
                        eq("match.withdrawal-cleaned.v1"),
                        eq("3"),
                        eq((String) second.get("payload")));
        verify(jdbc).update(contains("published_at=CURRENT_TIMESTAMP"), eq("event-1"));
        assertThat(manager.commits).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedDatabaseMarkerRollsBackOnlyItsRowAndNextMemberStillPublishes() {
        var jdbc = mock(JdbcTemplate.class);
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var manager = new Manager();
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("event-1", "event-2"));
        when(jdbc.queryForList(anyString(), eq("event-1")))
                .thenReturn(List.of(row("event-1", "2")));
        when(jdbc.queryForList(anyString(), eq("event-2")))
                .thenReturn(List.of(row("event-2", "3")));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(jdbc.update(contains("published_at=CURRENT_TIMESTAMP"), eq("event-1")))
                .thenThrow(
                        new org.springframework.dao.TransientDataAccessResourceException(
                                "marker failed"));

        new WithdrawalOutboxRelay(jdbc, kafka, manager).publish();

        verify(jdbc).update(contains("published_at=CURRENT_TIMESTAMP"), eq("event-2"));
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void interruptedWorkerKeepsItsInterruptFlagAndStopsTheBatch() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var future = (CompletableFuture<SendResult<String, String>>) mock(CompletableFuture.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("event-1", "event-2"));
        when(jdbc.queryForList(anyString(), eq("event-1")))
                .thenReturn(List.of(row("event-1", "2")));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("worker stopped"));
        try {
            new WithdrawalOutboxRelay(jdbc, kafka, new Manager()).publish();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(jdbc, never()).queryForList(anyString(), eq("event-2"));
            verify(jdbc, never()).update(anyString(), eq("event-1"));
        } finally {
            Thread.interrupted();
        }
    }
}
