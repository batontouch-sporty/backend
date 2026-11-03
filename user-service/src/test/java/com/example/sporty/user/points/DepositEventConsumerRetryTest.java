package com.example.sporty.user.points;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 일시 장애로 나는 IllegalStateException은 IllegalArgumentException(즉시 DLT)으로 바꾸지 않고 재시도에 맡긴다.
class DepositEventConsumerRetryTest {
    @Test
    void transientIllegalStateIsLeftRetryable() {
        var points = mock(PointSettlementService.class);
        when(points.apply(any())).thenThrow(new IllegalStateException("EntityManagerFactory is closed"));
        var consumer = new DepositEventConsumer(points, mock(OutboxEventRepository.class), new ObjectMapper(),
                Clock.systemUTC());

        assertThatThrownBy(() -> consumer.handle(DepositEventConsumer.CHARGE_REQUESTED, """
                {"eventId":"%s","requestId":"%s","userId":1,"matchId":7,"occurredAt":"2026-11-03T01:00:00Z","requestType":"JOIN"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .isExactlyInstanceOf(IllegalStateException.class);
    }
}
