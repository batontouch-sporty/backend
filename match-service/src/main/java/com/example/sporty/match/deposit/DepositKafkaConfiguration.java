package com.example.sporty.match.deposit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.util.Map;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="match.deposit.enabled",havingValue="true",matchIfMissing=true)
public class DepositKafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String,String> depositKafkaFactory(
            @Qualifier("withdrawalKafkaTemplate") KafkaTemplate<String,String> kafka,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String brokers,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean startup) {
        var f=new ConcurrentKafkaListenerContainerFactory<String,String>();
        f.setAutoStartup(startup);
        f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of("bootstrap.servers",brokers,
                "key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class,
                "enable.auto.commit",false,"auto.offset.reset","earliest")));
        var dlt=new DeadLetterPublishingRecoverer(kafka,(r,e)->new TopicPartition(r.topic()+".DLT",-1));
        dlt.setFailIfSendResultIsError(true);
        var errors=new DefaultErrorHandler(dlt,new FixedBackOff(1000,3));
        f.setCommonErrorHandler(errors); f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return f;
    }
    @Bean DepositListener depositListener(DepositSaga saga,ObjectMapper mapper) { return new DepositListener(saga,mapper); }
    public static class DepositListener {
        private final DepositSaga saga; private final ObjectMapper mapper;
        public DepositListener(DepositSaga saga,ObjectMapper mapper) { this.saga=saga; this.mapper=mapper; }
        @KafkaListener(topics={"user.deposit-charged.v1","user.deposit-charge-rejected.v1",
                "user.deposit-refund-completed.v1","user.participation-suspended.v1"},
                groupId="match-deposit-v1",containerFactory="depositKafkaFactory")
        public void receive(ConsumerRecord<String,String> record) throws Exception {
            var body=mapper.readTree(record.value());
            if(!body.path("userId").isIntegralNumber() || !Long.toString(body.path("userId").asLong()).equals(record.key()))
                throw new IllegalArgumentException("Deposit event key must equal userId");
            saga.handle(record.topic(),body);
        }
    }
}
