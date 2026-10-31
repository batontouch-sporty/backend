package com.example.sporty.facility.openapi;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 부분 수집 데이터로 동기화하지 않도록 수집 건수를 검증한다. 외부 API는 호출하지 않는다.
 */
class SeoulFacilityApiClientTest {

    @Test
    void 전체_건수를_다_받으면_통과한다() {
        assertThatCode(() -> SeoulFacilityApiClient.verifyComplete(250, 250))
                .doesNotThrowAnyException();
    }

    @Test
    void 일부만_받으면_동기화를_중단한다() {
        assertThatThrownBy(() -> SeoulFacilityApiClient.verifyComplete(200, 250))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("일부만");
    }
}
