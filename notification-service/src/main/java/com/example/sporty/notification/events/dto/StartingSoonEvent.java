package com.example.sporty.notification.events.dto;

import java.util.List;

// match.starting-soon.v1 — 시작까지 24시간 이내인 모임. 모임마다 한 번, eventId는 모임별 고정 값
// startAt은 UTC 오프셋 포함 ISO-8601(예: 2026-11-05T19:00:00+09:00), participantIds는 방장 포함·대기자 제외
public record StartingSoonEvent(
        String eventId,
        Long matchId,
        String title,
        String startAt,
        List<Long> participantIds
) {
}
