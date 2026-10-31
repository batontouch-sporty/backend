package com.example.sporty.match.withdrawal;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import static org.assertj.core.api.Assertions.*;
class WithdrawalKafkaConfigurationTest {
    @Test void higherSourcePartitionDoesNotRequireMatchingDltPartition(){
        var record=new ConsumerRecord<String,String>("user.withdrawal-requested.v1",7,0L,"2","invalid");
        var destination=WithdrawalKafkaConfiguration.deadLetterDestination(record);
        assertThat(destination.topic()).isEqualTo("user.withdrawal-requested.v1.DLT");
        assertThat(destination.partition()).isEqualTo(-1);
    }
}
