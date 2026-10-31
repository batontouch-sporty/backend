package com.example.sporty.match.withdrawal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WithdrawalOutboxRelayTest {
    static class Manager extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object t,org.springframework.transaction.TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}protected void doRollback(DefaultTransactionStatus s){}
    }
    @SuppressWarnings("unchecked")
    @Test void acknowledgmentPrecedesPublishedMarker(){
        var jdbc=mock(JdbcTemplate.class);var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("event-1"));
        when(jdbc.queryForList(anyString(),eq("event-1"))).thenReturn(List.of(Map.of("aggregate_id","2","event_type","match.withdrawal-cleaned.v1","payload","{\"eventId\":\"event-1\"}")));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture((SendResult<String,String>)null));
        new WithdrawalOutboxRelay(jdbc,kafka,new Manager()).publish();
        var order=inOrder(kafka,jdbc);order.verify(kafka).send(eq("match.withdrawal-cleaned.v1"),eq("2"),anyString());order.verify(jdbc).update(contains("published_at=CURRENT_TIMESTAMP"),eq("event-1"));
    }
    @SuppressWarnings("unchecked")
    @Test void failedSendLeavesOutboxUnpublished(){
        var jdbc=mock(JdbcTemplate.class);var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("event-1"));
        when(jdbc.queryForList(anyString(),eq("event-1"))).thenReturn(List.of(Map.of("aggregate_id","2","event_type","match.withdrawal-cleaned.v1","payload","{}")));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        new WithdrawalOutboxRelay(jdbc,kafka,new Manager()).publish();verify(jdbc,never()).update(anyString(),eq("event-1"));
    }
    @SuppressWarnings("unchecked")
    @Test void alreadyPublishedRowIsNotSentAgain(){
        var jdbc=mock(JdbcTemplate.class);var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("event-1"));
        when(jdbc.queryForList(anyString(),eq("event-1"))).thenReturn(List.of());
        new WithdrawalOutboxRelay(jdbc,kafka,new Manager()).publish();verifyNoInteractions(kafka);
    }
}
