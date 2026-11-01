package com.example.sporty.ai.metrics;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

/**
 * AI 운영 지표. Grafana 대시보드 ⑤(도메인·AI)에서 성공률·응답시간을 본다.
 * ai.request{type=search|draft|recommend, outcome=success|fallback|error}
 */
@Component
@RequiredArgsConstructor
public class AiMetrics {

    public static final String TYPE_DRAFT = "draft";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FALLBACK = "fallback";
    public static final String OUTCOME_ERROR = "error";

    private final MeterRegistry registry;

    public void record(String type, String outcome, long startNanos) {
        Timer.builder("ai.request")
                .description("AI 요청 처리 시간과 결과")
                .tag("type", type)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
