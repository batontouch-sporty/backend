package com.example.sporty.notification.events.dto;

import java.util.List;

// match.withdrawal-rejected.v1 — 회원 탈퇴가 거절됐을 때.
// reason = OWNER_OF_IN_PROGRESS_MATCH(진행 중인 모임의 방장) 또는 DEADLINE_EXCEEDED(처리 마감 초과)
public record WithdrawalRejectedEvent(
        String eventId,
        String sagaId,
        String requestEventId,
        Long userId,
        String reason,
        List<Long> blockingMatchIds
) {
}
