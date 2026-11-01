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
    @ValueSource(strings = {
            "/user-service/api/users",
            "/user-service/api/auth/login",
            "/user-service/api/auth/refresh",
            "/user-service/api/auth/%6cogin"
    })
    void tokenIgnoredRequests(String path) {
        assertThat(AuthorizationHeaderFilter.isTokenIgnored(HttpMethod.POST, path)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /user-service/api/auth/login",
            "GET, /user-service/api/users",
            "POST, /user-service/api/users/1",
            "POST, /user-service/api/auth/logout",
            "POST, /user-service/api/auth/login/%2e%2e/logout",
            "POST, /user-service/api/auth/login/../logout",
            "POST, /user-service/api/auth/LOGIN",
            "POST, /user-service/api/auth/logout/%252e%252e/login",
            "POST, /match-service/api/auth/login",
            "POST, /match-service/../user-service/api/auth/login"
    })
    void tokenCheckedRequests(String method, String path) {
        assertThat(AuthorizationHeaderFilter.isTokenIgnored(HttpMethod.valueOf(method), path)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
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
            "GET, /match-service/api/matches/1;v=1",
            "GET, /match-service/api/matches/1/",
            "GET, /match-service/api/matches/1/.",
            "GET, /match-service/api/matches/./1",
            "GET, /match-service/api/matches/1/participants",
            "GET, /MATCH-SERVICE/api/matches",
            "GET, /match-service/API/MATCHES",
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
            "/user-service/api/./admin/users",
            "/user-service/api/admin/%0Ausers",
            "/prometheus",
            "/prometheus/api/v1/query",
            "/PROMETHEUS/api/v1/query",
            "/%70rometheus/api/v1/query",
            "/%2570rometheus/api/v1/query",
            "//prometheus/api/v1/query",
            "/prometheus;v=1/api/v1/query"
    })
    void adminPaths(String path) {
        assertThat(AuthorizationHeaderFilter.isAdmin(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user-service/api/users",
            "/user-service/api/administrators",
            "/user-service/admin/users",
            "/user-service/api/search/100%25off",
            "/prometheus-data/api/v1/query",
            "/user-service/prometheus"
    })
    void notAdminPaths(String path) {
        assertThat(AuthorizationHeaderFilter.isAdmin(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/match-service/api/matches/1/../me",
            "/match-service/api/matches/1/%2e%2e/me",
            "/match-service/api/matches/1/%252e%252e/me",
            "/match-service/api/matches/1/.%2E/me",
            "/match-service/api/matches/1/..;/me",
            "/match-service/api/matches/1/..;x=1/me",
            "/match-service/api/matches/1/%2e%2e%3Bx/me",
            "/match-service/api/matches/1%2F..%2Fme",
            "/match-service/api/matches/1%5C..%5Cme",
            "/user-service/../match-service/api/matches",
            "/user-service/.."
    })
    void parentSegmentPaths(String path) {
        assertThat(AuthorizationHeaderFilter.hasParentSegment(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/match-service/api/matches",
            "/match-service/api/matches/./1",
            "/user-service/api/users/a..b",
            "/user-service/api/users/...",
            "/user-service/api/search/100%25off"
    })
    void noParentSegmentPaths(String path) {
        assertThat(AuthorizationHeaderFilter.hasParentSegment(path)).isFalse();
    }

    @Test
    void rejectsShortSecretAtStartup() {
        assertThatThrownBy(() -> new AuthorizationHeaderFilter("change-me"))
                .isInstanceOf(WeakKeyException.class);
    }
}
