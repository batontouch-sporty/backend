package com.example.sporty.notification.events.dto;

// match.joined.v1 — 회원이 매치에 참가했을 때
public record MatchJoinedEvent(
        String eventId,
        Long matchId,
        String title,
        Long userId,
        Long ownerId,
        Integer current,
        Integer max
) {
}
