package com.example.sporty.match.participation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;
import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "spring.config.import=",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:participation_events;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "match.outbox.enabled=false"
        })
class ParticipationEventsTest {
    @Autowired ParticipationService service;
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean MatchRemoteChecks checks;

    @MockitoBean(name = "participationMemberLocks")
    MemberLocks locks;

    @BeforeEach
    void setup() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS outbox_event(event_id VARCHAR(64) PRIMARY"
                    + " KEY,aggregate_id VARCHAR(64),event_type VARCHAR(80),payload CLOB,created_at"
                    + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMP)");
        jdbc.update("DELETE FROM outbox_event");
        waitlist.deleteAll();
        participants.deleteAll();
        matches.deleteAll();
        when(checks.activeMember(anyLong()))
                .thenAnswer(
                        i ->
                                new MemberClient.Member(
                                        i.getArgument(0), "MALE", "ACTIVE", "SONGPA", List.of()));
    }

    MatchEntity match(int capacity) {
        var start = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        var m =
                matches.saveAndFlush(
                        new MatchEntity(
                                new MatchEntity.Details(
                                        "Event test",
                                        null,
                                        start,
                                        start.plusHours(2),
                                        capacity,
                                        SkillLevel.BEGINNER,
                                        SportType.TENNIS,
                                        GenderGroup.MIXED),
                                new MatchEntity.FacilitySnapshot(
                                        12, "Court", "Location", "Songpa", true, null)));
        participants.saveAndFlush(new MatchParticipantEntity(m, 1, MatchParticipantRole.OWNER));
        return m;
    }

    List<JsonNode> events(String topic) {
        return jdbc
                .queryForList(
                        "SELECT payload FROM outbox_event WHERE event_type=?", String.class, topic)
                .stream()
                .map(
                        json -> {
                            try {
                                return mapper.readTree(json);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .toList();
    }

    @Test
    void fullJoinStoresJoinedAndClosedContracts() {
        var m = match(2);
        service.join(m.getId(), 2L);
        var joined = events("match.joined.v1");
        var closed = events("match.status-changed.v1");
        assertThat(joined).hasSize(1);
        assertThat(closed).hasSize(1);
        var j = joined.get(0);
        assertThat(j.path("eventId").asText()).isNotBlank();
        assertThat(j.path("matchId").asLong()).isEqualTo(m.getId());
        assertThat(j.path("userId").asLong()).isEqualTo(2);
        assertThat(j.path("ownerId").asLong()).isEqualTo(1);
        assertThat(j.path("current").asInt()).isEqualTo(2);
        assertThat(j.path("max").asInt()).isEqualTo(2);
        var c = closed.get(0);
        assertThat(c.path("from").asText()).isEqualTo("RECRUITING");
        assertThat(c.path("to").asText()).isEqualTo("CLOSED");
        assertThat(c.path("ownerId").asLong()).isEqualTo(1);
        assertThat(c.path("participantIds").toString()).isEqualTo("[1,2]");
        assertThat(jdbc.queryForList("SELECT aggregate_id FROM outbox_event", String.class))
                .containsOnly(m.getId().toString());
    }

    @Test
    void ordinaryJoinAndLeaveDoNotInventStatusEvents() {
        var m = match(3);
        service.join(m.getId(), 2L);
        service.leave(m.getId(), 2L);
        assertThat(events("match.joined.v1")).hasSize(1);
        assertThat(events("match.status-changed.v1")).isEmpty();
    }

    @Test
    void leaveOnlyRecordsReopenWithoutCancellationNotification() {
        var m = match(2);
        service.join(m.getId(), 2L);
        service.leave(m.getId(), 2L);
        assertThat(events("match.joined.v1")).hasSize(1);
        assertThat(events("match.status-changed.v1"))
                .hasSize(2)
                .anySatisfy(
                        e -> {
                            assertThat(e.path("from").asText()).isEqualTo("CLOSED");
                            assertThat(e.path("to").asText()).isEqualTo("RECRUITING");
                            assertThat(e.path("participantIds").toString()).isEqualTo("[1]");
                        });
    }

    @Test
    void outerRollbackRemovesParticipationStatusAndOutboxTogether() {
        var m = match(2);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            service.join(m.getId(), 2L);
                            tx.setRollbackOnly();
                        });
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(1);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.RECRUITING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class)).isZero();
    }

    @Test
    void failedOutboxInsertRollsBackBusinessChange() {
        var m = match(2);
        jdbc.execute("DROP TABLE outbox_event");
        assertThatThrownBy(() -> service.join(m.getId(), 2L))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(1);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.RECRUITING);
    }
}
