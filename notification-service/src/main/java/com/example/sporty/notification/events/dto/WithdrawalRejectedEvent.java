package com.example.sporty.notification.events.dto;

import java.util.List;

// match.withdrawal-rejected.v1 — 진행 중인 모임 때문에 회원 탈퇴가 거절됐을 때
public record WithdrawalRejectedEvent(
        String eventId,
        String sagaId,
        Long userId,
        String reason,
        List<Long> blockingMatchIds
) {
}
