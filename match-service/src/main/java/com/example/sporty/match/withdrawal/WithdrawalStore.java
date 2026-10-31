package com.example.sporty.match.withdrawal;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.*;

/** R7 match_db only. Does not introduce entities or touch another service's tables. */
public class WithdrawalStore {
    final JdbcTemplate jdbc;
    final ObjectMapper mapper;

    public WithdrawalStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean processed(String id) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_events WHERE event_id=?", Long.class, id)
                > 0;
    }

    public void mark(String id) {
        jdbc.update(
                "INSERT INTO processed_events(event_id,event_type) VALUES (?,?)",
                id,
                "user.withdrawal-requested.v1");
    }

    /**
     * Search this user's indexed outbox history and compare the original Saga in JSON. Published
     * rows remain deduplication evidence; retention must preserve that contract. DBA applies
     * docs/sql/003_match_outbox_aggregate_index.sql before activation.
     */
    public boolean completed(WithdrawalRequest request) {
        return jdbc
                .queryForList(
                        "SELECT payload FROM outbox_event WHERE aggregate_id=? AND event_type IN"
                                + " ('match.withdrawal-cleaned.v1','match.withdrawal-rejected.v1')",
                        String.class,
                        request.userId().toString())
                .stream()
                .anyMatch(
                        json -> {
                            try {
                                return request.sagaId()
                                        .equals(mapper.readTree(json).path("sagaId").asText());
                            } catch (Exception ex) {
                                throw new IllegalStateException(ex);
                            }
                        });
    }

    public record Match(
            long id,
            String title,
            String status,
            LocalDateTime start,
            LocalDateTime end,
            int capacity,
            String role) {}

    public List<Match> lockMatches(long userId) {
        return jdbc.query(
                "SELECT m.id,m.title,m.status,m.start_at,m.end_at,m.max_participant,p.role FROM"
                    + " matches m JOIN match_participants p ON p.match_id=m.id WHERE p.user_id=?"
                    + " ORDER BY m.id FOR UPDATE",
                (r, n) ->
                        new Match(
                                r.getLong(1),
                                r.getString(2),
                                r.getString(3),
                                r.getTimestamp(4).toLocalDateTime(),
                                r.getTimestamp(5).toLocalDateTime(),
                                r.getInt(6),
                                r.getString(7)),
                userId);
    }

    public List<Long> recipients(long matchId) {
        return jdbc.queryForList(
                "SELECT user_id FROM match_participants WHERE match_id=? ORDER BY"
                    + " match_participant_id",
                Long.class,
                matchId);
    }

    public long owner(long matchId) {
        return jdbc.queryForObject(
                "SELECT user_id FROM match_participants WHERE match_id=? AND role='OWNER'",
                Long.class,
                matchId);
    }

    public void cancel(long id, Instant now) {
        jdbc.update(
                "UPDATE matches SET"
                    + " status='CANCELLED',cancel_reason='USER_WITHDRAWAL',cancelled_at=?,status_changed_at=?"
                    + " WHERE id=?",
                LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul")),
                LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul")),
                id);
    }

    public void leave(long id, long userId) {
        jdbc.update(
                "DELETE FROM match_participants WHERE match_id=? AND user_id=? AND"
                    + " role='PARTICIPANT'",
                id,
                userId);
    }

    /** Reopen a pre-start closed match only when cleanup has actually freed capacity. */
    public boolean reopen(Match match, Instant now) {
        if (!"CLOSED".equals(match.status())) return false;
        long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM match_participants WHERE match_id=?",
                        Long.class,
                        match.id());
        if (count >= match.capacity()) return false;
        jdbc.update(
                "UPDATE matches SET status='RECRUITING',status_changed_at=? WHERE id=?",
                LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul")),
                match.id());
        return true;
    }

    /** Persist an event identity and JSON payload within the caller's cleanup transaction. */
    public void emit(String topic, long key, Map<String, Object> fields, Instant now) {
        var payload = new LinkedHashMap<String, Object>(fields);
        String eventId = UUID.randomUUID().toString();
        payload.put("eventId", eventId);
        try {
            jdbc.update(
                    "INSERT INTO outbox_event(event_id,aggregate_id,event_type,payload) VALUES"
                        + " (?,?,?,?)",
                    eventId,
                    Long.toString(key),
                    topic,
                    mapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
