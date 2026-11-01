package com.example.sporty.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.users.service.UserService;
import com.example.sporty.user.withdrawal.domain.entity.SagaStatus;
import com.example.sporty.user.withdrawal.events.KafkaTopics;
import com.example.sporty.user.withdrawal.repository.ProcessedEventRepository;
import com.example.sporty.user.withdrawal.repository.WithdrawalSagaRepository;
import com.example.sporty.user.withdrawal.service.WithdrawalOutboxRelay;

/**
 * 실제 Kafka(테스트용 내장 브로커)로 탈퇴 요청 발행 → 매치 결과(R7 형식) 구독 → 회원 상태 반영, 깨진 메시지 DLT를 확인한다.
 * 롤백되지 않으므로 다른 테스트와 섞이지 않게 H2 DB를 따로 쓴다.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:user_saga_kafka_test;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1, topics = {
        KafkaTopics.WITHDRAWAL_REQUESTED,
        KafkaTopics.WITHDRAWAL_CLEANED,
        KafkaTopics.WITHDRAWAL_REJECTED,
        KafkaTopics.WITHDRAWAL_CLEANED + KafkaTopics.DLT_SUFFIX
})
class WithdrawalSagaKafkaTest {
    static final Duration WAIT = Duration.ofSeconds(20);
    static final String PASSWORD = "TestPass123!";

    @Autowired UserService userService;
    @Autowired WithdrawalOutboxRelay relay;
    @Autowired UserRepository users;
    @Autowired WithdrawalSagaRepository sagas;
    @Autowired ProcessedEventRepository processed;
    @Autowired PasswordEncoder passwords;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper json;
    @MockitoBean RefreshTokenService refreshTokens;

    // 접수 → Outbox 발행(key = userId, R7 요청 형식) → match가 보낼 CLEANED 수신 → WITHDRAWN
    @Test
    void requestIsPublishedAndCleanedResultCompletesWithdrawal() throws Exception {
        UserEntity user = user();
        String sagaId = userService.withdrawal(user.getId(), PASSWORD).getSagaId();
        String requestEventId = sagas.findById(sagaId).orElseThrow().getClaimToken();

        try (Consumer<String, String> requests = consumer()) {
            broker.consumeFromAnEmbeddedTopic(requests, KafkaTopics.WITHDRAWAL_REQUESTED);
            assertThat(relay.publishPending()).isEqualTo(1);

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(requests,
                    KafkaTopics.WITHDRAWAL_REQUESTED, WAIT);
            assertThat(record.key()).isEqualTo(String.valueOf(user.getId()));
            JsonNode payload = json.readTree(record.value());
            assertThat(payload.path("eventId").asText()).isEqualTo(requestEventId);
            assertThat(payload.path("sagaId").asText()).isEqualTo(sagaId);
            assertThat(payload.path("userId").asLong()).isEqualTo(user.getId());
            assertThat(payload.path("requestedAt").asText()).endsWith("Z");
            assertThat(payload.path("deadlineAt").asText()).endsWith("Z");
        }

        // match-service(#36)가 보내는 결과 형식 그대로
        String resultId = UUID.randomUUID().toString();
        kafka.send(KafkaTopics.WITHDRAWAL_CLEANED, String.valueOf(user.getId()), """
                {"sagaId":"%s","requestEventId":"%s","userId":%d,"cleanedAt":"2026-11-01T03:00:05Z",
                 "canceledMatchIds":[],"leftMatchIds":[57],"eventId":"%s"}
                """.formatted(sagaId, requestEventId, user.getId(), resultId));

        await().atMost(WAIT).until(() -> processed.existsById(resultId));
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(sagas.findById(sagaId).orElseThrow().getStatus()).isEqualTo(SagaStatus.CLEANED);
    }

    // REJECTED 수신 → ACTIVE 복구
    @Test
    void rejectedResultRestoresUser() {
        UserEntity user = user();
        String sagaId = userService.withdrawal(user.getId(), PASSWORD).getSagaId();
        String requestEventId = sagas.findById(sagaId).orElseThrow().getClaimToken();

        String resultId = UUID.randomUUID().toString();
        kafka.send(KafkaTopics.WITHDRAWAL_REJECTED, String.valueOf(user.getId()), """
                {"sagaId":"%s","requestEventId":"%s","userId":%d,"rejectedAt":"2026-11-01T03:00:05Z",
                 "reason":"OWNER_OF_IN_PROGRESS_MATCH","blockingMatchIds":[57],"eventId":"%s"}
                """.formatted(sagaId, requestEventId, user.getId(), resultId));

        await().atMost(WAIT).until(() -> processed.existsById(resultId));
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(sagas.findById(sagaId).orElseThrow().getReason()).isEqualTo("OWNER_OF_IN_PROGRESS_MATCH");
    }

    // JSON이 깨진 결과는 재시도 없이 DLT로
    @Test
    void brokenResultGoesToDeadLetterTopic() {
        String broken = "{not json " + UUID.randomUUID();
        try (Consumer<String, String> dlt = consumer()) {
            broker.consumeFromAnEmbeddedTopic(dlt, KafkaTopics.WITHDRAWAL_CLEANED + KafkaTopics.DLT_SUFFIX);
            kafka.send(KafkaTopics.WITHDRAWAL_CLEANED, "1", broken);

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(dlt,
                    KafkaTopics.WITHDRAWAL_CLEANED + KafkaTopics.DLT_SUFFIX, WAIT);
            assertThat(record.value()).isEqualTo(broken);
        }
    }

    private UserEntity user() {
        return users.save(UserEntity.builder().email(UUID.randomUUID().toString().substring(0, 20) + "@test.io")
                .password(passwords.encode(PASSWORD)).gender(Gender.FEMALE).status(UserStatus.ACTIVE).build());
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("check-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
