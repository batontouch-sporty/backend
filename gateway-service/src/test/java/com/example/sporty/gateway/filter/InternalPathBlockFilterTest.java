package com.example.sporty.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

class InternalPathBlockFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/users",
            "/user-service/internal/users",
            "/user-service//internal/users",
            "/user-service/health-check/../internal/users",
            "/user-service/%69nternal/users",
            "/user-service/%2569nternal/users",
            "/user-service/internal;v=1/users",
            "/user-service/x%2Finternal/users",
            "/user-service/INTERNAL/users"
    })
    void blocksInternalPaths(String path) {
        assertThat(InternalPathBlockFilter.isInternal(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user-service/health-check",
            "/user-service/api/internal-notes",
            "/user-service/api/search/100%25off",
            "/actuator/health"
    })
    void allowsOtherPaths(String path) {
        assertThat(InternalPathBlockFilter.isInternal(path)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /user-service/actuator",
            "GET, /user-service/actuator/",
            "GET, /user-service/actuator/prometheus",
            "GET, /match-service/actuator/env",
            "GET, /user-service/actuator/info",
            "GET, /user-service/actuator/health/liveness",
            "GET, /user-service/actuator/health/",
            "GET, /user-service/actuator/health;v=1",
            "GET, /user-service/actuator/health/../prometheus",
            "GET, /user-service/actuator/health%2F..%2Fprometheus",
            "GET, /user-service/actuator/%2568ealth",
            "GET, /user-service//actuator/health",
            "GET, /user-service//actuator/prometheus",
            "GET, /user-service/health-check/../actuator/prometheus",
            "GET, /user-service/./actuator/prometheus",
            "GET, /user-service/%61ctuator/prometheus",
            "GET, /user-service/%2561ctuator/prometheus",
            "GET, /user-service/%252561ctuator/prometheus",
            "GET, /user-service/actuator;v=1/prometheus",
            "GET, /user-service/x%2Factuator/prometheus",
            "GET, /user-service/actuator%2Fprometheus",
            "GET, /user-service/actuator%5Cprometheus",
            "GET, /user-service/ACTUATOR/prometheus",
            "GET, /actuator/prometheus",
            "GET, /actuator/health",
            "POST, /user-service/actuator/health",
            "POST, /user-service/actuator/refresh"
    })
    void blocksActuatorPaths(String method, String path) {
        assertThat(InternalPathBlockFilter.isBlockedActuator(HttpMethod.valueOf(method), path)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /user-service/actuator/health",
            "GET, /notification-service/actuator/health",
            "GET, /user-service/health-check",
            "GET, /user-service/api/actuator-notes",
            "GET, /match-service/api/matches"
    })
    void keepsPublicHealthAndOtherPaths(String method, String path) {
        assertThat(InternalPathBlockFilter.isBlockedActuator(HttpMethod.valueOf(method), path)).isFalse();
    }
}
