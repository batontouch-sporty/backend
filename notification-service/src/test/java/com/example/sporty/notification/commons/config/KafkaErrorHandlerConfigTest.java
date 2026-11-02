package com.example.sporty.notification.commons.config;

import com.example.sporty.notification.events.KafkaTopics;
import com.example.sporty.notification.events.metrics.KafkaDltMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:notification_dlt_metrics_test;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "management.endpoints.web.exposure.include=health,prometheus"
})
@AutoConfigureObservability(tracing = false)
class KafkaErrorHandlerConfigTest {
    @Autowired KafkaDltMetrics metrics;
    @Autowired MeterRegistry registry;
    @Autowired TestRestTemplate http;

    @Test
    void countsOnlyAfterBrokerAckAndKeepsOriginalRouteAndPayload() {
        var template = template(CompletableFuture.completedFuture(sendResult()));
        var record = record(KafkaTopics.MATCH_JOINED);
        double before = count(record.topic());

        recoverer(template, metrics).accept(record, null, new IllegalArgumentException("invalid"));

        assertThat(count(record.topic())).isEqualTo(before + 1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<Object, Object>> sent =
                org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(1)).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo(record.topic() + KafkaTopics.DLT_SUFFIX);
        assertThat(sent.getValue().partition()).isNull();
        assertThat(sent.getValue().value()).isEqualTo(record.value());
        assertThat(sent.getValue().headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNotNull();
        assertThat(http.getForObject("/actuator/prometheus", String.class))
                .contains("sporty_kafka_dlt_published_total{")
                .contains("topic=\"match.joined.v1\"");
    }

    @Test
    void failedAckDoesNotCountAndIsNotHandledByErrorHandler() {
        var record = record(KafkaTopics.MATCH_STATUS_CHANGED);
        double before = count(record.topic());
        var template = template(CompletableFuture.failedFuture(new IllegalStateException("broker failure")));
        var handler = new KafkaErrorHandlerConfig().kafkaErrorHandler(template, metrics);

        assertThat(handler.handleOne(new IllegalArgumentException("invalid"), record, null, null)).isFalse();
        assertThat(count(record.topic())).isEqualTo(before);
        verify(template, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void pendingSendCountsAfterCompletionOnly() throws Exception {
        var record = record(KafkaTopics.MATCH_WAITLIST_PROMOTED);
        double before = count(record.topic());
        var pending = new CompletableFuture<SendResult<Object, Object>>();
        var template = template(pending);
        ConsumerAwareRecordRecoverer recoverer = recoverer(template, metrics);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var call = executor.submit(() -> recoverer.accept(record, null, new IllegalArgumentException("invalid")));
            await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(template, times(1)).send(any(ProducerRecord.class)));
            assertThat(count(record.topic())).isEqualTo(before);
            pending.complete(sendResult());
            call.get(5, TimeUnit.SECONDS);
            assertThat(count(record.topic())).isEqualTo(before + 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timedOutSendDoesNotCount() {
        var record = record(KafkaTopics.MATCH_STARTING_SOON);
        double before = count(record.topic());
        var pending = new CompletableFuture<SendResult<Object, Object>>();
        pending.orTimeout(100, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> recoverer(template(pending), metrics)
                .accept(record, null, new IllegalArgumentException("invalid")))
                .isInstanceOf(KafkaException.class);
        assertThat(count(record.topic())).isEqualTo(before);
    }

    @Test
    void metricsFailureDoesNotRepublishAcknowledgedDlt() {
        var record = record(KafkaTopics.MATCH_WITHDRAWAL_REJECTED);
        var template = template(CompletableFuture.completedFuture(sendResult()));
        KafkaDltMetrics failingMetrics = mock(KafkaDltMetrics.class);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(failingMetrics).recordPublished(record.topic());

        recoverer(template, failingMetrics).accept(record, null, new IllegalArgumentException("invalid"));

        verify(template, times(1)).send(any(ProducerRecord.class));
    }

    private static ConsumerAwareRecordRecoverer recoverer(KafkaTemplate<Object, Object> template,
                                                           KafkaDltMetrics metrics) {
        return KafkaErrorHandlerConfig.dltRecoverer(template, metrics);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<Object, Object> template(CompletableFuture<SendResult<Object, Object>> future) {
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(future);
        return template;
    }

    private static ConsumerRecord<String, String> record(String topic) {
        return new ConsumerRecord<>(topic, 0, 1L, "57", "payload");
    }

    private static SendResult<Object, Object> sendResult() {
        return new SendResult<>(new ProducerRecord<>("dlt", "payload"), mock(RecordMetadata.class));
    }

    private double count(String topic) {
        Counter counter = registry.find("sporty.kafka.dlt.published").tag("topic", topic).counter();
        return counter == null ? 0 : counter.count();
    }
}
