package com.example.sporty.match.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;

import io.github.resilience4j.circuitbreaker.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:feign32;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.config.import=",
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "spring.cloud.openfeign.client.config.matchMemberClient.connectTimeout=100",
            "spring.cloud.openfeign.client.config.matchMemberClient.readTimeout=150",
            "spring.cloud.openfeign.client.config.matchFacilityClient.connectTimeout=100",
            "spring.cloud.openfeign.client.config.matchFacilityClient.readTimeout=150"
        })
@AutoConfigureMockMvc
@Import(MatchRemoteChecksTest.Probe.class)
class MatchRemoteChecksTest {
    static final AtomicInteger memberStatus = new AtomicInteger(200),
            facilityStatus = new AtomicInteger(200),
            memberCalls = new AtomicInteger(),
            memberDelay = new AtomicInteger(),
            facilityDelay = new AtomicInteger();
    static volatile String memberState = "ACTIVE",
            gender = "MALE",
            open = "09:00:00",
            close = "22:00:00";
    static volatile boolean active = true, malformedMember = false, partialProfiles = false;
    static volatile String profileQuery = "";
    static final ExecutorService executor =
            Executors.newCachedThreadPool(
                    r -> {
                        var t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    });
    static final HttpServer memberServer = server(true), facilityServer = server(false);

