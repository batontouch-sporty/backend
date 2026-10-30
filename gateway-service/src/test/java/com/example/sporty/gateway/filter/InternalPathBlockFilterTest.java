package com.example.sporty.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InternalPathBlockFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/users",
            "/user-service/internal/users",
            "/user-service//internal/users",
            "/user-service/health-check/../internal/users",
            "/user-service/%69nternal/users",
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
            "/actuator/health"
    })
    void allowsOtherPaths(String path) {
        assertThat(InternalPathBlockFilter.isInternal(path)).isFalse();
    }
}
