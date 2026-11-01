package com.example.sporty.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.withdrawal.domain.entity.OutboxEventEntity;
import com.example.sporty.user.withdrawal.events.KafkaTopics;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.example.sporty.user.withdrawal.service.WithdrawalOutboxRelay;

/**
 * Outbox 발행: Kafka ACK를 받은 행만 published_at을 채우고, 실패한 행은 남겨 다음 주기에 다시 보낸다.
 * 한 행의 실패가 다음 행 발행을 막지 않는다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user_outbox_test;MODE=MariaDB;DB_CLOSE_DELAY=-1")
class WithdrawalOutboxRelayTest {
    @Autowired WithdrawalOutboxRelay relay;
    @Autowired OutboxEventRepository outbox;
    @MockitoBean KafkaTemplate<String, String> kafka;
    @MockitoBean RefreshTokenService refreshTokens;

    @BeforeEach
    void setUp() {
        outbox.deleteAllInBatch();
    }

    @Test
    void publishesWithUserIdKeyAndMarksOnlyAfterAck() {
        String id = save("5", "{\"eventId\":\"a\"}");
        when(kafka.send(KafkaTopics.WITHDRAWAL_REQUESTED, "5", "{\"eventId\":\"a\"}")).thenReturn(ack());

        assertThat(relay.publishPending()).isEqualTo(1);

        assertThat(outbox.findById(id).orElseThrow().getPublishedAt()).isNotNull();
        // 이미 보낸 행은 다시 보내지 않는다
        assertThat(relay.publishPending()).isZero();
    }

    @Test
    void failedRowStaysPendingAndDoesNotBlockNextRow() {
        String failing = save("6", "{\"eventId\":\"fail\"}");
        String next = save("7", "{\"eventId\":\"ok\"}");
        when(kafka.send(anyString(), eq("6"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("브로커 오류 가정")));
        when(kafka.send(anyString(), eq("7"), anyString())).thenReturn(ack());

        assertThat(relay.publishPending()).isEqualTo(1);

        assertThat(outbox.findById(failing).orElseThrow().getPublishedAt()).isNull();
        assertThat(outbox.findById(next).orElseThrow().getPublishedAt()).isNotNull();

        // 다음 주기에 같은 행을 다시 보낸다
        when(kafka.send(anyString(), eq("6"), anyString())).thenReturn(ack());
        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(outbox.findById(failing).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void nothingToPublishSendsNothing() {
        assertThat(relay.publishPending()).isZero();
        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    private String save(String key, String payload) {
        String id = UUID.randomUUID().toString();
        outbox.save(new OutboxEventEntity(id, key, KafkaTopics.WITHDRAWAL_REQUESTED, payload));
        return id;
    }

    private static CompletableFuture<SendResult<String, String>> ack() {
        return CompletableFuture.completedFuture(null);
    }
}
