package com.example.sporty.user.withdrawal.events.dto;

import java.util.List;

// match.withdrawal-cleaned.v1 · match.withdrawal-rejected.v1 결과 이벤트.
// 두 토픽의 필드를 한 레코드로 받는다. 모르는 필드(cleanedAt·rejectedAt 등)는 무시한다.
public record WithdrawalResultEvent(
        String eventId,
        String sagaId,
        String requestEventId,
        Long userId,
        // rejected만: OWNER_OF_IN_PROGRESS_MATCH · DEADLINE_EXCEEDED
        String reason,
        List<Long> canceledMatchIds,
        List<Long> leftMatchIds,
        List<Long> blockingMatchIds
) {
}
