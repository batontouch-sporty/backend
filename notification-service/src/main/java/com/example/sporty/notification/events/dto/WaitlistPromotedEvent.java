package com.example.sporty.notification.events.dto;

// match.waitlist-promoted.v1 — 대기자가 빈자리로 자동 승급됐을 때
public record WaitlistPromotedEvent(
        String eventId,
        Long matchId,
        String title,
        Long userId
) {
}
