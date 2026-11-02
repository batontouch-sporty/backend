package com.example.sporty.match.crud;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.participation.ParticipationService;
import com.example.sporty.match.participation.ParticipationProblem;
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
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "spring.config.import=",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:crud10;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "match.outbox.enabled=false",
            "match.withdrawal.enabled=false"
        })
@AutoConfigureMockMvc
class MatchCrudTest {
    @Autowired MatchCrudService service;
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ParticipationService participation;
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
    void minimumCapacityTwoStartsRecruitingAndRejectsDirectServiceCall() {
        Long id = service.create(1L, request(2));
        assertThat(matches.findById(id).orElseThrow().getStatus()).isEqualTo(MatchStatus.RECRUITING);
        assertThat(participants.countByMatch_Id(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isZero();
        assertThatThrownBy(() -> service.create(1L, request(1)))
                .isInstanceOfSatisfying(
                        MatchCrudProblem.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_INPUT"));
    }

    @Test
    void invalidInputAndAuthenticationReturnContractErrors() throws Exception {
        mvc.perform(
                        post("/api/matches")
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(request(3))))
                .andExpect(status().isUnauthorized());
        var body = mapper.valueToTree(request(3));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("maxParticipant", 1);
        mvc.perform(
                        post("/api/matches")
                                .header("X-User-Id", "1")
                                .contentType("application/json")
                                .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("maxParticipant: 2 이상이어야 합니다."));
        Long matchId = service.create(1L, request(3));
        mvc.perform(
                        put("/api/matches/" + matchId)
                                .header("X-User-Id", "1")
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(update(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("maxParticipant: 2 이상이어야 합니다."));
        mvc.perform(get("/api/matches").param("sportType", "NOT_A_SPORT"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/matches").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message")
                        .value("status: 입력 형식이나 허용된 값을 확인해주세요."));
        mvc.perform(get("/api/matches").param("maxParticipant", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("maxParticipant: 1 이상이어야 합니다."));
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
    void searchLimitsRowsInTheDatabaseWhileKeepingTheArrayResponse() throws Exception {
        var snapshot =
                new MatchEntity.FacilitySnapshot(
                        12L, "Court", "Location", "송파구", true, "https://example.org/book");
        var rows = new ArrayList<MatchEntity>();
        for (int i = 0; i <= 500; i++) {
            var begins = start.plusMinutes(i);
            rows.add(
                    new MatchEntity(
                            new MatchEntity.Details(
                                    "Match " + i,
                                    "Description",
                                    begins,
                                    begins.plusHours(1),
                                    3,
                                    SkillLevel.BEGINNER,
                                    SportType.TENNIS,
                                    GenderGroup.MIXED),
                            snapshot));
        }
        matches.saveAllAndFlush(rows);

        assertThat(matches.count()).isEqualTo(501);
        mvc.perform(get("/api/matches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(500))
                .andExpect(jsonPath("$[0].title").value("Match 0"))
                .andExpect(jsonPath("$[499].title").value("Match 499"));
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
                        e -> assertThat(e.code()).isEqualTo("INVALID_INPUT"));
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
    void deleteCancelsPreservesRecordsAndNotifiesConfirmedParticipantsOnce() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        waitlist.saveAndFlush(new MatchWaitlistEntity(matches.findById(id).orElseThrow(), 3L));
        mvc.perform(delete("/api/matches/" + id).header("X-User-Id", "2"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/matches/" + id).header("X-User-Id", "1"))
                .andExpect(status().isNoContent());
        var saved = matches.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(saved.getCancelledAt()).isNotNull();
        assertThat(saved.getCancelReason()).isEqualTo("OWNER_DELETE");
        assertThat(participants.countByMatch_Id(id)).isEqualTo(2);
        assertThat(waitlist.count()).isEqualTo(1);
        var event = mapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type='match.status-changed.v1'", String.class));
        assertThat(event.path("matchId").asLong()).isEqualTo(id);
        assertThat(event.path("ownerId").asLong()).isEqualTo(1L);
        assertThat(event.path("from").asText()).isEqualTo("RECRUITING");
        assertThat(event.path("to").asText()).isEqualTo("CANCELLED");
        assertThat(event.path("participantIds").toString()).isEqualTo("[1,2]");
        assertThat(event.path("reason").asText()).isEqualTo("방장 요청으로 모임 삭제");
        mvc.perform(delete("/api/matches/" + id).header("X-User-Id", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCH_DELETE_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void deleteClosedMatchRollsBackCancellationIfOutboxFails() {
        Long id = service.create(1L, request(2));
        participant(id, 2L);
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                matches.findById(id).orElseThrow().closeRecruitment(start.minusHours(1)));
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT reject_delete_event"
                + " CHECK(event_type <> 'match.status-changed.v1')");
        try {
            assertThatThrownBy(() -> service.delete(id, 1L))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var saved = matches.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(MatchStatus.CLOSED);
            assertThat(saved.getCancelledAt()).isNull();
            assertThat(participants.countByMatch_Id(id)).isEqualTo(2);
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT reject_delete_event");
        }
        service.delete(id, 1L);
        assertThat(matches.findById(id).orElseThrow().getStatus()).isEqualTo(MatchStatus.CANCELLED);
    }

    @Test
    void ownerCancelPreservesPeopleAndWritesStatusEventOnce() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        waitlist.saveAndFlush(new MatchWaitlistEntity(matches.findById(id).orElseThrow(), 3L));
        clearInvocations(locks, checks);
        mvc.perform(patch("/api/matches/" + id + "/cancel").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchId").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        var match = matches.findById(id).orElseThrow();
        assertThat(match.getCancelledAt()).isNotNull();
        assertThat(match.getStatusChangedAt()).isEqualTo(match.getCancelledAt());
        assertThat(match.getCancelReason()).isEqualTo("OWNER_CANCEL");
        assertThat(participants.countByMatch_Id(id)).isEqualTo(2);
        assertThat(waitlist.count()).isEqualTo(1);
        var payload = mapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type='match.status-changed.v1'",
                String.class));
        assertThat(payload.path("from").asText()).isEqualTo("RECRUITING");
        assertThat(payload.path("to").asText()).isEqualTo("CANCELLED");
        assertThat(payload.path("participantIds").size()).isEqualTo(2);
        assertThat(payload.path("reason").asText()).isEqualTo("방장 요청으로 모임 취소");
        verify(locks).lock(1L);
        verifyNoInteractions(checks);
        mvc.perform(patch("/api/matches/" + id + "/cancel").header("X-User-Id", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCH_CANCEL_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void ownerCancelAllowsClosedAndRejectsNonOwnerMissingIdentityAndMissingMatch() throws Exception {
        Long id = service.create(1L, request(2));
        participant(id, 2L);
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                matches.findById(id).orElseThrow().closeRecruitment(start.minusHours(1)));
        mvc.perform(patch("/api/matches/" + id + "/cancel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/matches/" + id + "/cancel").header("X-User-Id", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MATCH_CANCEL_FORBIDDEN"));
        mvc.perform(patch("/api/matches/999999/cancel").header("X-User-Id", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/matches/" + id + "/cancel").header("X-User-Id", "1"))
                .andExpect(status().isOk());
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT payload FROM outbox_event", String.class))
                .path("from").asText()).isEqualTo("CLOSED");
    }

    @Test
    void ownerCancelRejectsRunningCompletedAndPastStartEvenBeforeSchedulerRuns() {
        for (var status : List.of(MatchStatus.IN_PROGRESS, MatchStatus.COMPLETED)) {
            Long id = service.create(1L, request(3));
            new TransactionTemplate(manager).executeWithoutResult(tx ->
                    matches.findById(id).orElseThrow().changeStatus(status, start));
            assertThatThrownBy(() -> service.cancel(id, 1L)).isInstanceOfSatisfying(
                    MatchCrudProblem.class, e -> assertThat(e.code()).isEqualTo("MATCH_CANCEL_NOT_ALLOWED"));
            assertThat(matches.findById(id).orElseThrow().getCancelledAt()).isNull();
        }
        Long id = service.create(1L, request(3));
        jdbc.update("UPDATE matches SET start_at=? WHERE id=?",
                LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1), id);
        assertThatThrownBy(() -> service.cancel(id, 1L)).isInstanceOfSatisfying(
                MatchCrudProblem.class, e -> assertThat(e.code()).isEqualTo("MATCH_CANCEL_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test
    void ownerCancelRollsBackWhenOutboxCannotBeWritten() {
        Long id = service.create(1L, request(3));
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT reject_cancel_event"
                + " CHECK(event_type <> 'match.status-changed.v1')");
        try {
            assertThatThrownBy(() -> service.cancel(id, 1L))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var match = matches.findById(id).orElseThrow();
            assertThat(match.getStatus()).isEqualTo(MatchStatus.RECRUITING);
            assertThat(match.getCancelledAt()).isNull();
            assertThat(match.getCancelReason()).isNull();
            assertThat(participants.countByMatch_Id(id)).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT reject_cancel_event");
        }
    }

    @Test
    void cancelledMatchRejectsParticipationChangesAndKeepsRecords() throws Exception {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        waitlist.saveAndFlush(new MatchWaitlistEntity(matches.findById(id).orElseThrow(), 3L));
        when(checks.activeMember(anyLong())).thenAnswer(i ->
                new MemberClient.Member(i.getArgument(0), "MALE", "ACTIVE", "SONGPA", List.of()));
        service.cancel(id, 1L);
        mvc.perform(post("/api/matches/" + id + "/participants").header("X-User-Id", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCH_RECRUITMENT_CLOSED"));
        mvc.perform(delete("/api/matches/" + id + "/participants/me").header("X-User-Id", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCH_LEAVE_NOT_ALLOWED"));
        mvc.perform(post("/api/matches/" + id + "/waitlist").header("X-User-Id", "4"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/matches/" + id + "/waitlist/me").header("X-User-Id", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WAITLIST_CANCEL_NOT_ALLOWED"));
        assertThat(participants.countByMatch_Id(id)).isEqualTo(2);
        assertThat(waitlist.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentJoinWaitsForCancellationThenRejectsIt() throws Exception {
        Long id = service.create(1L, request(3));
        var cancelled = new CountDownLatch(1);
        var joining = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(checks.activeMember(2L)).thenAnswer(i -> {
            joining.countDown();
            return new MemberClient.Member(2L, "MALE", "ACTIVE", "SONGPA", List.of());
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                service.cancel(id, 1L);
                cancelled.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Join did not start");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return null;
            }));
            assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue();
            var join = pool.submit(() -> participation.join(id, 2L));
            assertThat(joining.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            cancel.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> join.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ParticipationProblem.class)
                    .satisfies(ex -> assertThat(((ParticipationProblem) ex.getCause()).code())
                            .isEqualTo("MATCH_RECRUITMENT_CLOSED"));
            assertThat(participants.countByMatch_Id(id)).isEqualTo(1);
            assertThat(matches.findById(id).orElseThrow().getStatus()).isEqualTo(MatchStatus.CANCELLED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
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
    void outboxFailureRollsBackCapacityChange() {
        Long id = service.create(1L, request(3));
        participant(id, 2L);
        jdbc.execute(
                "ALTER TABLE outbox_event ADD CONSTRAINT reject_crud_event CHECK(event_type <>"
                    + " 'match.status-changed.v1')");
        try {
            assertThatThrownBy(() -> service.update(id, 1L, update(2)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(matches.findById(id).orElseThrow().getMaxParticipant()).isEqualTo(3);
            assertThat(matches.findById(id).orElseThrow().getStatus())
                    .isEqualTo(MatchStatus.RECRUITING);
            assertThat(participants.count()).isEqualTo(2);
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT reject_crud_event");
        }
    }
}
