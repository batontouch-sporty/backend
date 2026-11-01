package com.example.sporty.match.participation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLockTimeoutException;
import com.example.sporty.match.withdrawal.MemberLocks;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:participation;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false"
        })
@AutoConfigureMockMvc
class ParticipationTest {
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;
    @Autowired ParticipationService service;
    @Autowired MockMvc mvc;
    @MockitoBean MatchRemoteChecks checks;
    @MockitoBean ParticipationEvents events;

    @MockitoBean(name = "participationMemberLocks")
    MemberLocks locks;

    @BeforeEach
    void clean() {
        waitlist.deleteAll();
        participants.deleteAll();
        matches.deleteAll();
        reset(checks, locks);
        when(checks.activeMember(anyLong()))
                .thenAnswer(
                        invocation ->
                                new MemberClient.Member(
                                        invocation.getArgument(0),
                                        "MALE",
                                        "ACTIVE",
                                        "SONGPA",
                                        List.of()));
    }

    MatchEntity match(int capacity, boolean past) {
        var start = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(past ? -1 : 1);
        var match =
                matches.saveAndFlush(
                        new MatchEntity(
                                new MatchEntity.Details(
                                        "Match",
                                        null,
                                        start,
                                        start.plusHours(2),
                                        capacity,
                                        SkillLevel.BEGINNER,
                                        SportType.TENNIS,
                                        GenderGroup.MIXED),
                                new MatchEntity.FacilitySnapshot(
                                        12, "Court", "Location", "송파구", true, null)));
        participants.saveAndFlush(new MatchParticipantEntity(match, 1, MatchParticipantRole.OWNER));
        return match;
    }

