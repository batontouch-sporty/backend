package com.example.sporty.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.sporty.notification.events.KafkaTopics;
import com.example.sporty.notification.events.repository.ProcessedEventRepository;
import com.example.sporty.notification.events.service.NotificationEventService;
import com.example.sporty.notification.notifications.repository.NotificationRepository;

/**
 * 실제 Kafka(테스트용 내장 브로커)로 구독 → 저장, 중복 무시, DLT를 확인한다.
 * 롤백되지 않으므로 다른 테스트와 섞이지 않게 H2 DB를 따로 쓴다.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:notification_kafka_test;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.group-id=notification-service",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1, topics = {
        KafkaTopics.MATCH_JOINED,
        KafkaTopics.MATCH_STATUS_CHANGED,
        KafkaTopics.MATCH_WITHDRAWAL_REJECTED,
        KafkaTopics.MATCH_WAITLIST_PROMOTED,
        KafkaTopics.MATCH_STARTING_SOON,
        KafkaTopics.MATCH_STATUS_CHANGED + KafkaTopics.DLT_SUFFIX,
        KafkaTopics.MATCH_WITHDRAWAL_REJECTED + KafkaTopics.DLT_SUFFIX
})
class MatchEventConsumerTest {
    static final Duration WAIT = Duration.ofSeconds(20);
    @Autowired KafkaTemplate<Object, Object> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired NotificationRepository notifications;
    @Autowired ProcessedEventRepository processedEvents;
    @MockitoSpyBean NotificationEventService eventService;

    // 이벤트 → 알림 저장, 같은 이벤트를 두 번 보내도 한 번만
    @Test
    void consumesJoinedEventOnceEvenIfDeliveredTwice() {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {"eventId":"%s","matchId":57,"title":"주말 풋살","userId":101,"ownerId":102,"current":3,"max":10,"extra":"무시"}
                """.formatted(eventId);

        kafka.send(KafkaTopics.MATCH_JOINED, "57", payload);
        kafka.send(KafkaTopics.MATCH_JOINED, "57", payload);

        await().atMost(WAIT).until(() -> processedEvents.existsById(eventId));
        // 두 번째 메시지까지 처리될 시간을 준 뒤에도 알림은 그대로 2건(참가자·방장)
        String marker = UUID.randomUUID().toString();
        kafka.send(KafkaTopics.MATCH_JOINED, "58", """
                {"eventId":"%s","matchId":58,"title":"확인용","userId":103}
                """.formatted(marker));
        await().atMost(WAIT).until(() -> processedEvents.existsById(marker));

        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(101L)).hasSize(1);
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(102L)).hasSize(1);
    }

    // match-service(#36)가 보내는 R7 형식(ownerId·sagaId·requestEventId·rejectedAt 포함)을 그대로 읽는다
    @Test
    void consumesR7SagaPayloadsFromMatchService() {
        String cancelled = UUID.randomUUID().toString();
        String rejected = UUID.randomUUID().toString();
        String sagaId = UUID.randomUUID().toString();

        kafka.send(KafkaTopics.MATCH_STATUS_CHANGED, "60", """
                {"eventId":"%s","matchId":60,"title":"탈퇴 테스트","from":"RECRUITING","to":"CANCELLED","ownerId":201,
                 "participantIds":[202,203],"reason":"모임장 회원 탈퇴로 인한 모임 취소","sagaId":"%s"}
                """.formatted(cancelled, sagaId));
        kafka.send(KafkaTopics.MATCH_WITHDRAWAL_REJECTED, "204", """
                {"eventId":"%s","sagaId":"%s","requestEventId":"%s","userId":204,"rejectedAt":"2026-11-01T03:00:00Z",
                 "reason":"DEADLINE_EXCEEDED","blockingMatchIds":[]}
                """.formatted(rejected, sagaId, UUID.randomUUID()));

        await().atMost(WAIT).until(() -> processedEvents.existsById(cancelled) && processedEvents.existsById(rejected));

        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(202L))
                .singleElement().extracting(n -> n.getBody())
                .isEqualTo("'탈퇴 테스트' 모임이 취소됐어요 (사유: 모임장 회원 탈퇴로 인한 모임 취소)");
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(201L)).isEmpty();
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(204L))
                .singleElement().extracting(n -> n.getBody())
                .isEqualTo("탈퇴 처리 시간이 지나 탈퇴가 취소됐어요. 잠시 후 다시 시도해 주세요");
    }

    // match-service(#65)가 보내는 승급 이벤트 형식을 그대로 읽어 승급된 회원에게 알림
    @Test
    void consumesWaitlistPromotedEventFromMatchService() {
        String eventId = UUID.randomUUID().toString();

        kafka.send(KafkaTopics.MATCH_WAITLIST_PROMOTED, "61", """
                {"matchId":61,"title":"평일 농구","userId":301,"eventId":"%s"}
                """.formatted(eventId));

        await().atMost(WAIT).until(() -> processedEvents.existsById(eventId));
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(301L))
                .singleElement().extracting(n -> n.getBody())
                .isEqualTo("'평일 농구' 대기가 풀려 참가가 확정됐어요");
    }

    // match-service(#79)가 보내는 시작 전 알림 형식(startAt 오프셋 포함, 방장 포함 참가자)을 그대로 읽는다
    @Test
    void consumesStartingSoonEventFromMatchService() {
        String eventId = UUID.randomUUID().toString();
        OffsetDateTime startAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(3).withSecond(0).withNano(0);

        kafka.send(KafkaTopics.MATCH_STARTING_SOON, "62", """
                {"eventId":"%s","matchId":62,"title":"주말 풋살","startAt":"%s","participantIds":[401,402]}
                """.formatted(eventId, startAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));

        await().atMost(WAIT).until(() -> processedEvents.existsById(eventId));
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(401L))
                .singleElement().extracting(n -> n.getBody()).asString()
                .startsWith("'주말 풋살' 모임이 ")
                .endsWith(startAt.format(DateTimeFormatter.ofPattern("HH:mm")) + "에 시작해요");
        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(402L)).hasSize(1);
    }

    // JSON이 깨진 메시지는 재시도 없이 DLT로
    @Test
    void brokenMessageGoesToDeadLetterTopic() {
        String broken = "{not json " + UUID.randomUUID();

        try (Consumer<String, String> dlt = dltConsumer()) {
            broker.consumeFromAnEmbeddedTopic(dlt, KafkaTopics.MATCH_WITHDRAWAL_REJECTED + KafkaTopics.DLT_SUFFIX);
            kafka.send(KafkaTopics.MATCH_WITHDRAWAL_REJECTED, "101", broken);

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(dlt,
                    KafkaTopics.MATCH_WITHDRAWAL_REJECTED + KafkaTopics.DLT_SUFFIX, WAIT);
            assertThat(record.value()).isEqualTo(broken);
        }
    }

    // 저장이 계속 실패하면 처음 1번 + 재시도 3번 뒤 DLT로
    @Test
    void failingEventIsRetriedThreeTimesThenSentToDeadLetterTopic() {
        String eventId = "fail-" + UUID.randomUUID();
        doThrow(new IllegalStateException("DB 장애 가정"))
                .when(eventService).handleMatchStatusChanged(argThat(e -> eventId.equals(e.eventId())));
        String payload = """
                {"eventId":"%s","matchId":57,"title":"주말 풋살","from":"RECRUITING","to":"CANCELLED","participantIds":[101]}
                """.formatted(eventId);

        try (Consumer<String, String> dlt = dltConsumer()) {
            broker.consumeFromAnEmbeddedTopic(dlt, KafkaTopics.MATCH_STATUS_CHANGED + KafkaTopics.DLT_SUFFIX);
            kafka.send(KafkaTopics.MATCH_STATUS_CHANGED, "57", payload);

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(dlt,
                    KafkaTopics.MATCH_STATUS_CHANGED + KafkaTopics.DLT_SUFFIX, WAIT);
            assertThat(record.value()).isEqualTo(payload);
        }
        verify(eventService, times(4)).handleMatchStatusChanged(argThat(e -> eventId.equals(e.eventId())));
        assertThat(processedEvents.existsById(eventId)).isFalse();
    }

    private Consumer<String, String> dltConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-check-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
