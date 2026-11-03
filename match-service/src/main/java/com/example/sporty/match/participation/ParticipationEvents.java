package com.example.sporty.match.participation;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.repository.MatchParticipantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Outbox writes join the same match_db transaction as participation and status changes. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ParticipationEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MatchParticipantRepository participants;

    public ParticipationEvents(
            JdbcTemplate jdbc, ObjectMapper mapper, MatchParticipantRepository participants) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.participants = participants;
    }

    public void joined(MatchEntity match, long userId, long current) {
        var rows = participants.findConfirmedForUpdate(match.getId());
        var fields = base(match);
        fields.put("userId", userId);
        fields.put("ownerId", owner(rows));
        fields.put("current", current);
        fields.put("max", match.getMaxParticipant());
        emit("match.joined.v1", match.getId(), fields);
    }

    public void promoted(MatchEntity match, long userId) {
        var fields = base(match);
        fields.put("userId", userId);
        emit("match.waitlist-promoted.v1", match.getId(), fields);
    }

    public void statusChanged(MatchEntity match, MatchStatus from, String reason) {
        var rows = participants.findConfirmedForUpdate(match.getId());
        var fields = base(match);
        fields.put("from", from.name());
        fields.put("to", match.getStatus().name());
        fields.put("ownerId", owner(rows));
        fields.put("participantIds", rows.stream().map(MatchParticipantEntity::getUserId).toList());
        fields.put("reason", reason);
        emit("match.status-changed.v1", match.getId(), fields);
    }

    private static final java.util.concurrent.atomic.AtomicLong LAST_ID_TIME = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Time-ordered UUID (version 7 layout: 48-bit millis, 12-bit sequence, random tail).
     * Events saved in one transaction can share created_at, and the relay then orders by
     * event_id, so a random UUID could publish "COMPLETED" before "IN_PROGRESS".
     * Strictly increasing within this JVM; ids of one match from other replicas are separated
     * by the match row lock and later commit time.
     */
    static String orderedEventId() {
        long t = LAST_ID_TIME.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis() << 12));
        long msb = ((t >>> 12) << 16) | 0x7000L | (t & 0xFFFL);
        long lsb = (java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private static Map<String, Object> base(MatchEntity match) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("matchId", match.getId());
        fields.put("title", match.getTitle());
        return fields;
    }

    private static Long owner(List<MatchParticipantEntity> rows) {
        return rows.stream()
                .filter(p -> p.getRole() == MatchParticipantRole.OWNER)
                .map(MatchParticipantEntity::getUserId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모임장 참가 기록이 없습니다."));
    }

    private void emit(String topic, Long matchId, Map<String, Object> fields) {
        String eventId = orderedEventId();
        fields.put("eventId", eventId);
        try {
            jdbc.update(
                    "INSERT INTO outbox_event(event_id,aggregate_id,event_type,payload) VALUES"
                            + " (?,?,?,?)",
                    eventId,
                    matchId.toString(),
                    topic,
                    mapper.writeValueAsString(fields));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("참가 이벤트를 저장할 수 없습니다.", ex);
        }
    }
}
