package com.example.sporty.match.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

/** Run against the independently started facility-service PR, never an HTTP fixture. */
@EnabledIfEnvironmentVariable(named = "MATCH_LIVE_FACILITY", matches = "true")
@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:live32;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "match.clients.facility.url=http://localhost:18083",
            "match.clients.user.url=http://localhost:18081"
        })
class LiveFacilityContractTest {
    @Autowired MatchRemoteChecks checks;
    final LocalDateTime start = LocalDateTime.of(2030, 1, 2, 18, 0);

    @Test
    void actualFacilityResponseDeserializesAndCopiesAllSnapshotFields() {
        var result = checks.checkFacility(12L, start, start.plusHours(2)).toEntitySnapshot();
        assertThat(result.id()).isEqualTo(12L);
        assertThat(result.facilityName()).isEqualTo("Live facility");
        assertThat(result.locationName()).isEqualTo("Live location");
        assertThat(result.region()).isEqualTo("송파구");
        assertThat(result.isFree()).isFalse();
        assertThat(result.url()).isEqualTo("https://example.org/live");
    }

    @Test
    void actualInactiveFacilityIsRejected() {
        assertThatThrownBy(() -> checks.checkFacility(13L, start, start.plusHours(2)))
                .isInstanceOfSatisfying(
                        MatchDependencyException.class,
                        e -> assertThat(e.code()).isEqualTo("FACILITY_INACTIVE"));
    }

    @Test
    void actualMissingFacilityIsNotMistakenForAnOutage() {
        assertThatThrownBy(() -> checks.checkFacility(999L, start, start.plusHours(2)))
                .isInstanceOfSatisfying(
                        MatchDependencyException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(404);
                            assertThat(e.code()).isEqualTo("SERVICE_NOT_FOUND");
                        });
    }

    @Test
    void actualHoursRejectAnOutsideSchedule() {
        assertThatThrownBy(() -> checks.checkFacility(12L, start.withHour(7), start.withHour(8)))
                .isInstanceOfSatisfying(
                        MatchDependencyException.class,
                        e -> assertThat(e.code()).isEqualTo("FACILITY_HOURS_MISMATCH"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MATCH_LIVE_FACILITY_DOWN", matches = "true")
    void actualStoppedFacilityReturns503() {
        assertThatThrownBy(() -> checks.checkFacility(12L, start, start.plusHours(2)))
                .isInstanceOfSatisfying(
                        MatchDependencyException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(503);
                            assertThat(e.code()).isEqualTo("FACILITY_SERVICE_UNAVAILABLE");
                        });
    }
}
