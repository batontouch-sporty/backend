package com.example.sporty.match.lifecycle;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.participation.ParticipationEvents;
import com.example.sporty.match.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Time-based RECRUITING/CLOSED -> IN_PROGRESS -> COMPLETED transitions under the match row lock. */
@Component
public class MatchLifecycle {
    public static final String SETTLEMENT = "match.attendance-settlement-requested.v1";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final ParticipationEvents events;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean depositEnabled;

    public MatchLifecycle(
            MatchRepository matches,
            MatchParticipantRepository participants,
            ParticipationEvents events,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${match.deposit.enabled:true}") boolean depositEnabled) {
        this.matches = matches;
        this.participants = participants;
        this.events = events;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.depositEnabled = depositEnabled;
    }

    /** Id cursor page: the scheduler walks every page, so failing matches cannot starve later ones. */
    public List<Long> candidates(long afterId) {
        var now = now();
        return jdbc.queryForList(
                "SELECT id FROM matches WHERE id>? AND ((status IN ('RECRUITING','CLOSED') AND"
                        + " start_at<=?) OR (status='IN_PROGRESS' AND end_at<=?)) ORDER BY id LIMIT 100",
                Long.class,
                afterId,
                now,
                now);
    }

    /** Re-checks status and time after locking, so concurrent or repeated runs transition once. */
    @Transactional
    public void advance(long matchId) {
        var match = matches.findByIdForUpdate(matchId).orElse(null);
        if (match == null) return;
        var now = now();
        var from = match.getStatus();
        if ((from == MatchStatus.RECRUITING || from == MatchStatus.CLOSED)
                && !match.getStartAt().isAfter(now)) {
            match.changeStatus(MatchStatus.IN_PROGRESS, now);
            events.statusChanged(match, from, "시작 시각 도달로 모임 시작");
        }
        if (match.getStatus() == MatchStatus.IN_PROGRESS && !match.getEndAt().isAfter(now)) {
            match.changeStatus(MatchStatus.COMPLETED, now);
            events.statusChanged(match, MatchStatus.IN_PROGRESS, "종료 시각 도달로 모임 완료");
            if (depositEnabled) settle(match);
        }
        matches.flush();
    }

    /** One request per confirmed participant; the name-based eventId blocks a second insert. */
    private void settle(MatchEntity match) {
        var rows = participants.findConfirmedForUpdate(match.getId());
        boolean underMin = rows.size() < 2;
        for (var p : rows) {
            String eventId =
                    UUID.nameUUIDFromBytes(
                                    (SETTLEMENT + ":" + p.getRequestId())
                                            .getBytes(StandardCharsets.UTF_8))
                            .toString();
            if (!jdbc.queryForList(
                            "SELECT event_id FROM outbox_event WHERE event_id=? FOR UPDATE",
                            String.class,
                            eventId)
                    .isEmpty()) continue;
            var fields = new LinkedHashMap<String, Object>();
            fields.put("eventId", eventId);
            fields.put("requestId", p.getRequestId());
            fields.put("userId", p.getUserId());
            fields.put("matchId", match.getId());
            fields.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
            fields.put("outcome", underMin ? "UNDER_MIN_PARTICIPANTS" : p.getMatchParticipantStatus());
            try {
                jdbc.update(
                        "INSERT INTO outbox_event(event_id,aggregate_id,event_type,payload) VALUES"
                                + " (?,?,?,?)",
                        eventId,
                        Long.toString(p.getUserId()),
                        SETTLEMENT,
                        mapper.writeValueAsString(fields));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("출석 정산 이벤트를 저장할 수 없습니다.", ex);
            }
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(SEOUL);
    }
}
