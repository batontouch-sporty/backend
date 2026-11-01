package com.example.sporty.match.waitlist;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.participation.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "spring.config.import=",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:waitlist35;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "match.outbox.enabled=false",
            "match.withdrawal.enabled=true",
            "spring.kafka.listener.auto-startup=false"
        })
@AutoConfigureMockMvc
class WaitlistTest {
    @Autowired WaitlistService service;
    @Autowired ParticipationService participation;
    @Autowired WithdrawalProcessor saga;
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

    @MockitoBean(name = "withdrawalMemberLocks")
    MemberLocks sagaLocks;

    @BeforeEach
    void setup() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS outbox_event(event_id VARCHAR(64) PRIMARY"
                    + " KEY,aggregate_id VARCHAR(64),event_type VARCHAR(80),payload CLOB,created_at"
                    + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS processed_events(event_id VARCHAR(64) PRIMARY"
                        + " KEY,event_type VARCHAR(80),processed_at TIMESTAMP DEFAULT"
                        + " CURRENT_TIMESTAMP)");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM processed_events");
        waitlist.deleteAll();
        participants.deleteAll();
        matches.deleteAll();
        when(checks.activeMember(anyLong())).thenAnswer(i -> member(i.getArgument(0), "MALE"));
        doCallRealMethod().when(checks).checkParticipantGender(any(), anyString());
    }

    MemberClient.Member member(Long id, String gender) {
        return new MemberClient.Member(id, gender, "ACTIVE", "SONGPA", List.of());
    }

    MatchEntity full() {
        var start = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        var m =
                matches.saveAndFlush(
                        new MatchEntity(
                                new MatchEntity.Details(
                                        "Queue",
                                        null,
                                        start,
                                        start.plusHours(2),
                                        2,
                                        SkillLevel.BEGINNER,
                                        SportType.TENNIS,
                                        GenderGroup.MALE),
                                new MatchEntity.FacilitySnapshot(
                                        12, "Court", "Location", "Songpa", true, null)));
        participants.saveAndFlush(new MatchParticipantEntity(m, 1, MatchParticipantRole.OWNER));
        participants.saveAndFlush(
                new MatchParticipantEntity(m, 2, MatchParticipantRole.PARTICIPANT));
        m.closeRecruitment(LocalDateTime.now());
        return matches.saveAndFlush(m);
    }

