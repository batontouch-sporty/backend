package com.example.sporty.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import io.jsonwebtoken.security.WeakKeyException;

class AuthorizationHeaderFilterTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /user-service/api/users",
            "POST, /user-service/api/auth/login",
            "POST, /user-service/api/auth/refresh",
            "GET, /match-service/api/matches",
            "GET, /match-service/api/matches/1",
            "GET, /facility-service/api/services",
            "GET, /facility-service/api/services/1",
            "POST, /ai-service/api/ai/matches/search",
            "GET, /user-service/health-check",
            "GET, /notification-service/actuator/health",
            "GET, /match-service/api/matches/%31"
    })
    void publicRequests(String method, String path) {
        assertThat(AuthorizationHeaderFilter.isPublic(HttpMethod.valueOf(method), path)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /user-service/api/users",
            "POST, /match-service/api/matches",
            "POST, /match-service/api/matches/1",
            "DELETE, /match-service/api/matches/1",
            "GET, /ai-service/api/ai/matches/search",
            "GET, /match-service/api/matches/me",
            "GET, /match-service/api/matches//me",
            "GET, /match-service/api/matches/%6de",
            "GET, /match-service/api/matches/%256de",
            "GET, /match-service/api/matches/me;v=1",
            "GET, /match-service/api/matches/1/../me",
            "GET, /match-service/api/matches/1/%2e%2e/me",
            "GET, /match-service/api/matches/1/%252e%252e/me",
            "GET, /match-service/api/matches/1/..;/me",
            "GET, /match-service/api/matches/me/%252e%252e/1",
            "GET, /match-service/api/matches/1/participants",
            "GET, /MATCH-SERVICE/api/matches",
            "GET, /match-service/API/MATCHES",
            "GET, /user-service/../match-service/api/matches",
            "GET, /user-service/actuator/env"
    })
    void privateRequests(String method, String path) {
        assertThat(AuthorizationHeaderFilter.isPublic(HttpMethod.valueOf(method), path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user-service/api/admin",
            "/user-service/api/admin/users",
            "/user-service/api/ADMIN/users",
            "/user-service/API/Admin/users",
            "/user-service//api//admin/users",
            "/user-service/%61pi/%61dmin/users",
            "/user-service/api/%2561dmin/users",
            "/user-service/api/admin;v=1/users",
            "/user-service/api%2Fadmin/users",
            "/user-service/api%5Cadmin/users",
            "/user-service/api/x/../admin/users",
            "/user-service/api/./admin/users",
            "/user-service/../api/admin/users",
            "/user-service/api/admin/%0Ausers",
            "/match-service/api/matches/%2e%2e/admin/users"
    })
    void adminPaths(String path) {
        assertThat(AuthorizationHeaderFilter.isAdmin(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user-service/api/users",
            "/user-service/api/administrators",
            "/user-service/admin/users",
            "/user-service/api/search/100%25off"
    })
    void notAdminPaths(String path) {
        assertThat(AuthorizationHeaderFilter.isAdmin(path)).isFalse();
    }

    @Test
    void rejectsShortSecretAtStartup() {
        assertThatThrownBy(() -> new AuthorizationHeaderFilter("change-me"))
                .isInstanceOf(WeakKeyException.class);
    }
}
