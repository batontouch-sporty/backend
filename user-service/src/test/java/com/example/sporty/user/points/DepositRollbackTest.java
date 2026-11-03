package com.example.sporty.user.points;

import static com.example.sporty.user.points.DepositEventConsumer.CHARGE_REQUESTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;

import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 결과 이벤트 직렬화가 실패하면 정산(잔액·이력·processed_events)도 롤백돼, 같은 이벤트 재처리 때 결과가 나간다.
// 실제 커밋·롤백을 보려고 테스트 트랜잭션 없이 따로 둔 H2 DB를 쓴다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user_deposit_rollback_test;MODE=MariaDB;DB_CLOSE_DELAY=-1")
class DepositRollbackTest {
    @Autowired DepositEventConsumer consumer;
    @Autowired UserRepository users;
    @Autowired PointHistoryRepository history;
    @Autowired OutboxEventRepository outbox;
    @MockitoSpyBean ObjectMapper json;

    @Test
    void serializationFailureRollsBackSettlementAndRetryPublishesOnce() throws Exception {
        long uid = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@x.test")
                .password("test").gender(Gender.MALE).points(50).build()).getId();
        UUID request = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","requestId":"%s","userId":%d,"matchId":7,"occurredAt":"2026-11-03T01:00:00Z","requestType":"JOIN"}
                """.formatted(UUID.randomUUID(), request, uid);

        doThrow(new JsonProcessingException("serialize failed") {}).doCallRealMethod()
                .when(json).writeValueAsString(anyMap());
        assertThatThrownBy(() -> consumer.handle(CHARGE_REQUESTED, payload)).isInstanceOf(JsonProcessingException.class);
        assertThat(users.findById(uid).orElseThrow().getPoints()).isEqualTo(50);
        assertThat(history.findByRequestId(request.toString())).isEmpty();

        consumer.handle(CHARGE_REQUESTED, payload);
        assertThat(users.findById(uid).orElseThrow().getPoints()).isEqualTo(40);
        assertThat(history.findByRequestId(request.toString())).singleElement()
                .extracting(PointHistory::getReason).isEqualTo(PointHistory.Reason.DEPOSIT_CHARGED);
        assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateId().equals(String.valueOf(uid))).singleElement()
                .satisfies(e -> assertThat(e.getEventType()).isEqualTo(DepositEventConsumer.CHARGED));
    }
}
