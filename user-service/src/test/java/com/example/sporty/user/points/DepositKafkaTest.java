package com.example.sporty.user.points;

import static com.example.sporty.user.points.DepositEventConsumer.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.withdrawal.repository.ProcessedEventRepository;
import com.example.sporty.user.withdrawal.service.WithdrawalOutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 내장 브로커로 차감 요청 구독 → Outbox 발행(key = userId), 필수 값이 빠진 요청은 DLT를 확인한다. H2 DB를 따로 쓴다.
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:user_deposit_kafka_test;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1, topics = {CHARGE_REQUESTED, CHARGE_REQUESTED + ".DLT", CHARGED})
class DepositKafkaTest {
    static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired UserRepository users;
    @Autowired ProcessedEventRepository processed;
    @Autowired WithdrawalOutboxRelay relay;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper json;
    @MockitoBean RefreshTokenService refreshTokens;

    @Test
    void chargeRequestIsSettledAndChargedIsPublished() throws Exception {
        long uid = users.save(UserEntity.builder().email(UUID.randomUUID() + "@x.test")
                .password("test").gender(Gender.FEMALE).build()).getId();
        String eventId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        kafka.send(CHARGE_REQUESTED, String.valueOf(uid), """
                {"eventId":"%s","requestId":"%s","userId":%d,"matchId":3,"occurredAt":"2026-11-03T01:00:00Z","requestType":"CREATE"}
                """.formatted(eventId, requestId, uid));
        await().atMost(WAIT).until(() -> processed.existsById(eventId));
        assertThat(users.findById(uid).orElseThrow().getPoints()).isEqualTo(40);

        try (Consumer<String, String> charged = consumer()) {
            broker.consumeFromAnEmbeddedTopic(charged, CHARGED);
            assertThat(relay.publishPending()).isEqualTo(1);
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(charged, CHARGED, WAIT);
            assertThat(record.key()).isEqualTo(String.valueOf(uid));
            JsonNode body = json.readTree(record.value());
            assertThat(body.path("requestId").asText()).isEqualTo(requestId);
            assertThat(body.path("userId").asLong()).isEqualTo(uid);
        }
    }

    @Test
    void requestWithoutRequiredFieldGoesToDeadLetterTopic() {
        String broken = """
                {"eventId":"%s","userId":1,"matchId":3,"occurredAt":"2026-11-03T01:00:00Z","requestType":"JOIN"}
                """.formatted(UUID.randomUUID());
        try (Consumer<String, String> dlt = consumer()) {
            broker.consumeFromAnEmbeddedTopic(dlt, CHARGE_REQUESTED + ".DLT");
            kafka.send(CHARGE_REQUESTED, "1", broken);
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(dlt, CHARGE_REQUESTED + ".DLT", WAIT);
            assertThat(record.value()).isEqualTo(broken);
        }
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("check-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
