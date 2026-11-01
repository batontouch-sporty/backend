package com.example.sporty.match.reminder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** R8-owned tables only. Retained Outbox rows are the durable once-per-match issuance history. */
@Component
public class StartingSoonStore {
    public static final String TOPIC = "match.starting-soon.v1";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public StartingSoonStore(
            JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this(jdbc, mapper, manager, Clock.systemUTC());
    }

    StartingSoonStore(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public record Event(
            String eventId,
            long matchId,
            String title,
            String startAt,
            List<Long> participantIds) {}

    private record Match(long id, String title, LocalDateTime startAt, String status) {}

    public List<Long> candidates() {
        var now = now();
        return jdbc.queryForList(
                "SELECT m.id FROM matches m WHERE m.status IN ('RECRUITING','CLOSED') AND"
                    + " m.start_at>? AND m.start_at<=? AND NOT EXISTS (SELECT 1 FROM outbox_event o"
                    + " WHERE o.aggregate_id=CONCAT('',m.id) AND o.event_type=?) ORDER BY"
                    + " m.start_at,m.id LIMIT 100",
                Long.class,
                now,
                now.plusHours(24),
                TOPIC);
    }

    public boolean enqueue(long matchId) {
        return Boolean.TRUE.equals(
                tx.execute(
                        status -> {
                            var matches =
                                    jdbc.query(
                                            "SELECT id,title,start_at,status FROM matches WHERE"
                                                    + " id=? FOR UPDATE",
                                            (rs, n) ->
                                                    new Match(
                                                            rs.getLong("id"),
                                                            rs.getString("title"),
                                                            rs.getTimestamp("start_at")
                                                                    .toLocalDateTime(),
                                                            rs.getString("status")),
                                            matchId);
                            if (matches.isEmpty()) return false;
                            var match = matches.get(0);
                            var now = now();
                            if (!Set.of("RECRUITING", "CLOSED").contains(match.status())
                                    || !match.startAt().isAfter(now)
                                    || match.startAt().isAfter(now.plusHours(24))) return false;
                            String eventId = eventId(matchId);
                            // A locking read sees a previous replica's commit even under MariaDB
                            // REPEATABLE READ.
                            if (!jdbc.queryForList(
                                            "SELECT event_id FROM outbox_event WHERE event_id=? FOR"
                                                    + " UPDATE",
                                            String.class,
                                            eventId)
                                    .isEmpty()) return false;
                            var recipients =
                                    jdbc.queryForList(
                                            "SELECT user_id FROM match_participants WHERE"
                                                + " match_id=? AND role IN ('OWNER','PARTICIPANT')"
                                                + " ORDER BY match_participant_id",
                                            Long.class,
                                            matchId);
                            if (recipients.isEmpty()) return false;
                            var event =
                                    new Event(
                                            eventId,
                                            matchId,
                                            match.title(),
                                            match.startAt()
                                                    .atZone(SEOUL)
                                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                                            List.copyOf(recipients));
                            try {
                                jdbc.update(
                                        "INSERT INTO"
                                            + " outbox_event(event_id,aggregate_id,event_type,payload)"
                                            + " VALUES (?,?,?,?)",
                                        eventId,
                                        Long.toString(matchId),
                                        TOPIC,
                                        mapper.writeValueAsString(event));
                            } catch (JsonProcessingException ex) {
                                throw new IllegalStateException("시작 전 알림 이벤트를 저장할 수 없습니다.", ex);
                            }
                            return true;
                        }));
    }

    public static String eventId(long matchId) {
        return UUID.nameUUIDFromBytes((TOPIC + ":" + matchId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), SEOUL);
    }
}
