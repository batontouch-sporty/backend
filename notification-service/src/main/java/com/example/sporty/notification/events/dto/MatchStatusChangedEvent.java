package com.example.sporty.notification.events.dto;

import java.util.List;

// match.status-changed.v1 — 매치 상태가 바뀌었을 때. from·to는 RECRUITING·CLOSED·IN_PROGRESS·COMPLETED·CANCELLED
public record MatchStatusChangedEvent(
        String eventId,
        Long matchId,
        String title,
        String from,
        String to,
        List<Long> participantIds,
        String reason
) {
}
