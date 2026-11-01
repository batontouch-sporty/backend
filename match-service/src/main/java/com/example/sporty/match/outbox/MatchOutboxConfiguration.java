package com.example.sporty.match.outbox;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/** One producer and one relay serve participation and withdrawal events. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MatchOutboxConfiguration {
    // Keep the existing bean name so the Saga listener's DLT publisher shares this producer.
    @Bean
    KafkaTemplate<String, String> withdrawalKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String brokers) {
        return new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(
                        Map.of(
                                "bootstrap.servers",
                                brokers,
                                "key.serializer",
                                StringSerializer.class,
                                "value.serializer",
                                StringSerializer.class,
                                "acks",
                                "all",
                                "enable.idempotence",
                                true,
                                "max.block.ms",
                                1000L)));
    }
}
