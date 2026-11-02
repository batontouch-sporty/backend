package com.example.sporty.notification.events.metrics;

import com.example.sporty.notification.events.KafkaTopics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class KafkaDltMetrics {
    private static final Set<String> TOPICS = Set.of(
            KafkaTopics.MATCH_JOINED,
            KafkaTopics.MATCH_STATUS_CHANGED,
            KafkaTopics.MATCH_WITHDRAWAL_REJECTED,
            KafkaTopics.MATCH_WAITLIST_PROMOTED,
            KafkaTopics.MATCH_STARTING_SOON);
    private final MeterRegistry registry;

    public void recordPublished(String topic) {
        if (!TOPICS.contains(topic)) {
            throw new IllegalArgumentException("Unsupported notification topic: " + topic);
        }
        // 재시작 시 초기화되며 DLT ACK 뒤 offset commit 실패로 같은 레코드가 다시 발행될 수 있다.
        registry.counter("sporty.kafka.dlt.published", "topic", topic).increment();
    }
}
