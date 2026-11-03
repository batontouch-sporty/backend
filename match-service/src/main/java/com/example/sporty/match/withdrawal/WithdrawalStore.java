package com.example.sporty.match.withdrawal;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.*;

/** R7 match_db only. Does not introduce entities or touch another service's tables. */
public class WithdrawalStore {
    final JdbcTemplate jdbc;
    final ObjectMapper mapper;
    private com.example.sporty.match.deposit.DepositSaga deposits;
    public void setDeposits(com.example.sporty.match.deposit.DepositSaga value) { deposits=value; }

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

    /** Result row identity returned by GET /internal/withdrawals/{sagaId}. */
    public record SagaResult(String sagaId, String requestEventId, Long userId, String result) {}

    /**
     * This Saga's committed result row (CLEANED or REJECTED), or null. Same indexed lookup as
     * {@link #completed}; cleanup and its result row commit together.
     */
    public SagaResult result(String sagaId, long userId) {
        return jdbc
                .query(
                        "SELECT event_type,payload FROM outbox_event WHERE aggregate_id=? AND event_type IN"
                                + " ('match.withdrawal-cleaned.v1','match.withdrawal-rejected.v1')",
                        (r, n) -> {
                            try {
                                var json = mapper.readTree(r.getString(2));
                                if (!sagaId.equals(json.path("sagaId").asText())) return null;
                                return new SagaResult(
                                        sagaId,
                                        json.path("requestEventId").asText(),
                                        json.path("userId").asLong(),
                                        "match.withdrawal-cleaned.v1".equals(r.getString(1))
                                                ? "CLEANED"
                                                : "REJECTED");
                            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                                throw new IllegalStateException(ex);
                            }
                        },
                        Long.toString(userId))
                .stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparing(SagaResult::result)) // CLEANED wins if both ever exist
                .orElse(null);
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
        // A13 locks a match before deleting its participant rows. Lock the same match rows
        // first, in ID order, so a withdrawal never holds a participant row while waiting
        // for the match row owned by a concurrent hard delete.
        var matchIds =
                jdbc.queryForList(
                        "SELECT match_id FROM match_participants WHERE user_id=? AND participation_status IN ('PENDING','CONFIRMED') ORDER BY match_id",
                        Long.class,
                        userId);
        for (var matchId : matchIds) {
            jdbc.queryForList(
                    "SELECT id FROM matches WHERE id=? FOR UPDATE", Long.class, matchId);
        }
        // Re-read with a locking query: a delete can commit while the row lock is awaited.
        // A consistent read could otherwise return that deleted membership under REPEATABLE READ.
        return jdbc.query(
                "SELECT m.id,m.title,m.status,m.start_at,m.end_at,m.max_participant,p.role FROM"
                    + " matches m JOIN match_participants p ON p.match_id=m.id WHERE p.user_id=? AND p.participation_status IN ('PENDING','CONFIRMED')"
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
                "SELECT user_id FROM match_participants WHERE match_id=? AND participation_status='CONFIRMED' ORDER BY"
                        + " match_participant_id FOR UPDATE",
                Long.class,
                matchId);
    }

    public long owner(long matchId) {
        return jdbc.queryForObject(
                "SELECT user_id FROM match_participants WHERE match_id=? AND role='OWNER' AND participation_status='CONFIRMED' FOR UPDATE",
                Long.class,
                matchId);
    }

    public void cancel(long id, Instant now) {
        if(deposits!=null && deposits.isEnabled()) {
            var owner=jdbc.queryForObject("SELECT user_id FROM match_participants WHERE match_id=? AND role='OWNER'",Long.class,id);
            deposits.withdrawal(id,owner,true);
        }
        jdbc.update(
                "UPDATE matches SET"
                    + " status='CANCELLED',cancel_reason='USER_WITHDRAWAL',cancelled_at=?,status_changed_at=?"
                    + " WHERE id=?",
                LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul")),
                LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul")),
                id);
    }

    public void leave(long id, long userId) {
        if(deposits!=null && deposits.isEnabled()) { deposits.withdrawal(id,userId,false); return; }
        jdbc.update(
                "DELETE FROM match_participants WHERE match_id=? AND user_id=? AND"
                        + " role='PARTICIPANT'",
                id,
                userId);
    }

    public void clearWaitlist(long userId) {
        jdbc.update("DELETE FROM match_waitlist WHERE user_id=?", userId);
    }

    /** Reopen a pre-start closed match only when cleanup has actually freed capacity. */
    public boolean reopen(Match match, Instant now) {
        if (!"CLOSED".equals(match.status())) return false;
        if(deposits!=null && deposits.isEnabled() && deposits.hasWaitlistPriority(match.id())) return false;
        long count = jdbc.queryForList(
                "SELECT match_participant_id FROM match_participants WHERE match_id=? AND participation_status='CONFIRMED' FOR UPDATE",
                Long.class,match.id()).size();
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
