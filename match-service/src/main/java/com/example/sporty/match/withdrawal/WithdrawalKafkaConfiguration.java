package com.example.sporty.match.withdrawal;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="match.withdrawal.enabled",havingValue="true")
public class WithdrawalKafkaConfiguration {
    /** DLT partition layout is independent; Kafka routes by the retained key. */
    static TopicPartition deadLetterDestination(org.apache.kafka.clients.consumer.ConsumerRecord<?,?> record){
        return new TopicPartition(record.topic()+".DLT",-1);
    }
    @Bean KafkaTemplate<String,String> withdrawalKafkaTemplate(@Value("${spring.kafka.bootstrap-servers:localhost:9092}")String brokers){
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers",brokers,"key.serializer",StringSerializer.class,"value.serializer",StringSerializer.class,"acks","all","enable.idempotence",true)));
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String,String> withdrawalKafkaFactory(KafkaTemplate<String,String> withdrawalKafkaTemplate,@Value("${spring.kafka.bootstrap-servers:localhost:9092}")String brokers){
        var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of("bootstrap.servers",brokers,"key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class,"enable.auto.commit",false,"auto.offset.reset","earliest")));
        var recovery=new DeadLetterPublishingRecoverer(withdrawalKafkaTemplate,(record,ex)->deadLetterDestination(record));
        recovery.setFailIfSendResultIsError(true);
        var errors=new DefaultErrorHandler(recovery,new FixedBackOff(1000,3));errors.addRetryableExceptions(IllegalArgumentException.class,com.fasterxml.jackson.core.JsonProcessingException.class);
        factory.setCommonErrorHandler(errors);factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);return factory;
    }
}
