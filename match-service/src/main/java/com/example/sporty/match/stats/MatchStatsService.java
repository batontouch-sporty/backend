package com.example.sporty.match.stats;

import com.example.sporty.match.domain.MatchStatus;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** A29 counts persisted match and participant records without a date filter. */
@Service
public class MatchStatsService {
    private final JdbcTemplate jdbc;

    public MatchStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MatchCounts(long total, Map<MatchStatus, Long> byStatus) {}
    public record ParticipantCounts(long total) {}
    public record Stats(MatchCounts matches, ParticipantCounts participants) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Stats getStats() {
        var byStatus = new EnumMap<MatchStatus, Long>(MatchStatus.class);
        for (var status : MatchStatus.values()) byStatus.put(status, 0L);
        jdbc.query("SELECT status, COUNT(*) AS total FROM matches GROUP BY status", rs -> {
            while (rs.next()) {
                byStatus.put(MatchStatus.valueOf(rs.getString("status")), rs.getLong("total"));
            }
            return null;
        });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long participantTotal = jdbc.queryForObject("SELECT COUNT(*) FROM match_participants", Long.class);
        return new Stats(new MatchCounts(total, Collections.unmodifiableMap(byStatus)),
                new ParticipantCounts(participantTotal));
    }
}