    void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ParticipationProblem.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(status);
                            assertThat(e.code()).isEqualTo(code);
                        });
    }

    WithdrawalRequest request(long user) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return new WithdrawalRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                user,
                now,
                now.plusMinutes(5));
    }

    @Test
    void registrationReturnsRankAndCancellationUpdatesQueue() throws Exception {
        var m = full();
        assertThat(service.register(m.getId(), 3L).position()).isEqualTo(1);
        var second = service.register(m.getId(), 4L);
        assertThat(second.position()).isEqualTo(2);
        assertThat(second.registeredAt()).isNotNull();
        mvc.perform(delete("/api/matches/{id}/waitlist/me", m.getId()).header("X-User-Id", "3"))
                .andExpect(status().isNoContent());
        assertThat(service.register(m.getId(), 5L).position()).isEqualTo(2);
        error(() -> service.cancel(m.getId(), 3L), 404, "WAITLIST_NOT_FOUND");
    }

    @Test
    void apiReturns201AndRejectsMissingIdentity() throws Exception {
        var m = full();
        mvc.perform(post("/api/matches/{id}/waitlist", m.getId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/matches/{id}/waitlist", m.getId()).header("X-User-Id", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.matchId").value(m.getId()))
                .andExpect(jsonPath("$.userId").value(3));
    }

    @Test
    void rejectsOwnerParticipantDuplicateAndNonFullMatch() {
        var m = full();
        error(() -> service.register(m.getId(), 1L), 400, "MATCH_OWNER_CANNOT_WAIT");
        error(() -> service.register(m.getId(), 2L), 409, "MATCH_ALREADY_JOINED");
        service.register(m.getId(), 3L);
        error(() -> service.register(m.getId(), 3L), 409, "WAITLIST_ALREADY_JOINED");
        participation.leave(m.getId(), 2L); // fills from the queue
        participation.leave(m.getId(), 3L); // no remaining waiter; reopens
        error(() -> service.register(m.getId(), 4L), 400, "MATCH_NOT_FULL");
    }

    @Test
    void genderAndInactiveRegistrationDoNotCreateRows() {
        var m = full();
        when(checks.activeMember(3L)).thenReturn(member(3L, "FEMALE"));
        assertThatThrownBy(() -> service.register(m.getId(), 3L))
                .isInstanceOf(MatchDependencyException.class);
        when(checks.activeMember(4L))
                .thenThrow(new MatchDependencyException(400, "USER_NOT_ACTIVE", "Inactive"));
        assertThatThrownBy(() -> service.register(m.getId(), 4L))
                .isInstanceOf(MatchDependencyException.class);
        assertThat(waitlist.count()).isZero();
    }

    @Test
    void fifoPromotionFillsExactlyOneSeatAndEmitsContract() throws Exception {
        var m = full();
        service.register(m.getId(), 3L);
        service.register(m.getId(), 4L);
        jdbc.update(
                "UPDATE match_waitlist SET registered_at='2026-01-01 00:00:00'"); // tie resolved by
        // PK
        participation.leave(m.getId(), 2L);
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 3L)).isTrue();
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 4L)).isFalse();
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(2);
        assertThat(waitlist.count()).isEqualTo(1);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.CLOSED);
        var payload =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT payload FROM outbox_event WHERE"
                                        + " event_type='match.waitlist-promoted.v1'",
                                String.class));
        assertThat(payload.path("userId").asLong()).isEqualTo(3);
        assertThat(payload.path("matchId").asLong()).isEqualTo(m.getId());
        assertThat(payload.path("eventId").asText()).isNotBlank();
        assertThat(payload.size()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class))
                .isEqualTo(1);
    }

    @Test
    void definitivelyIneligibleWaitersAreSkippedInOrder() {
        var m = full();
        for (long id = 3; id <= 5; id++) service.register(m.getId(), id);
        when(checks.activeMember(3L))
                .thenThrow(new MatchDependencyException(400, "USER_NOT_ACTIVE", "Inactive"));
        when(checks.activeMember(4L)).thenReturn(member(4L, "FEMALE"));
        participation.leave(m.getId(), 2L);
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 5L)).isTrue();
        assertThat(waitlist.count()).isZero();
    }

    @Test
    void dependencyOutagePreservesHeadAndRollsBackCancellation() {
        var m = full();
        service.register(m.getId(), 3L);
        service.register(m.getId(), 4L);
        when(checks.activeMember(3L))
                .thenThrow(
                        new MatchDependencyException(
                                503, "USER_SERVICE_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(() -> participation.leave(m.getId(), 2L))
                .isInstanceOf(MatchDependencyException.class);
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 2L)).isTrue();
        assertThat(waitlist.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class)).isZero();
    }

    @Test
    void busyHeadReturns409RatherThanBlockingOrSkipping() throws Exception {
        var m = full();
        service.register(m.getId(), 3L);
        service.register(m.getId(), 4L);
        doThrow(new MemberLockTimeoutException()).when(locks).lockImmediately(3L);
        mvc.perform(delete("/api/matches/{id}/participants/me", m.getId()).header("X-User-Id", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OPERATION_IN_PROGRESS"));
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 2L)).isTrue();
        assertThat(waitlist.count()).isEqualTo(2);
    }

    @Test
    void outboxFailureRollsBackCancellationPromotionAndQueueDeletion() {
        var m = full();
        service.register(m.getId(), 3L);
        jdbc.execute("DROP TABLE outbox_event");
        assertThatThrownBy(() -> participation.leave(m.getId(), 2L))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 2L)).isTrue();
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 3L)).isFalse();
        assertThat(waitlist.count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateRegistrationsProduceOneRow() throws Exception {
        var m = full();
        var pool = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        try {
            List<Future<Boolean>> work = new ArrayList<>();
            for (int i = 0; i < 16; i++)
                work.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        service.register(m.getId(), 3L);
                                        return true;
                                    } catch (ParticipationProblem ex) {
                                        assertThat(ex.code()).isEqualTo("WAITLIST_ALREADY_JOINED");
                                        return false;
                                    }
                                }));
            gate.countDown();
            int ok = 0;
            for (var f : work) if (f.get(20, TimeUnit.SECONDS)) ok++;
            assertThat(ok).isEqualTo(1);
            assertThat(waitlist.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelledHistoryRemainsAndHardDeleteUsesCascade() {
        var m = full();
        service.register(m.getId(), 3L);
        m.cancel("Cancelled", LocalDateTime.now());
        matches.saveAndFlush(m);
        error(() -> service.cancel(m.getId(), 3L), 400, "WAITLIST_CANCEL_NOT_ALLOWED");
        assertThat(waitlist.count()).isEqualTo(1);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            participants.deleteAllByMatch_Id(m.getId());
                            participants.flush();
                            matches.deleteHardById(m.getId());
                        });
        assertThat(waitlist.count()).isZero();
    }

    @Test
    void sagaCleansWaitlistOnlyUserAndDeduplicates() {
        var m = full();
        service.register(m.getId(), 3L);
        var request = request(3);
        saga.handle(request);
        saga.handle(request);
        assertThat(waitlist.count()).isZero();
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE"
                                        + " event_type='match.withdrawal-cleaned.v1'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void rejectedOwnerWithdrawalPreservesWaitlist() {
        var m = full();
        service.register(m.getId(), 3L);
        var start = LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(5);
        var running =
                matches.saveAndFlush(
                        new MatchEntity(
                                new MatchEntity.Details(
                                        "Running",
                                        null,
                                        start,
                                        start.plusHours(1),
                                        2,
                                        SkillLevel.BEGINNER,
                                        SportType.TENNIS,
                                        GenderGroup.MALE),
                                new MatchEntity.FacilitySnapshot(
                                        12, "Court", "Location", "Songpa", true, null)));
        participants.saveAndFlush(
                new MatchParticipantEntity(running, 3, MatchParticipantRole.OWNER));
        running.changeStatus(MatchStatus.IN_PROGRESS, LocalDateTime.now());
        matches.saveAndFlush(running);
        saga.handle(request(3));
        assertThat(waitlist.existsByMatch_IdAndUserId(m.getId(), 3L)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE"
                                    + " event_type='match.withdrawal-rejected.v1'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void sagaParticipantCleanupPromotesWaiterInSameTransaction() {
        var m = full();
        service.register(m.getId(), 3L);
        saga.handle(request(2));
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 2L)).isFalse();
        assertThat(participants.existsByMatch_IdAndUserId(m.getId(), 3L)).isTrue();
        assertThat(waitlist.count()).isZero();
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.CLOSED);
    }
}
