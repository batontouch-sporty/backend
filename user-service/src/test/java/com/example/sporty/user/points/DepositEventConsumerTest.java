package com.example.sporty.user.points;

import static com.example.sporty.user.points.DepositEventConsumer.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.withdrawal.domain.entity.OutboxEventEntity;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.example.sporty.user.withdrawal.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 매칭 요청 payload → 정산 → Outbox 결과 이벤트. 테스트마다 롤백한다.
@SpringBootTest
@Transactional
class DepositEventConsumerTest {
    @Autowired DepositEventConsumer consumer;
    @Autowired UserRepository users;
    @Autowired OutboxEventRepository outbox;
    @Autowired ObjectMapper json;
    @Autowired ProcessedEventRepository processed;

    private long member(int balance) {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@x.test")
                .password("test").gender(Gender.MALE).points(balance).build()).getId();
    }

    private String send(String topic, long uid, UUID request, String extra) throws Exception {
        String eventId = UUID.randomUUID().toString();
        send(topic, eventId, uid, request, extra);
        return eventId;
    }

    private void send(String topic, String eventId, long uid, UUID request, String extra) throws Exception {
        consumer.handle(topic, """
                {"eventId":"%s","requestId":"%s","userId":%d,"matchId":7,"occurredAt":"2026-11-03T01:00:00Z",%s}
                """.formatted(eventId, request, uid, extra));
    }

    private String charge(long uid, UUID request) throws Exception {
        return send(CHARGE_REQUESTED, uid, request, "\"requestType\":\"JOIN\"");
    }

    private void refund(long uid, UUID request) throws Exception {
        send(REFUND_REQUESTED, uid, request, "\"reason\":\"PARTICIPANT_CANCELLED\"");
    }

    private void attendance(long uid, UUID request, String result) throws Exception {
        send(ATTENDANCE_REQUESTED, uid, request, "\"outcome\":\"" + result + "\"");
    }

    // 이 회원 key로 쌓인 결과 이벤트(저장 순서)
    private List<JsonNode> events(long uid, String topic) {
        return outbox.findAll().stream()
                .filter(e -> e.getAggregateId().equals(String.valueOf(uid)) && e.getEventType().equals(topic))
                .sorted(java.util.Comparator.comparing(OutboxEventEntity::getCreatedAt))
                .map(e -> {
                    try { return json.readTree(e.getPayload()); } catch (Exception ex) { throw new RuntimeException(ex); }
                }).toList();
    }

    private long eventCount(long uid) {
        return outbox.findAll().stream().filter(e -> e.getAggregateId().equals(String.valueOf(uid))).count();
    }

    private int balance(long uid) { return users.findById(uid).orElseThrow().getPoints(); }

    @Test
    void chargeSuccessPublishesChargedWithCommonFields() throws Exception {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        String requestEventId = charge(uid, request);

        assertThat(balance(uid)).isEqualTo(40);
        JsonNode e = events(uid, CHARGED).get(0);
        assertThat(e.path("eventId").asText()).isNotEqualTo(requestEventId);
        UUID.fromString(e.path("eventId").asText());
        assertThat(e.path("requestId").asText()).isEqualTo(request.toString());
        assertThat(e.path("userId").isIntegralNumber()).isTrue();
        assertThat(e.path("userId").asLong()).isEqualTo(uid);
        assertThat(e.path("matchId").asLong()).isEqualTo(7);
        assertThat(OffsetDateTime.parse(e.path("occurredAt").asText()).getOffset()).isNotNull();
        assertThat(eventCount(uid)).isEqualTo(1);
    }

    @Test
    void insufficientPointsIsRejected() throws Exception {
        long uid = member(5);
        charge(uid, UUID.randomUUID());
        assertThat(events(uid, CHARGE_REJECTED)).singleElement()
                .satisfies(e -> assertThat(e.path("reason").asText()).isEqualTo("INSUFFICIENT_POINTS"));
        assertThat(balance(uid)).isEqualTo(5);
    }

    @Test
    void suspendedMemberIsRejected() throws Exception {
        long uid = member(50);
        UserEntity user = users.findById(uid).orElseThrow();
        user.suspendUntil(LocalDateTime.now().plusYears(1));
        users.saveAndFlush(user);
        charge(uid, UUID.randomUUID());
        assertThat(events(uid, CHARGE_REJECTED)).singleElement()
                .satisfies(e -> assertThat(e.path("reason").asText()).isEqualTo("PARTICIPATION_SUSPENDED"));
    }

    @Test
    void withdrawnMemberIsRejectedAndLateRefundClosesWithZero() throws Exception {
        long uid = member(50);
        UUID charged = UUID.randomUUID();
        charge(uid, charged);
        UserEntity user = users.findById(uid).orElseThrow();
        user.startWithdrawal();
        user.completeWithdrawal();
        users.saveAndFlush(user);
        charge(uid, UUID.randomUUID());
        assertThat(events(uid, CHARGE_REJECTED)).singleElement()
                .satisfies(e -> assertThat(e.path("reason").asText()).isEqualTo("USER_NOT_ACTIVE"));
        refund(uid, charged);
        assertThat(events(uid, REFUND_COMPLETED)).singleElement().satisfies(e -> {
            assertThat(e.path("outcome").asText()).isEqualTo("RETURNED");
            assertThat(e.path("creditedPoints").asInt()).isZero();
            assertThat(e.path("requestId").asText()).isEqualTo(charged.toString());
        });
        assertThat(balance(uid)).isEqualTo(40);
        // 탈퇴 회원 출석 정산은 결과를 보내지 않는다.
        long before = eventCount(uid);
        attendance(uid, charged, "ATTENDED");
        assertThat(eventCount(uid)).isEqualTo(before);
    }

    @Test
    void refundAfterNoShowForfeitClosesWithZero() throws Exception {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        charge(uid, request);
        attendance(uid, request, "NO_SHOW");
        refund(uid, request);
        assertThat(events(uid, REFUND_COMPLETED)).singleElement().satisfies(e -> {
            assertThat(e.path("outcome").asText()).isEqualTo("RETURNED");
            assertThat(e.path("creditedPoints").asInt()).isZero();
        });
        assertThat(balance(uid)).isEqualTo(40);
    }

    // 없는 회원은 재시도해도 같으므로 IllegalArgumentException(비재시도 → 바로 DLT)으로 나간다.
    @Test
    void unknownMemberIsNotRetryable() {
        assertThatThrownBy(() -> charge(Long.MAX_VALUE, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 보증금 기능이 꺼져 있을 때 확정된 참가의 출석 정산: DLT 없이 처리 완료로 기록하고 발행하지 않는다.
    @Test
    void attendanceWithoutChargeIsRecordedWithoutEvent() throws Exception {
        long uid = member(50);
        String eventId = UUID.randomUUID().toString();
        send(ATTENDANCE_REQUESTED, eventId, uid, UUID.randomUUID(), "\"outcome\":\"ATTENDED\"");
        assertThat(processed.existsById(eventId)).isTrue();
        assertThat(eventCount(uid)).isZero();
        assertThat(balance(uid)).isEqualTo(50);
        assertThat(users.findById(uid).orElseThrow().getAttendanceRewardCount()).isZero();
    }

    @Test
    void refundArrivingBeforeChargeCancelsAndLaterChargeIsRejected() throws Exception {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        refund(uid, request);
        assertThat(events(uid, REFUND_COMPLETED)).singleElement().satisfies(e -> {
            assertThat(e.path("outcome").asText()).isEqualTo("CANCELLED_BEFORE_CHARGE");
            assertThat(e.path("creditedPoints").isIntegralNumber()).isTrue();
            assertThat(e.path("creditedPoints").asInt()).isZero();
        });
        charge(uid, request);
        assertThat(events(uid, CHARGE_REJECTED)).singleElement()
                .satisfies(e -> assertThat(e.path("reason").asText()).isEqualTo("REQUEST_CANCELLED"));
        assertThat(balance(uid)).isEqualTo(50);
    }

    @Test
    void duplicateEventIdIsProcessedOnce() throws Exception {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        String eventId = charge(uid, request);
        send(CHARGE_REQUESTED, eventId, uid, request, "\"requestType\":\"JOIN\"");
        assertThat(balance(uid)).isEqualTo(40);
        assertThat(eventCount(uid)).isEqualTo(1);
    }

    @Test
    void sameRequestWithNewEventIdRepeatsSameResult() throws Exception {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        charge(uid, request);
        charge(uid, request);
        assertThat(events(uid, CHARGED)).hasSize(2)
                .allSatisfy(e -> assertThat(e.path("requestId").asText()).isEqualTo(request.toString()));
        assertThat(balance(uid)).isEqualTo(40);

        refund(uid, request);
        refund(uid, request);
        assertThat(events(uid, REFUND_COMPLETED)).hasSize(2).allSatisfy(e -> {
            assertThat(e.path("outcome").asText()).isEqualTo("RETURNED");
            assertThat(e.path("creditedPoints").asInt()).isEqualTo(10);
        });
        assertThat(balance(uid)).isEqualTo(50);

        UUID poor = UUID.randomUUID();
        long other = member(0);
        charge(other, poor);
        charge(other, poor);
        assertThat(events(other, CHARGE_REJECTED)).hasSize(2)
                .allSatisfy(e -> assertThat(e.path("reason").asText()).isEqualTo("INSUFFICIENT_POINTS"));
    }

    @Test
    void refundAfterChargeReturnsDeposit() throws Exception {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        charge(uid, request);
        refund(uid, request);
        assertThat(events(uid, REFUND_COMPLETED)).singleElement().satisfies(e -> {
            assertThat(e.path("outcome").asText()).isEqualTo("RETURNED");
            assertThat(e.path("creditedPoints").asInt()).isEqualTo(10);
            assertThat(e.path("requestId").asText()).isEqualTo(request.toString());
        });
        assertThat(balance(uid)).isEqualTo(50);
    }

    @Test
    void fifthAttendancePaysBonusWithoutResultEvents() throws Exception {
        long uid = member(50);
        for (int i = 0; i < 5; i++) {
            UUID request = UUID.randomUUID();
            charge(uid, request);
            attendance(uid, request, "ATTENDED");
        }
        assertThat(balance(uid)).isEqualTo(60);
        assertThat(eventCount(uid)).isEqualTo(5); // 차감 결과 5건뿐, 출석 결과는 보내지 않는다.
        UUID under = UUID.randomUUID();
        charge(uid, under);
        attendance(uid, under, "UNDER_MIN_PARTICIPANTS");
        assertThat(balance(uid)).isEqualTo(60);
        assertThat(eventCount(uid)).isEqualTo(6);
    }

    @Test
    void noShowThatSuspendsPublishesSuspension() throws Exception {
        long uid = member(10);
        UUID request = UUID.randomUUID();
        charge(uid, request);
        attendance(uid, request, "NO_SHOW");

        JsonNode e = events(uid, SUSPENDED).get(0);
        assertThat(e.path("requestId").asText()).isEqualTo(request.toString());
        assertThat(e.path("matchId").asLong()).isEqualTo(7);
        OffsetDateTime until = OffsetDateTime.parse(e.path("suspendedUntil").asText());
        assertThat(until.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(until.toLocalDateTime()).isEqualTo(users.findById(uid).orElseThrow().getSuspendedUntil());

        // 같은 노쇼 재요청은 정지를 다시 만들지 않는다.
        attendance(uid, request, "NO_SHOW");
        assertThat(events(uid, SUSPENDED)).hasSize(1);
    }

    @Test
    void noShowWithPointsLeftDoesNotPublish() throws Exception {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        charge(uid, request);
        attendance(uid, request, "NO_SHOW");
        assertThat(events(uid, SUSPENDED)).isEmpty();
        assertThat(balance(uid)).isEqualTo(40);
    }

    @Test
    void contractViolationsAreRejectedBeforeSettlement() {
        long uid = member(50);
        UUID request = UUID.randomUUID();
        assertThatThrownBy(() -> send(CHARGE_REQUESTED, uid, request, "\"requestType\":\"UNKNOWN\""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.handle(CHARGE_REQUESTED, """
                {"eventId":"%s","userId":%d,"matchId":7,"occurredAt":"2026-11-03T01:00:00Z","requestType":"JOIN"}
                """.formatted(UUID.randomUUID(), uid))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.handle(REFUND_REQUESTED, """
                {"eventId":"%s","requestId":"%s","userId":"%d","matchId":7,"occurredAt":"2026-11-03T01:00:00Z","reason":"MATCH_CANCELLED"}
                """.formatted(UUID.randomUUID(), request, uid))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.handle(ATTENDANCE_REQUESTED, """
                {"eventId":"%s","requestId":"%s","userId":%d,"matchId":7,"occurredAt":"yesterday","outcome":"ATTENDED"}
                """.formatted(UUID.randomUUID(), request, uid))).isInstanceOf(IllegalArgumentException.class);
        assertThat(balance(uid)).isEqualTo(50);
        assertThat(eventCount(uid)).isZero();
    }
}