    static HttpServer server(boolean member) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext(
                    "/internal/",
                    exchange -> {
                        try {
                            if (member) memberCalls.incrementAndGet();
                            int status = (member ? memberStatus : facilityStatus).get();
                            int delay = (member ? memberDelay : facilityDelay).get();
                            if (delay > 0) Thread.sleep(delay);
                            String path = exchange.getRequestURI().getPath();
                            Object response = Map.of();
                            if (member && status == 200) {
                                if (path.equals("/internal/users")) {
                                    profileQuery = exchange.getRequestURI().getQuery();
                                    String[] ids = profileQuery.substring(4).split(",");
                                    response =
                                            Arrays.stream(ids)
                                                    .map(Long::valueOf)
                                                    .filter(id -> !partialProfiles || id == 1)
                                                    .map(
                                                            id ->
                                                                    Map.of(
                                                                            "id",
                                                                            id,
                                                                            "profileId",
                                                                            id + 10,
                                                                            "nickname",
                                                                            "member" + id,
                                                                            "imageUrl",
                                                                            "image" + id))
                                                    .toList();
                                } else
                                    response =
                                            Map.of(
                                                    "id",
                                                    malformedMember
                                                            ? 999
                                                            : Long.parseLong(
                                                                    path.substring(
                                                                            path.lastIndexOf('/')
                                                                                    + 1)),
                                                    "status",
                                                    memberState,
                                                    "gender",
                                                    gender,
                                                    "district",
                                                    "MAPO",
                                                    "sportTypes",
                                                    List.of("FUTSAL"));
                            } else if (!member && status == 200) {
                                response =
                                        Map.of(
                                                "id",
                                                12,
                                                "name",
                                                "Saved court",
                                                "startTime",
                                                open,
                                                "endTime",
                                                close,
                                                "active",
                                                active,
                                                "url",
                                                "https://example.com/reserve",
                                                "locationName",
                                                "Saved location",
                                                "region",
                                                "송파구",
                                                "isFree",
                                                false);
                            }
                            byte[] body = new ObjectMapper().writeValueAsBytes(response);
                            exchange.getResponseHeaders()
                                    .set("Content-Type", "application/json;charset=UTF-8");
                            exchange.sendResponseHeaders(status, body.length);
                            exchange.getResponseBody().write(body);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add(
                "match.clients.user.url",
                () -> "http://127.0.0.1:" + memberServer.getAddress().getPort());
        registry.add(
                "match.clients.facility.url",
                () -> "http://127.0.0.1:" + facilityServer.getAddress().getPort());
    }

    @Autowired MatchRemoteChecks checks;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired MockMvc mvc;
    LocalDateTime start = LocalDate.of(2030, 1, 2).atTime(18, 0), end = start.plusHours(2);

    @BeforeEach
    void reset() {
        memberStatus.set(200);
        facilityStatus.set(200);
        memberCalls.set(0);
        memberDelay.set(0);
        facilityDelay.set(0);
        memberState = "ACTIVE";
        gender = "MALE";
        open = "09:00:00";
        close = "22:00:00";
        active = true;
        malformedMember = false;
        partialProfiles = false;
        profileQuery = "";
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @AfterAll
    static void stop() {
        memberServer.stop(0);
        facilityServer.stop(0);
        executor.shutdownNow();
    }

    void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        MatchDependencyException.class,
                        ex -> {
                            assertThat(ex.status()).isEqualTo(status);
                            assertThat(ex.code()).isEqualTo(code);
                        });
    }

    @Test
    void realFeignReturnsMemberAndValidatedFacilitySnapshot() {
        var context = checks.checkCreation(5L, 12L, start, end);
        assertThat(context.member().district()).isEqualTo("MAPO");
        assertThat(context.facility())
                .isEqualTo(
                        new MatchRemoteChecks.FacilitySnapshot(
                                12L,
                                "Saved court",
                                "Saved location",
                                "송파구",
                                false,
                                "https://example.com/reserve"));
    }

    @Test
    void inactiveMemberMapsDifferentlyForCreateAndJoin() {
        for (String state : List.of("WITHDRAWING", "WITHDRAWN")) {
            memberState = state;
            error(() -> checks.checkParticipant(5L, "MIXED"), 400, "USER_NOT_ACTIVE");
            error(() -> checks.checkCreation(5L, 12L, start, end), 401, "USER_WITHDRAWN");
        }
    }

    @Test
    void participantGenderIsChecked() {
        assertThat(checks.checkParticipant(5L, "MIXED").id()).isEqualTo(5L);
        assertThat(checks.checkParticipant(5L, "MALE").id()).isEqualTo(5L);
        error(() -> checks.checkParticipant(5L, "FEMALE"), 400, "MATCH_GENDER_NOT_ALLOWED");
    }

    @Test
    void confirmed404DoesNotBecome503() {
        memberStatus.set(404);
        error(() -> checks.activeMember(5L), 401, "MATCH_USER_NOT_FOUND");
        facilityStatus.set(404);
        error(() -> checks.checkFacility(12L, start, end), 404, "SERVICE_NOT_FOUND");
    }

    @Test
    void memberOutageFailsClosed() {
        memberStatus.set(500);
        error(() -> checks.checkParticipant(5L, "MIXED"), 503, "USER_SERVICE_UNAVAILABLE");
    }

    @Test
    void facilityOutageFailsClosed() {
        facilityStatus.set(500);
        error(() -> checks.checkFacility(12L, start, end), 503, "FACILITY_SERVICE_UNAVAILABLE");
    }

    @Test
    void malformedSuccessfulMemberResponseFailsClosed() {
        malformedMember = true;
        error(() -> checks.activeMember(5L), 503, "USER_SERVICE_UNAVAILABLE");
    }

    @Test
    void inactiveFacilityIsBusinessError() {
        active = false;
        error(() -> checks.checkFacility(12L, start, end), 400, "FACILITY_INACTIVE");
    }

    @Test
    void operatingWindowIncludesBoundaryAndRejectsOverflow() {
        assertThat(checks.checkFacility(12L, start.withHour(9), end.withHour(22))).isNotNull();
        error(
                () -> checks.checkFacility(12L, start.withHour(8), end),
                400,
                "FACILITY_HOURS_MISMATCH");
        error(
                () -> checks.checkFacility(12L, start, end.withHour(23)),
                400,
                "FACILITY_HOURS_MISMATCH");
    }

    @Test
    void overnightWindowIsBoundToOpeningDate() {
        open = "22:00:00";
        close = "02:00:00";
        assertThat(checks.checkFacility(12L, start.withHour(23), end.plusDays(1).withHour(1)))
                .isNotNull();
        assertThat(checks.checkFacility(12L, start.withHour(0), end.withHour(1))).isNotNull();
        error(
                () -> checks.checkFacility(12L, start.withHour(23), end.plusDays(1).withHour(3)),
                400,
                "FACILITY_HOURS_MISMATCH");
    }

    @Test
    void equalHoursAreNotInventedAsAllDay() {
        open = "09:00:00";
        close = "09:00:00";
        error(() -> checks.checkFacility(12L, start, end), 400, "FACILITY_HOURS_MISMATCH");
    }

    @Test
    void invalidInputIsRejectedBeforeRemoteCall() {
        error(() -> checks.checkCreation(5L, 12L, end, start), 400, "INVALID_INPUT");
        error(() -> checks.checkParticipant(5L, "INVALID"), 400, "INVALID_INPUT");
        error(() -> checks.activeMember(null), 400, "INVALID_INPUT");
        assertThat(memberCalls.get()).isZero();
    }

    @Test
    void profilesUseOneCsvRequestAndFillMissingProfiles() {
        partialProfiles = true;
        var rows = checks.profiles(List.of(1L, 2L, 1L));
        assertThat(profileQuery).isEqualTo("ids=1,2");
        assertThat(memberCalls.get()).isEqualTo(1);
        assertThat(rows.get(1L).profileId()).isEqualTo(11L);
        assertThat(rows.get(2L).nickname()).isEqualTo("알 수 없음");
        assertThat(rows.get(2L).profileId()).isNull();
    }

    @Test
    void profileFailureDoesNotBlockDisplay() {
        memberStatus.set(503);
        assertThat(checks.profiles(List.of(1L)).get(1L).nickname()).isEqualTo("알 수 없음");
    }

    @Test
    void emptyProfilesDoNotCallMemberService() {
        assertThat(checks.profiles(List.of())).isEmpty();
        assertThat(memberCalls.get()).isZero();
    }

    @Test
    void memberReadTimeoutReturns503() {
        memberDelay.set(500);
        error(() -> checks.activeMember(5L), 503, "USER_SERVICE_UNAVAILABLE");
    }

    @Test
    void facilityReadTimeoutReturns503() {
        facilityDelay.set(500);
        error(() -> checks.checkFacility(12L, start, end), 503, "FACILITY_SERVICE_UNAVAILABLE");
    }

    @Test
    void repeatedFailuresOpenCircuitAndStopHttpRequests() {
        memberStatus.set(503);
        for (int i = 0; i < 5; i++)
            error(() -> checks.activeMember(5L), 503, "USER_SERVICE_UNAVAILABLE");
        int calls = memberCalls.get();
        error(() -> checks.activeMember(5L), 503, "USER_SERVICE_UNAVAILABLE");
        assertThat(memberCalls.get()).isEqualTo(calls);
        assertThat(breakers.getAllCircuitBreakers())
                .anyMatch(b -> b.getState() == CircuitBreaker.State.OPEN);
    }

    @Test
    void memberLookupCircuitDoesNotBlockProfileFallbackClient() {
        memberStatus.set(503);
        for (int i = 0; i < 5; i++)
            error(() -> checks.activeMember(5L), 503, "USER_SERVICE_UNAVAILABLE");
        memberStatus.set(200);
        assertThat(checks.profiles(List.of(1L)).get(1L).nickname()).isEqualTo("member1");
    }

    @Test
    void restErrorMappingUsesContractWithoutUpstreamDetails() throws Exception {
        memberStatus.set(500);
        mvc.perform(get("/__test/member"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("USER_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("회원 확인 불가, 잠시 후 다시"));
    }

    /** Test-only probe: production exposes no new integration REST endpoint. */
    @RestController
    static class Probe {
        private final MatchRemoteChecks checks;

        Probe(MatchRemoteChecks checks) {
            this.checks = checks;
        }

        @GetMapping("/__test/member")
        Object member() {
            return checks.checkParticipant(5L, "MIXED");
        }
    }

    @Autowired MatchFacilityIntegration integration;
    @Autowired com.example.sporty.match.repository.MatchRepository matches;
    @Autowired jakarta.persistence.EntityManager entityManager;

    com.example.sporty.match.domain.MatchEntity.Details details(String title) {
        return new com.example.sporty.match.domain.MatchEntity.Details(
                title,
                null,
                start,
                end,
                3,
                com.example.sporty.match.domain.SkillLevel.BEGINNER,
                com.example.sporty.match.domain.SportType.TENNIS,
                com.example.sporty.match.domain.GenderGroup.MIXED);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void commonEntityPersistsTheValidatedFacilitySnapshotForCreationAndUpdate() {
        var match = matches.saveAndFlush(integration.create(1L, details("Created"), 12L));
        var id = match.getId();
        entityManager.clear();
        var stored = matches.findByIdForUpdate(id).orElseThrow();
        assertThat(stored.getFacilityId()).isEqualTo(12L);
        assertThat(stored.getFacilityName()).isEqualTo("Saved court");
        assertThat(stored.getLocationName()).isEqualTo("Saved location");
        assertThat(stored.getUrl()).isEqualTo("https://example.com/reserve");
        assertThat(stored.getRegion()).isEqualTo("송파구");
        assertThat(stored.getIsFree()).isFalse();
        integration.update(stored, details("Updated"), 12L);
        matches.flush();
        entityManager.clear();
        assertThat(matches.findById(id).orElseThrow().getTitle()).isEqualTo("Updated");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void dependencyFailureLeavesTheStoredMatchUnchanged() {
        var match = matches.saveAndFlush(integration.create(1L, details("Original"), 12L));
        active = false;
        error(() -> integration.update(match, details("Rejected"), 12L), 400, "FACILITY_INACTIVE");
        facilityStatus.set(500);
        error(
                () -> integration.update(match, details("Failed"), 12L),
                503,
                "FACILITY_SERVICE_UNAVAILABLE");
        matches.flush();
        entityManager.clear();
        assertThat(matches.findById(match.getId()).orElseThrow().getTitle()).isEqualTo("Original");
    }
    @Test
    void repeatedMissingIdsDoNotOpenEitherCircuitAndValidIdsStillWork() {
        memberStatus.set(404);facilityStatus.set(404);
        for(int i=0;i<12;i++) {
            error(()->checks.activeMember(1L),401,"MATCH_USER_NOT_FOUND");
            error(()->checks.checkFacility(12L,start,end),404,"SERVICE_NOT_FOUND");
        }
        assertThat(breakers.getAllCircuitBreakers()).isNotEmpty();
        breakers.getAllCircuitBreakers().forEach(b->{
            assertThat(b.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(b.getMetrics().getNumberOfFailedCalls()).isZero();
        });
        memberStatus.set(200);facilityStatus.set(200);
        assertThat(checks.activeMember(1L).id()).isEqualTo(1L);
        assertThat(checks.checkFacility(12L,start,end).facilityId()).isEqualTo(12L);
    }

}