    void error(Runnable call, int status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ParticipationProblem.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(status);
                            assertThat(e.code()).isEqualTo(code);
                        });
    }

    @Test
    void joinClosesAtCapacityAndLeaveReopens() {
        var m = match(2, false);
        var result = service.join(m.getId(), 2L);
        assertThat(result.role()).isEqualTo(MatchParticipantRole.PARTICIPANT);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.CLOSED);
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(2);
        service.leave(m.getId(), 2L);
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(1);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.RECRUITING);
    }

    @Test
    void ownerAndDuplicateCannotJoinTwice() {
        var m = match(4, false);
        error(() -> service.join(m.getId(), 1L), 409, "MATCH_ALREADY_JOINED");
        service.join(m.getId(), 2L);
        error(() -> service.join(m.getId(), 2L), 409, "MATCH_ALREADY_JOINED");
    }

    @Test
    void ownerCannotLeave() {
        var m = match(3, false);
        error(() -> service.leave(m.getId(), 1L), 403, "MATCH_OWNER_CANNOT_LEAVE");
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(1);
    }

    @Test
    void startedAndClosedMatchesRejectJoin() {
        var past = match(3, true);
        error(() -> service.join(past.getId(), 2L), 400, "MATCH_ALREADY_STARTED");
        var closed = match(3, false);
        closed.closeRecruitment(LocalDateTime.now());
        matches.saveAndFlush(closed);
        error(() -> service.join(closed.getId(), 2L), 400, "MATCH_RECRUITMENT_CLOSED");
    }

    @Test
    void inconsistentRecruitingButFullMatchStillRejects() {
        var m = match(1, false);
        error(() -> service.join(m.getId(), 2L), 400, "MATCH_FULL");
    }

    @Test
    void participantMayLeaveAfterStartWithoutReopening() {
        var m = match(2, true);
        m.changeStatus(MatchStatus.IN_PROGRESS, LocalDateTime.now());
        matches.saveAndFlush(m);
        participants.saveAndFlush(
                new MatchParticipantEntity(m, 2, MatchParticipantRole.PARTICIPANT));
        service.leave(m.getId(), 2L);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.IN_PROGRESS);
    }

    @Test
    void missingRecordsReturn404() {
        error(() -> service.join(99999L, 2L), 404, "MATCH_NOT_FOUND");
        var m = match(3, false);
        error(() -> service.leave(m.getId(), 2L), 404, "MATCH_PARTICIPANT_NOT_FOUND");
    }

    @Test
    void dependencyFailureLeavesNoParticipantOrStateChange() {
        var m = match(2, false);
        when(checks.activeMember(2L))
                .thenThrow(
                        new MatchDependencyException(
                                503, "USER_SERVICE_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(() -> service.join(m.getId(), 2L))
                .isInstanceOf(MatchDependencyException.class);
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(1);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.RECRUITING);
    }

    @Test
    void cancelledAndCompletedMatchesKeepParticipantHistory() throws Exception {
        for (var terminal : List.of(MatchStatus.CANCELLED, MatchStatus.COMPLETED)) {
            var m = match(3, false);
            participants.saveAndFlush(
                    new MatchParticipantEntity(m, 2, MatchParticipantRole.PARTICIPANT));
            var changedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            if (terminal == MatchStatus.CANCELLED) m.cancel("Cancelled test", changedAt);
            else m.changeStatus(terminal, changedAt);
            matches.saveAndFlush(m);
            mvc.perform(
                            delete("/api/matches/{id}/participants/me", m.getId())
                                    .header("X-User-Id", "2"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MATCH_LEAVE_NOT_ALLOWED"));
            assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(2);
            assertThat(matches.findById(m.getId()).orElseThrow().getStatus()).isEqualTo(terminal);
        }
    }

    @Test
    void slowMemberLookupDoesNotHoldTheMatchRowLock() throws Exception {
        var m = match(4, false);
        var enteredLookup = new CountDownLatch(1);
        var releaseLookup = new CountDownLatch(1);
        when(checks.activeMember(2L))
                .thenAnswer(
                        invocation -> {
                            enteredLookup.countDown();
                            if (!releaseLookup.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("Lookup timed out");
                            return new MemberClient.Member(
                                    2L, "MALE", "ACTIVE", "SONGPA", List.of());
                        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var slow = pool.submit(() -> service.join(m.getId(), 2L));
            assertThat(enteredLookup.await(3, TimeUnit.SECONDS)).isTrue();
            var other = pool.submit(() -> service.join(m.getId(), 3L));
            assertThat(other.get(3, TimeUnit.SECONDS).userId()).isEqualTo(3L);
            releaseLookup.countDown();
            assertThat(slow.get(3, TimeUnit.SECONDS).userId()).isEqualTo(2L);
            assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(3);
        } finally {
            releaseLookup.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentJoinsCannotExceedCapacity() throws Exception {
        var m = match(3, false);
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> jobs = new ArrayList<>();
            for (long id = 2; id < 22; id++) {
                long user = id;
                jobs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        service.join(m.getId(), user);
                                        return true;
                                    } catch (ParticipationProblem ex) {
                                        assertThat(ex.code()).isEqualTo("MATCH_RECRUITMENT_CLOSED");
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int successes = 0;
            for (var job : jobs) if (job.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(2);
            assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void memberLockTimeoutReturns409ForJoinAndLeaveWithoutChangingData() throws Exception {
        var m = match(3, false);
        participants.saveAndFlush(
                new MatchParticipantEntity(m, 2, MatchParticipantRole.PARTICIPANT));
        doThrow(new MemberLockTimeoutException()).when(locks).lock(2L);
        mvc.perform(post("/api/matches/{id}/participants", m.getId()).header("X-User-Id", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OPERATION_IN_PROGRESS"));
        mvc.perform(delete("/api/matches/{id}/participants/me", m.getId()).header("X-User-Id", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OPERATION_IN_PROGRESS"));
        assertThat(participants.countByMatch_Id(m.getId())).isEqualTo(2);
        assertThat(matches.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.RECRUITING);
    }

    @Test
    void apiReturns201204AndRejectsMissingIdentity() throws Exception {
        var m = match(3, false);
        mvc.perform(post("/api/matches/{id}/participants", m.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/matches/{id}/participants", m.getId()).header("X-User-Id", "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchId").value(m.getId()))
                .andExpect(jsonPath("$.userId").value(2))
                .andExpect(jsonPath("$.role").value("PARTICIPANT"));
        mvc.perform(delete("/api/matches/{id}/participants/me", m.getId()).header("X-User-Id", "2"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }
}
