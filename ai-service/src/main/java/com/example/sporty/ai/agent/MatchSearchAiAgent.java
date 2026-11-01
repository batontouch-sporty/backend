package com.example.sporty.ai.agent;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.sporty.ai.client.MatchClient;
import com.example.sporty.ai.client.dto.MatchDto;
import com.example.sporty.ai.commons.exception.AiException;
import com.example.sporty.ai.domain.dto.AiSearchConditionDto;
import com.example.sporty.ai.domain.dto.AiSearchResponseDto;
import com.example.sporty.ai.domain.enums.MatchStatus;
import com.example.sporty.ai.metrics.AiMetrics;
import com.example.sporty.ai.util.KeywordConditionExtractor;
import com.example.sporty.ai.util.PromptDateTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A22 자연어 매치 검색. 1차 AI-02를 옮기고 Feign·Fallback을 더했다.
 * - LLM 실패·타임아웃: 문장 속 종목·자치구 단어로 대체 검색, 없으면 모집 중 매치. conditions=[], fallback=true
 * - match-service 호출 실패: 503 AI_UNAVAILABLE (매치 목록 없이는 응답할 수 없음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchSearchAiAgent {

    private final SearchChat searchChat;
    private final MatchClient matchClient;
    private final AiMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public AiSearchResponseDto search(String prompt) {
        long start = System.nanoTime();
        String outcome = AiMetrics.OUTCOME_SUCCESS;
        try {
            AiSearchConditionDto condition;
            boolean fallback = false;
            try {
                condition = extractCondition(prompt);
            } catch (LlmFailedException e) {
                condition = fallbackCondition(prompt);
                fallback = true;
                outcome = AiMetrics.OUTCOME_FALLBACK;
            }

            List<MatchDto> matches = searchMatches(condition);
            return AiSearchResponseDto.builder()
                    .conditions(fallback ? List.of() : condition.toConditions())
                    .matches(matches)
                    .criteria(condition)
                    .fallback(fallback)
                    .build();
        } catch (AiException e) {
            // 400은 LLM이 정상 응답한 업무 오류라 success, 5xx만 error로 센다.
            if (e.getStatus().is5xxServerError()) {
                outcome = AiMetrics.OUTCOME_ERROR;
            }
            throw e;
        } finally {
            metrics.record(AiMetrics.TYPE_SEARCH, outcome, start);
        }
    }

    // LLM으로 조건 추출. 조건이 없으면 400 AI_CONDITION_NOT_FOUND (LLM이 정상 응답한 경우만)
    AiSearchConditionDto extractCondition(String prompt) {
        String result;
        try {
            result = searchChat.call(systemPrompt(), prompt);
        } catch (RuntimeException e) {
            if (MatchDraftAiAgent.isUnsupportedValue(e)) {
                throw AiException.unsupportedSport();
            }
            log.warn("AI 검색 LLM 호출 실패, 대체 검색으로 진행: {}", e.getClass().getSimpleName());
            throw new LlmFailedException();
        }

        AiSearchConditionDto condition = toCondition(result);
        if (condition == null || condition.isEmpty()) {
            throw AiException.conditionNotFound();
        }
        return condition;
    }

    // 대체 검색 조건: 문장 속 종목·자치구 단어. 하나도 없으면 모집 중 매치를 보여준다.
    private AiSearchConditionDto fallbackCondition(String prompt) {
        AiSearchConditionDto keyword = KeywordConditionExtractor.extract(prompt);
        if (keyword.isEmpty()) {
            return AiSearchConditionDto.builder().status(MatchStatus.RECRUITING).build();
        }
        return keyword;
    }

    private List<MatchDto> searchMatches(AiSearchConditionDto condition) {
        try {
            List<MatchDto> matches = matchClient.searchMatches(condition.toQuery());
            return matches == null ? List.of() : matches;
        } catch (FeignException e) {
            log.warn("AI 검색 중 match-service 호출 실패: status={}", e.status());
            throw AiException.unavailable();
        }
    }

    private String systemPrompt() {
        return """
                당신은 운동 매칭 서비스의 검색 에이전트입니다.

                ## 규칙
                1. 사용자의 문장에서 검색 조건을 추출해 searchMatches 도구를 호출한다.
                2. 문장에 없는 조건은 채우지 않는다. 추측하지 않는다.
                3. 지역은 서울시 자치구 이름으로 변환한다. 예: 강남역 → 강남구, 잠실 → 송파구
                4. 날짜는 직접 계산하지 않고 아래 날짜표에서 찾아 사용한다.
                5. 기간 표현은 startAt을 시작일 00:00, endAt을 종료일 23:59로 한다.
                6. 성별 구성은 남자만 → MALE, 여자만 → FEMALE, 남녀 함께·혼성 → MIXED로 변환한다.

                ## 날짜표
                %s
                """.formatted(PromptDateTable.of(PromptDateTable.today()));
    }

    // tool이 호출되면 조건 JSON, 호출되지 않으면 AI의 안내 문장이 온다.
    private AiSearchConditionDto toCondition(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(result, AiSearchConditionDto.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** LLM 호출 자체가 실패했음을 알리는 내부 신호 */
    static class LlmFailedException extends RuntimeException {
    }
}
