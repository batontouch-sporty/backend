package com.example.sporty.match.withdrawal;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SAGA_TEST_BROKER",matches="localhost:39102")
class WithdrawalDltBrokerTest {
    /** A source partition absent from the one-partition DLT must still be recoverable. */
    @Test void sourcePartitionSevenPublishesToSinglePartitionDlt(){
        String broker=System.getenv("SAGA_TEST_BROKER"),payload="dlt-partition-review-"+UUID.randomUUID();
        var factory=new DefaultKafkaProducerFactory<String,String>(Map.of("bootstrap.servers",broker,"key.serializer",StringSerializer.class,"value.serializer",StringSerializer.class));
        var template=new KafkaTemplate<>(factory);
        try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",broker,"group.id","saga-dlt-review-"+UUID.randomUUID(),"key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class,"auto.offset.reset","earliest","enable.auto.commit",false))){
            var recoverer=new DeadLetterPublishingRecoverer(template,(record,ex)->WithdrawalKafkaConfiguration.deadLetterDestination(record));recoverer.setFailIfSendResultIsError(true);
            recoverer.accept(new ConsumerRecord<String,String>("user.withdrawal-requested.v1",7,0L,"2",payload),new IllegalArgumentException("review fixture"));
            consumer.subscribe(List.of("user.withdrawal-requested.v1.DLT"));
            boolean found=false;long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!found&&System.nanoTime()<end){for(var record:consumer.poll(Duration.ofMillis(300)))if(payload.equals(record.value())){assertThat(record.partition()).isZero();found=true;}}
            assertThat(found).isTrue();
        }finally{factory.destroy();}
    }
}
