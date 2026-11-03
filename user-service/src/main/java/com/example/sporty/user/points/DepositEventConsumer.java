package com.example.sporty.user.points;

import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.points.PointSettlementService.Action;
import com.example.sporty.user.points.PointSettlementService.Command;
import com.example.sporty.user.points.PointSettlementService.Result;
import com.example.sporty.user.withdrawal.domain.entity.OutboxEventEntity;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매칭의 보증금·출석 정산 요청을 받아 포인트를 정산하고, 결과 이벤트를 같은 트랜잭션에 Outbox로 남긴다.
 * 발행은 탈퇴 Saga와 같은 WithdrawalOutboxRelay가 한다. 같은 eventId는 PointSettlementService가 processed_events로 거른다.
 * 필수 값 누락·알 수 없는 값·없는 회원은 IllegalArgumentException으로 재시도 없이 "<원본 토픽>.DLT"로 간다(WithdrawalSagaConfig).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepositEventConsumer {

    // 구독: key = userId
    public static final String CHARGE_REQUESTED = "match.deposit-charge-requested.v1";
    public static final String REFUND_REQUESTED = "match.deposit-refund-requested.v1";
    public static final String ATTENDANCE_REQUESTED = "match.attendance-settlement-requested.v1";
    // 발행: key = userId
    public static final String CHARGED = "user.deposit-charged.v1";
    public static final String CHARGE_REJECTED = "user.deposit-charge-rejected.v1";
    public static final String REFUND_COMPLETED = "user.deposit-refund-completed.v1";
    public static final String SUSPENDED = "user.participation-suspended.v1";
    public static final String GROUP = "user-deposit-v1";

    private static final Set<String> REQUEST_TYPES = Set.of("CREATE", "JOIN", "PROMOTE");
    private static final Set<String> REFUND_REASONS =
            Set.of("CONFIRMATION_FAILED", "PARTICIPANT_CANCELLED", "MATCH_CANCELLED");
    private static final Set<String> ATTENDANCE_RESULTS = Set.of("ATTENDED", "NO_SHOW", "UNDER_MIN_PARTICIPANTS");

    private final PointSettlementService points;
    private final OutboxEventRepository outbox;
    private final ObjectMapper json;
    private final Clock clock;

    @KafkaListener(topics = {CHARGE_REQUESTED, REFUND_REQUESTED, ATTENDANCE_REQUESTED}, groupId = GROUP)
    @Transactional
    public void handle(@Header(KafkaHeaders.RECEIVED_TOPIC) String topic, String payload) throws JsonProcessingException {
        JsonNode body = json.readTree(payload);
        Action action = switch (topic) {
            case CHARGE_REQUESTED -> { oneOf(body, "requestType", REQUEST_TYPES); yield Action.CHARGE; }
            case REFUND_REQUESTED -> { oneOf(body, "reason", REFUND_REASONS); yield Action.REFUND; }
            case ATTENDANCE_REQUESTED -> Action.valueOf(oneOf(body, "result", ATTENDANCE_RESULTS));
            default -> throw new IllegalArgumentException("알 수 없는 토픽: " + topic);
        };
        try {
            OffsetDateTime.parse(text(body, "occurredAt"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("occurredAt 형식 오류", e);
        }
        Command cmd = new Command(text(body, "eventId"), UUID.fromString(text(body, "requestId")),
                positive(body, "userId"), positive(body, "matchId"), action);
        Result result;
        try {
            result = points.apply(cmd);
        } catch (UserNotFoundException e) {
            // 없는 회원은 다시 해도 같다. IllegalArgumentException으로 바꿔 바로 DLT로 보낸다.
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        log.info("포인트 정산: topic={}, requestId={}, userId={}, outcome={}",
                topic, cmd.requestId(), cmd.userId(), result.outcome());

        switch (result.outcome()) {
            case CHARGED -> publish(CHARGED, cmd, Map.of());
            case INSUFFICIENT_POINTS, PARTICIPATION_SUSPENDED, USER_NOT_ACTIVE, REQUEST_CANCELLED ->
                    publish(CHARGE_REJECTED, cmd, Map.of("reason", result.outcome().name()));
            case RETURNED, CANCELLED_BEFORE_CHARGE -> {
                // 출석 정산 결과는 매칭에 받는 쪽이 없다. 반환 요청에만 답한다.
                if (action == Action.REFUND) {
                    publish(REFUND_COMPLETED, cmd, Map.of("outcome", result.outcome().name(),
                            "creditedPoints", result.creditedPoints()));
                }
            }
            case FORFEITED -> {
                if (result.newSuspension() != null) {
                    publish(SUSPENDED, cmd, Map.of("suspendedUntil", result.newSuspension()
                            .atZone(PointSettlementService.SEOUL).toOffsetDateTime().toString()));
                }
            }
            // 노쇼 몰수 뒤 반환·탈퇴 회원 반환은 돌려줄 포인트가 없다. 매칭의 반환 대기가 남지 않게 RETURNED, 0으로 닫는다.
            case ALREADY_SETTLED, WITHDRAWN -> {
                if (action == Action.REFUND) {
                    log.warn("반환할 보증금 없음, 0으로 완료: requestId={}, userId={}, outcome={}",
                            cmd.requestId(), cmd.userId(), result.outcome());
                    publish(REFUND_COMPLETED, cmd, Map.of("outcome", "RETURNED", "creditedPoints", 0));
                }
            }
            case NO_CHARGE_RECORD -> log.warn("차감 이력 없는 출석 정산, 처리 완료로 기록: requestId={}, userId={}",
                    cmd.requestId(), cmd.userId());
            // DUPLICATE는 처음 처리 때 이미 남겼다.
            case DUPLICATE -> { }
        }
    }

    private void publish(String topic, Command cmd, Map<String, Object> extra) throws JsonProcessingException {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("requestId", cmd.requestId().toString());
        payload.put("userId", cmd.userId());
        payload.put("matchId", cmd.matchId());
        payload.put("occurredAt", OffsetDateTime.now(clock).toString());
        payload.putAll(extra);
        outbox.save(new OutboxEventEntity(eventId, String.valueOf(cmd.userId()), topic, json.writeValueAsString(payload)));
    }

    private static String text(JsonNode body, String key) {
        JsonNode v = body.path(key);
        if (!v.isTextual() || v.asText().isBlank()) throw new IllegalArgumentException("필수 값 누락: " + key);
        return v.asText();
    }

    private static long positive(JsonNode body, String key) {
        JsonNode v = body.path(key);
        if (!v.isIntegralNumber() || v.asLong() <= 0) throw new IllegalArgumentException("잘못된 값: " + key);
        return v.asLong();
    }

    private static String oneOf(JsonNode body, String key, Set<String> allowed) {
        String v = text(body, key);
        if (!allowed.contains(v)) throw new IllegalArgumentException("알 수 없는 " + key + ": " + v);
        return v;
    }
}
