package com.example.sporty.match.withdrawal;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

import java.util.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "match.withdrawal.enabled", havingValue = "true")
public class WithdrawalKafkaConfiguration {
    /** DLT partition layout is independent; Kafka routes by the retained key. */
    static TopicPartition deadLetterDestination(
            org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic() + ".DLT", -1);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> withdrawalKafkaFactory(
            KafkaTemplate<String, String> withdrawalKafkaTemplate,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String brokers,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setAutoStartup(autoStartup);
        factory.setConsumerFactory(
                new DefaultKafkaConsumerFactory<>(
                        Map.of(
                                "bootstrap.servers",
                                brokers,
                                "key.deserializer",
                                StringDeserializer.class,
                                "value.deserializer",
                                StringDeserializer.class,
                                "enable.auto.commit",
                                false,
                                "auto.offset.reset",
                                "earliest")));
        var recovery =
                new DeadLetterPublishingRecoverer(
                        withdrawalKafkaTemplate, (record, ex) -> deadLetterDestination(record));
        recovery.setFailIfSendResultIsError(true);
        var errors = new DefaultErrorHandler(recovery, new FixedBackOff(1000, 3));
        errors.addRetryableExceptions(
                IllegalArgumentException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class);
        factory.setCommonErrorHandler(errors);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
