package com.example.sporty.match.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

@EnabledIfEnvironmentVariable(named = "MATCH_LIVE_MEMBER", matches = "true")
@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:memberlive32;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "match.clients.user.url=http://localhost:18181",
            "match.clients.facility.url=http://localhost:18083"
        })
class LiveMemberContractTest {
    @Autowired MatchRemoteChecks checks;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        MatchDependencyException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(status);
                            assertThat(e.code()).isEqualTo(code);
                        });
    }

    @Test
    void actualMembersReturnTheirGenderAndStatus() {
        assertThat(checks.activeMember(1L).gender()).isEqualTo("MALE");
        assertThat(checks.activeMember(2L).gender()).isEqualTo("FEMALE");
    }

    @Test
    void actualWithdrawingAndWithdrawnMembersCannotParticipate() {
        error(() -> checks.checkParticipant(3L, "MIXED"), 400, "USER_NOT_ACTIVE");
        error(() -> checks.checkParticipant(4L, "MIXED"), 400, "USER_NOT_ACTIVE");
    }

    @Test
    void actualGenderRestrictionsAreEnforced() {
        error(() -> checks.checkParticipant(1L, "FEMALE"), 400, "MATCH_GENDER_NOT_ALLOWED");
        assertThat(checks.checkParticipant(2L, "FEMALE").id()).isEqualTo(2L);
    }

    @Test
    void creationMapsInactiveMembersToItsOwnError() {
        var start = LocalDateTime.of(2030, 1, 2, 18, 0);
        error(
                () -> checks.checkCreation(3L, 12L, start, start.plusHours(2)),
                401,
                "USER_WITHDRAWN");
    }

    @Test
    void actualCsvProfilesIncludeHistoricalMembersAndFillMissingIds() {
        var result = checks.profiles(List.of(1L, 3L, 999L));
        assertThat(result.get(1L).nickname()).isEqualTo("Live active");
        assertThat(result.get(3L).nickname()).isEqualTo("Live history");
        assertThat(result.get(999L).nickname()).isEqualTo("알 수 없음");
    }

    @Test
    void actual404DoesNotOpenTheMemberCircuit() {
        for (int i = 0; i < 12; i++)
            error(() -> checks.activeMember(999L), 401, "MATCH_USER_NOT_FOUND");
        assertThat(checks.activeMember(1L).id()).isEqualTo(1L);
        breakers.getAllCircuitBreakers()
                .forEach(b -> assertThat(b.getState()).isEqualTo(CircuitBreaker.State.CLOSED));
    }
}
