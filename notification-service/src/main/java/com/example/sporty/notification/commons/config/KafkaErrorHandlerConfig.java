package com.example.sporty.notification.commons.config;

import com.example.sporty.notification.events.KafkaTopics;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// 알림 저장에 실패하면 1초 간격으로 3번 더 시도하고, 그래도 실패하면 "<토픽>.DLT"로 보낸다.
// JSON 형식 오류·eventId 누락은 다시 해도 같으므로 바로 DLT로 보낸다.
@Configuration
public class KafkaErrorHandlerConfig {

    static final long RETRY_INTERVAL_MS = 1000L;
    static final long MAX_RETRIES = 3L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        // 파티션은 -1로 두어 DLT 파티션 수가 원래 토픽과 달라도 보낼 수 있게 한다.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + KafkaTopics.DLT_SUFFIX, -1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }

}
