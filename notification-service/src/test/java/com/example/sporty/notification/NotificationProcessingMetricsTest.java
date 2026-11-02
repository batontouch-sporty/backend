package com.example.sporty.notification;

import com.example.sporty.notification.events.KafkaTopics;
import com.example.sporty.notification.events.dto.MatchJoinedEvent;
import com.example.sporty.notification.events.dto.MatchStatusChangedEvent;
import com.example.sporty.notification.events.dto.StartingSoonEvent;
import com.example.sporty.notification.events.dto.WaitlistPromotedEvent;
import com.example.sporty.notification.events.dto.WithdrawalRejectedEvent;
import com.example.sporty.notification.events.metrics.KafkaProcessingMetrics;
import com.example.sporty.notification.events.repository.ProcessedEventRepository;
import com.example.sporty.notification.events.service.NotificationEventService;
import com.example.sporty.notification.notifications.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:notification_metrics_test;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "management.endpoints.web.exposure.include=health,prometheus"
})
@AutoConfigureObservability(tracing = false)
class NotificationProcessingMetricsTest {
    @Autowired NotificationEventService service;
    @Autowired NotificationRepository notifications;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired MeterRegistry registry;
    @Autowired TestRestTemplate http;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean KafkaProcessingMetrics metrics;

    @Test
    void countsNewJoinedEventAfterCommitAndExportsExpectedName() {
        String eventId = UUID.randomUUID().toString();
        double before = count(KafkaTopics.MATCH_JOINED);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.handleMatchJoined(joined(eventId));
            assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before);
        });

        assertThat(processedEvents.existsById(eventId)).isTrue();
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(101L)).hasSize(1);
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before + 1);
        assertThat(http.getForObject("/actuator/prometheus", String.class))
                .contains("sporty_kafka_records_processed_total{")
                .contains("group=\"notification-service\"")
                .contains("topic=\"match.joined.v1\"");
    }

    @Test
    void duplicateEventDoesNotCountAgain() {
        String eventId = UUID.randomUUID().toString();
        double before = count(KafkaTopics.MATCH_JOINED);
        service.handleMatchJoined(joined(eventId));
        service.handleMatchJoined(joined(eventId));

        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before + 1);
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(101L))
                .filteredOn(n -> eventId.equals(n.getEventId())).hasSize(1);
    }

    @Test
    void concurrentDuplicateCountsOnlyCommittedEvent() throws Exception {
        String eventId = UUID.randomUUID().toString();
        double before = count(KafkaTopics.MATCH_JOINED);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = List.of(
                    executor.submit(() -> processDuplicate(eventId, start)),
                    executor.submit(() -> processDuplicate(eventId, start)));
            start.countDown();
            assertThat(attempts.get(0).get(20, TimeUnit.SECONDS)
                    || attempts.get(1).get(20, TimeUnit.SECONDS)).isTrue();
            for (Future<Boolean> attempt : attempts) attempt.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(processedEvents.existsById(eventId)).isTrue();
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before + 1);
    }

    @Test
    void outerTransactionRollbackDoesNotCount() {
        String eventId = UUID.randomUUID().toString();
        double before = count(KafkaTopics.MATCH_JOINED);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.handleMatchJoined(joined(eventId));
            assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before);
            status.setRollbackOnly();
        });

        assertThat(processedEvents.existsById(eventId)).isFalse();
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before);
    }

    @Test
    void failedAttemptThenSuccessfulRetryCountsOnce() {
        String eventId = UUID.randomUUID().toString();
        double before = count(KafkaTopics.MATCH_JOINED);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.handleMatchJoined(joined(eventId));
            throw new IllegalStateException("retry");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(processedEvents.existsById(eventId)).isFalse();
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before);

        service.handleMatchJoined(joined(eventId));
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before + 1);
    }

    @Test
    void zeroRecipientEventStillCounts() {
        String eventId = UUID.randomUUID().toString();
        double before = count(KafkaTopics.MATCH_JOINED);
        service.handleMatchJoined(new MatchJoinedEvent(eventId, 57L, "모임", null, null, 0, 10));

        assertThat(processedEvents.existsById(eventId)).isTrue();
        assertThat(notifications.findAll()).noneMatch(n -> eventId.equals(n.getEventId()));
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before + 1);
    }

    @Test
    void separatesAllFiveTopics() {
        String statusId = UUID.randomUUID().toString();
        String rejectedId = UUID.randomUUID().toString();
        String promotedId = UUID.randomUUID().toString();
        String startingId = UUID.randomUUID().toString();
        String[] topics = {KafkaTopics.MATCH_STATUS_CHANGED, KafkaTopics.MATCH_WITHDRAWAL_REJECTED,
                KafkaTopics.MATCH_WAITLIST_PROMOTED, KafkaTopics.MATCH_STARTING_SOON};
        double[] before = new double[topics.length];
        for (int i = 0; i < topics.length; i++) before[i] = count(topics[i]);

        service.handleMatchStatusChanged(new MatchStatusChangedEvent(statusId, 58L, "모임",
                "RECRUITING", "IN_PROGRESS", null, List.of(), null));
        service.handleWithdrawalRejected(new WithdrawalRejectedEvent(rejectedId, null, null, null,
                "DEADLINE_EXCEEDED", List.of()));
        service.handleWaitlistPromoted(new WaitlistPromotedEvent(promotedId, 58L, "모임", null));
        service.handleStartingSoon(new StartingSoonEvent(startingId, 58L, "모임", null, List.of()));

        for (int i = 0; i < topics.length; i++) assertThat(count(topics[i])).isEqualTo(before[i] + 1);
        for (String id : List.of(statusId, rejectedId, promotedId, startingId))
            assertThat(processedEvents.existsById(id)).isTrue();
    }

    @Test
    void invalidEventIdDoesNotCount() {
        double before = count(KafkaTopics.MATCH_JOINED);
        assertThatThrownBy(() -> service.handleMatchJoined(joined(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before);
    }

    @Test
    void metricsFailureDoesNotFailCommittedEvent() {
        String eventId = UUID.randomUUID().toString();
        double before = count(KafkaTopics.MATCH_JOINED);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(metrics).recordProcessed(KafkaTopics.MATCH_JOINED);

        service.handleMatchJoined(joined(eventId));

        assertThat(processedEvents.existsById(eventId)).isTrue();
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(101L))
                .anyMatch(n -> eventId.equals(n.getEventId()));
        assertThat(count(KafkaTopics.MATCH_JOINED)).isEqualTo(before);
    }

    private MatchJoinedEvent joined(String eventId) {
        return new MatchJoinedEvent(eventId, 57L, "모임", 101L, null, 1, 10);
    }

    private boolean processDuplicate(String eventId, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            service.handleMatchJoined(new MatchJoinedEvent(eventId, 57L, "모임", null, null, 0, 10));
            return true;
        } catch (RuntimeException duplicateKey) {
            return false;
        }
    }

    private double count(String topic) {
        Counter counter = registry.find("sporty.kafka.records.processed")
                .tags("topic", topic, "group", "notification-service").counter();
        return counter == null ? 0 : counter.count();
    }
}
