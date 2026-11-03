package com.example.sporty.notification.events.metrics;

import com.example.sporty.notification.events.KafkaTopics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class KafkaProcessingMetrics {
    private static final Set<String> TOPICS = Set.of(
            KafkaTopics.MATCH_JOINED,
            KafkaTopics.MATCH_STATUS_CHANGED,
            KafkaTopics.MATCH_WITHDRAWAL_REJECTED,
            KafkaTopics.MATCH_WAITLIST_PROMOTED,
            KafkaTopics.MATCH_STARTING_SOON);
    private final MeterRegistry registry;

    public void recordProcessed(String topic) {
        if (!TOPICS.contains(topic)) {
            throw new IllegalArgumentException("Unsupported notification topic: " + topic);
        }
        // Counter는 프로세스 재시작 시 초기화되며 DB의 영속 처리 건수와 다를 수 있다.
        registry.counter("sporty.kafka.records.processed", "topic", topic, "group", "notification-service")
                .increment();
    }
}
