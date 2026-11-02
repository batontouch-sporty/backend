package com.example.sporty.ai.metrics;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.example.sporty.ai.commons.exception.AiException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

/**
 * AI 운영 지표. Grafana 대시보드 ⑤(도메인·AI)에서 성공률·응답시간을 본다.
 * ai.request{type=search|draft|recommend, outcome=success|fallback|rejected|error}
 * - success: 정상 응답 / fallback: 대체 응답(200)
 * - rejected: 입력·회원 문제로 거절한 4xx(성공도 장애도 아님)
 * - error: 5xx와 예상 못 한 예외
 */
@Component
@RequiredArgsConstructor
public class AiMetrics {

    public static final String TYPE_SEARCH = "search";
    public static final String TYPE_DRAFT = "draft";
    public static final String TYPE_RECOMMEND = "recommend";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FALLBACK = "fallback";
    public static final String OUTCOME_REJECTED = "rejected";
    public static final String OUTCOME_ERROR = "error";

    private final MeterRegistry registry;

    // 예외로 끝난 요청의 outcome. 4xx AiException만 rejected, 나머지(5xx·예상 못 한 예외)는 error.
    public static String outcomeOf(Throwable e) {
        if (e instanceof AiException aiException && aiException.getStatus().is4xxClientError()) {
            return OUTCOME_REJECTED;
        }
        return OUTCOME_ERROR;
    }

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
