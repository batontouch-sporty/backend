package com.example.sporty.ai.agent;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.stereotype.Service;

import com.example.sporty.ai.commons.exception.AiException;
import com.example.sporty.ai.domain.dto.AiDraftDto;
import com.example.sporty.ai.domain.dto.AiDraftResponseDto;
import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.metrics.AiMetrics;
import com.example.sporty.ai.util.PromptDateTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A23 매치 초안 생성. 1차 AI-01을 옮겼다.
 * LLM이 실패하면 초안을 만들 수 없으므로 Fallback 없이 503 AI_UNAVAILABLE로 응답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchDraftAiAgent {

    private static final int DEFAULT_MATCH_HOURS = 2;
    private static final int TITLE_MAX = 50;
    private static final int DESCRIPTION_MAX = 500;

    private final DraftChat draftChat;
    private final AiMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public AiDraftResponseDto draft(String prompt) {
        long start = System.nanoTime();
        try {
            AiDraftResponseDto response = AiDraftResponseDto.builder()
                    .initialValues(complete(requestDraft(prompt)))
                    .build();
            metrics.record(AiMetrics.TYPE_DRAFT, AiMetrics.OUTCOME_SUCCESS, start);
            return response;
        } catch (AiException e) {
            // 503(LLM 실패)만 error로 센다. 400은 LLM은 응답했지만 입력 정보가 부족한 경우다.
            String outcome = e.getStatus().is5xxServerError()
                    ? AiMetrics.OUTCOME_ERROR
                    : AiMetrics.OUTCOME_SUCCESS;
            metrics.record(AiMetrics.TYPE_DRAFT, outcome, start);
            throw e;
        }
    }

    private AiDraftDto requestDraft(String prompt) {
        String result;
        try {
            result = draftChat.call(systemPrompt(), prompt);
        } catch (RuntimeException e) {
            if (isUnsupportedValue(e)) {
                // AI가 enum에 없는 값(예: SQUASH)을 넘기면 도구 인자 변환 단계에서 실패한다.
                throw AiException.unsupportedSport();
            }
            log.warn("AI 초안 생성 LLM 호출 실패: {}", e.getClass().getSimpleName());
            throw AiException.unavailable();
        }

        AiDraftDto draft = toDraft(result);
        if (draft == null || hasNoMatchInfo(draft)) {
            throw AiException.draftInsufficient();
        }
        return draft;
    }

    private String systemPrompt() {
        return """
                당신은 운동 매칭 서비스의 매치 개설 도우미입니다.

                ## 규칙
                1. 사용자의 문장에서 새로 개설할 매치 정보를 정리해 draftMatch 도구를 호출한다.
                2. 종목, 지역, 일시, 인원, 실력은 문장에 있는 것만 채운다. 추측하지 않는다.
                3. 지역은 서울시 자치구 이름으로 변환한다. 예: 강남역 → 강남구, 잠실 → 송파구
                4. 날짜는 직접 계산하지 않고 아래 날짜표에서 찾아 사용한다.
                5. 시각은 24시간제로 변환한다. 예: 저녁 7시 → 19:00
                6. 날짜만 있고 시각을 말하지 않았으면 시작 시각은 00:00으로 한다.
                7. 종료 시각을 말하지 않았으면 endAt은 채우지 않는다.
                8. 성별 구성은 남성만, 여성만처럼 문장에 있을 때만 채운다.
                9. title은 지역, 종목, 분위기를 담아 30자 안팎으로 작성한다.
                10. description은 참가자에게 보내는 친근한 안내를 2~3문장으로 작성한다.
                    문장에 없는 사실(가격, 시설, 준비물 등)은 지어내지 않는다.

                ## 날짜표
                %s
                """.formatted(PromptDateTable.of(PromptDateTable.today()));
    }

    // 폼에 바로 넣을 수 있게 보정: 종료 시각 기본값(시작 + 2시간), 성별 기본값(MIXED), 글자 수 제한
    static AiDraftDto complete(AiDraftDto draft) {
        LocalDateTime startAt = draft.getStartAt();
        LocalDateTime endAt = draft.getEndAt();
        if (startAt != null && (endAt == null || !endAt.isAfter(startAt))) {
            // 시각을 말하지 않아 00:00이면 종료도 00:00으로 둬서 사용자가 폼에서 직접 고치게 한다.
            endAt = startAt.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? startAt
                    : startAt.plusHours(DEFAULT_MATCH_HOURS);
        }
        GenderGroup genderGroup = draft.getGenderGroup() != null ? draft.getGenderGroup() : GenderGroup.MIXED;
        return AiDraftDto.builder()
                .sportType(draft.getSportType()).region(draft.getRegion())
                .startAt(startAt).endAt(endAt)
                .maxParticipant(draft.getMaxParticipant()).skillLevel(draft.getSkillLevel())
                .genderGroup(genderGroup)
                .title(cut(draft.getTitle(), TITLE_MAX))
                .description(cut(draft.getDescription(), DESCRIPTION_MAX))
                .build();
    }

    // Spring AI가 도구 인자를 종목(SportType)으로 바꾸다 실패하면 원인 어딘가에 IllegalArgumentException이 있다.
    // 다른 enum(실력·성별 등) 변환 오류는 종목 안내가 맞지 않으므로 여기서 거르지 않는다.
    static boolean isUnsupportedValue(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException
                    && t.getMessage() != null
                    && t.getMessage().contains("No enum constant")
                    && t.getMessage().contains("SportType.")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // tool이 호출되면 초안 JSON, 호출되지 않으면 AI의 안내 문장이 온다.
    private AiDraftDto toDraft(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(result, AiDraftDto.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // 명세: 종목·지역·날짜·인원 중 하나도 없으면 정보 부족. 실력·제목·설명만 있으면 초안으로 보지 않는다.
    private boolean hasNoMatchInfo(AiDraftDto draft) {
        return draft.getSportType() == null
                && draft.getRegion() == null
                && draft.getStartAt() == null
                && draft.getMaxParticipant() == null;
    }

    private static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
