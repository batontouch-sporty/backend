package com.example.sporty.match.crud;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "spring.config.import=",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:crud10draft;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "match.outbox.enabled=false",
            "match.withdrawal.enabled=false"
        })
@AutoConfigureMockMvc
class MatchCrudDraftTest {
    @Autowired MatchCrudService service;
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean MatchRemoteChecks checks;

    @MockitoBean(name = "participationMemberLocks")
    MemberLocks locks;

    LocalDateTime start;

    @BeforeEach
    void prepare() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS outbox_event(event_id VARCHAR(64) PRIMARY"
                    + " KEY,aggregate_id VARCHAR(64),event_type VARCHAR(80),payload CLOB,created_at"
                    + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMP)");
        jdbc.update("DELETE FROM outbox_event");
        waitlist.deleteAll();
        participants.deleteAll();
        matches.deleteAll();
        start = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(2);
        var snapshot =
                new MatchRemoteChecks.FacilitySnapshot(
                        12L, "Court", "Location", "송파구", true, "https://example.org/book");
        when(checks.checkCreation(anyLong(), anyLong(), any(), any()))
                .thenReturn(
                        new MatchRemoteChecks.CreationContext(
                                new MemberClient.Member(1L, "MALE", "ACTIVE", "SONGPA", List.of()),
                                snapshot));
        when(checks.checkFacility(anyLong(), any(), any())).thenReturn(snapshot);
        when(checks.profiles(anyList()))
                .thenAnswer(
                        i -> {
                            Map<Long, MatchRemoteChecks.ProfileView> p = new HashMap<>();
                            for (Long id : (List<Long>) i.getArgument(0))
                                p.put(
                                        id,
                                        new MatchRemoteChecks.ProfileView(
                                                id, id + 100, "User " + id, null));
                            return p;
                        });
    }

    MatchDtos.Create request(int capacity) {
        return new MatchDtos.Create(
                12L,
                "Draft match",
                "Description",
                start,
                start.plusHours(1),
                capacity,
                SkillLevel.BEGINNER,
                SportType.TENNIS,
                GenderGroup.MIXED);
    }

    MatchDtos.Update update(int capacity) {
        return new MatchDtos.Update(
                "Changed",
                "New description",
                start.plusHours(1),
                start.plusHours(2),
                capacity,
                SkillLevel.INTERMEDIATE,
                GenderGroup.MIXED);
    }

    void participant(Long id, Long user) {
        participants.saveAndFlush(
                new MatchParticipantEntity(
                        matches.findById(id).orElseThrow(),
                        user,
                        MatchParticipantRole.PARTICIPANT));
    }

    @Test
    void createUsesSharedMemberLockAndCopiesSnapshotWithOwner() throws Exception {
        var response =
                mvc.perform(
                                post("/api/matches")
                                        .header("X-User-Id", "1")
                                        .contentType("application/json")
                                        .content(mapper.writeValueAsString(request(3))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long id = Long.parseLong(response);
        var m = matches.findById(id).orElseThrow();
        assertThat(m.getUrl()).isEqualTo("https://example.org/book");
        assertThat(m.getFacilityId()).isEqualTo(12L);
        assertThat(participants.findByMatch_IdAndUserId(id, 1L).orElseThrow().getRole())
                .isEqualTo(MatchParticipantRole.OWNER);
        var ordered = inOrder(locks, checks);
        ordered.verify(locks).lock(1L);
        ordered.verify(checks).checkCreation(eq(1L), eq(12L), any(), any());
    }

    @Test
    void ownerCountsTowardCapacityOneAndCloseEvent() throws Exception {
        Long id = service.create(1L, request(1));
        assertThat(matches.findById(id).orElseThrow().getStatus()).isEqualTo(MatchStatus.CLOSED);
        var event =
                mapper.readTree(
                        jdbc.queryForObject("SELECT payload FROM outbox_event", String.class));
        assertThat(event.path("ownerId").asLong()).isEqualTo(1L);
        assertThat(event.path("to").asText()).isEqualTo("CLOSED");
        assertThat(event.path("participantIds")).hasSize(1);
    }

    @Test
    void invalidInputAndAuthenticationReturnContractErrors() throws Exception {
        mvc.perform(
                        post("/api/matches")
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(request(3))))
                .andExpect(status().isUnauthorized());
        var body = mapper.valueToTree(request(3));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("maxParticipant", 0);
        mvc.perform(
                        post("/api/matches")
                                .header("X-User-Id", "1")
                                .contentType("application/json")
                                .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/matches").param("sportType", "NOT_A_SPORT"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/matches").param("maxParticipant", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/matches")
                                .param("startAt", start.plusDays(1).toString())
                                .param("endAt", start.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/matches/123")).andExpect(status().isNotFound());
    }

    @Test
    void searchSupportsFiltersDefaultStatusesLiteralKeywordsAndBatchedCounts() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        Long hidden = service.create(3L, request(3));
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            var m = matches.findById(hidden).orElseThrow();
                            m.cancel("Cancelled", start.minusDays(1));
                        });
        mvc.perform(
                        get("/api/matches")
                                .param("serviceId", "12")
                                .param("sportType", "TENNIS")
                                .param("region", "송파구")
                                .param("isFree", "true")
                                .param("startAt", start.minusHours(1).toString())
                                .param("endAt", start.plusHours(2).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].matchId").value(id))
                .andExpect(jsonPath("$[0].numCurrentParticipant").value(2))
                .andExpect(jsonPath("$[0].distance").isEmpty());
        mvc.perform(get("/api/matches").param("status", "CANCELLED"))
                .andExpect(jsonPath("$[0].matchId").value(hidden));
        mvc.perform(get("/api/matches").param("titleKeyword", "%"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/matches").param("titleKeyword", "draft"))
                .andExpect(jsonPath("$[0].matchId").value(id));
        mvc.perform(get("/api/matches").param("descriptionKeyword", "DESCRIPTION"))
                .andExpect(jsonPath("$[0].matchId").value(id));
        mvc.perform(get("/api/matches").param("region", "송파"))
                .andExpect(jsonPath("$[0].matchId").value(id));
        verify(checks, never()).profiles(anyList());
    }

    @Test
    void detailMapsProfilesFlagsAndSnapshotWithoutFacilityLookup() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        clearInvocations(checks);
        mvc.perform(get("/api/matches/" + id).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOwner").value(true))
                .andExpect(jsonPath("$.isParticipant").value(true))
                .andExpect(jsonPath("$.participants[0].profileId").value(101))
                .andExpect(jsonPath("$.serviceName").value("Court"));
        mvc.perform(get("/api/matches/" + id))
                .andExpect(jsonPath("$.isOwner").value(false))
                .andExpect(jsonPath("$.isParticipant").value(false));
        when(checks.profiles(anyList())).thenReturn(Map.of());
        mvc.perform(get("/api/matches/" + id))
                .andExpect(jsonPath("$.participants[0].nickname").value("알 수 없음"));
        verify(checks, never()).checkFacility(anyLong(), any(), any());
    }

    @Test
    void updateRecalculatesStatusCopiesFacilityAndReturnsDetail() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        mvc.perform(
                        put("/api/matches/" + id)
                                .header("X-User-Id", "1")
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(update(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.serviceId").value(12))
                .andExpect(jsonPath("$.sportType").value("TENNIS"));
        service.update(id, 1L, update(4));
        assertThat(matches.findById(id).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.RECRUITING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void updateCallsFacilityAndProfileOutsideTheDatabaseTransaction() {
        Long id = service.create(1L, request(3));
        when(checks.checkFacility(anyLong(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                    .isFalse();
                            return new MatchRemoteChecks.FacilitySnapshot(
                                    12L, "Court", "Location", "송파구", true, "https://example.org/book");
                        });
        when(checks.profiles(anyList()))
                .thenAnswer(
                        invocation -> {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                    .isFalse();
                            return Map.of();
                        });

        service.update(id, 1L, update(3));
        verify(checks).checkFacility(eq(12L), any(), any());
        verify(checks).profiles(anyList());
    }

    @Test
    void updateRejectsNonOwnerAndCapacityBelowCurrentAndRollsBackDependencyFailure() {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        assertThatThrownBy(() -> service.update(id, 2L, update(3)))
                .isInstanceOfSatisfying(
                        MatchCrudProblem.class,
                        e -> assertThat(e.code()).isEqualTo("MATCH_MODIFY_FORBIDDEN"));
        assertThatThrownBy(() -> service.update(id, 1L, update(1)))
                .isInstanceOfSatisfying(
                        MatchCrudProblem.class,
                        e -> assertThat(e.code()).isEqualTo("MATCH_CAPACITY_BELOW_CURRENT"));
        when(checks.checkFacility(anyLong(), any(), any()))
                .thenThrow(
                        new MatchDependencyException(
                                503, "FACILITY_SERVICE_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(() -> service.update(id, 1L, update(4)))
                .isInstanceOf(MatchDependencyException.class);
        assertThat(matches.findById(id).orElseThrow().getTitle()).isEqualTo("Draft match");
    }

    @Test
    void cancelledAndStartedMatchesCannotBeModifiedOrDeleted() {
        Long id = service.create(1L, request(3));
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            var m = matches.findById(id).orElseThrow();
                            m.cancel("reason", start.minusDays(1));
                        });
        assertThatThrownBy(() -> service.update(id, 1L, update(3)))
                .isInstanceOfSatisfying(
                        MatchCrudProblem.class,
                        e -> assertThat(e.code()).isEqualTo("MATCH_MODIFY_NOT_ALLOWED"));
        assertThatThrownBy(() -> service.delete(id, 1L)).isInstanceOf(MatchCrudProblem.class);
        Long running = service.create(2L, request(3));
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx ->
                                matches.findById(running)
                                        .orElseThrow()
                                        .changeStatus(MatchStatus.IN_PROGRESS, start));
        assertThatThrownBy(() -> service.update(running, 2L, update(3)))
                .isInstanceOfSatisfying(
                        MatchCrudProblem.class,
                        e -> assertThat(e.code()).isEqualTo("MATCH_ALREADY_STARTED"));
        assertThatThrownBy(() -> service.delete(running, 2L)).isInstanceOf(MatchCrudProblem.class);
    }

    @Test
    void hardDeleteDeletesParticipantsThenCascadesWaitlistWithoutEvent() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        waitlist.saveAndFlush(new MatchWaitlistEntity(matches.findById(id).orElseThrow(), 3L));
        mvc.perform(delete("/api/matches/" + id).header("X-User-Id", "2"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/matches/" + id).header("X-User-Id", "1"))
                .andExpect(status().isNoContent());
        assertThat(matches.existsById(id)).isFalse();
        assertThat(participants.count()).isZero();
        assertThat(waitlist.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isZero();
    }

    @Test
    void mineUsesIdentityOnlyIncludesHistoryAndKeepsDescendingOrder() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        clearInvocations(checks);
        mvc.perform(get("/api/matches/me").header("X-User-Id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("PARTICIPANT"))
                .andExpect(jsonPath("$[0].facilityName").value("Court"));
        mvc.perform(get("/api/matches/me").header("X-User-Id", "999"))
                .andExpect(jsonPath("$.length()").value(0));
        verifyNoInteractions(checks);
    }

    @Test
    void outboxFailureRollsBackOwnerAndCreatedMatch() {
        jdbc.execute(
                "ALTER TABLE outbox_event ADD CONSTRAINT reject_crud_event CHECK(event_type <>"
                    + " 'match.status-changed.v1')");
        try {
            assertThatThrownBy(() -> service.create(1L, request(1)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(matches.count()).isZero();
            assertThat(participants.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT reject_crud_event");
        }
    }
}
