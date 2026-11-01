package com.example.sporty.notification.events.dto;

import java.util.List;

// match.status-changed.v1 — 매치 상태가 바뀌었을 때. from·to는 RECRUITING·CLOSED·IN_PROGRESS·COMPLETED·CANCELLED
// ownerId는 마감(CLOSED) 알림을 방장에게 보내는 데 쓴다. 탈퇴 Saga로 바뀐 경우 sagaId가 붙지만 알림에는 쓰지 않는다.
public record MatchStatusChangedEvent(
        String eventId,
        Long matchId,
        String title,
        String from,
        String to,
        Long ownerId,
        List<Long> participantIds,
        String reason
) {
}
