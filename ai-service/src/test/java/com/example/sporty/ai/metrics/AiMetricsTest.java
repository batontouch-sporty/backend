package com.example.sporty.ai.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.sporty.ai.commons.exception.AiException;

class AiMetricsTest {

    @Test
    void AI_4xx는_rejected_5xx와_예상_못_한_예외는_error() {
        assertThat(AiMetrics.outcomeOf(AiException.draftInsufficient())).isEqualTo(AiMetrics.OUTCOME_REJECTED);
        assertThat(AiMetrics.outcomeOf(AiException.userNotFound())).isEqualTo(AiMetrics.OUTCOME_REJECTED);
        assertThat(AiMetrics.outcomeOf(AiException.unavailable())).isEqualTo(AiMetrics.OUTCOME_ERROR);
        assertThat(AiMetrics.outcomeOf(AiException.recommendFailed())).isEqualTo(AiMetrics.OUTCOME_ERROR);
        assertThat(AiMetrics.outcomeOf(new IllegalStateException("boom"))).isEqualTo(AiMetrics.OUTCOME_ERROR);
    }
}
