package com.example.sporty.ai.commons.exception;

import org.springframework.http.HttpStatus;

import com.example.sporty.ai.util.SupportedSports;

import lombok.Getter;

/** AI API 에러. 코드와 메시지는 API 명세서 A22~A24 결정을 따른다. */
@Getter
public class AiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    // A22: 검색 조건을 하나도 못 찾음(LLM이 정상 응답한 경우만)
    public static AiException conditionNotFound() {
        return new AiException(HttpStatus.BAD_REQUEST, "AI_CONDITION_NOT_FOUND",
                "검색 조건을 찾지 못했습니다. 종목, 지역, 날짜 중 하나 이상을 포함해 주세요. (예: 이번 주말 강남에서 풋살)");
    }

    // A24: LLM도 실패하고 최신순 Fallback까지 실패했을 때만
    public static AiException recommendFailed() {
        return new AiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_RECOMMEND_FAILED",
                "추천 매치를 만들지 못했습니다. 잠시 후 다시 시도해주세요.");
    }

    // A24: 회원 없음(user-service I1이 404)
    public static AiException userNotFound() {
        return new AiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "회원을 찾을 수 없습니다.");
    }

    // A23: 매치 정보를 하나도 못 찾음
    public static AiException draftInsufficient() {
        return new AiException(HttpStatus.BAD_REQUEST, "AI_DRAFT_INSUFFICIENT",
                "만들 매치 정보를 찾지 못했습니다. 종목, 지역, 날짜, 인원 중 하나 이상을 포함해 주세요. "
                        + "(예: 토요일 저녁 7시 송파에서 농구 10명)");
    }

    // A22·A23: 지원하지 않는 종목
    public static AiException unsupportedSport() {
        return new AiException(HttpStatus.BAD_REQUEST, "AI_UNSUPPORTED_SPORT",
                SupportedSports.unsupportedMessage());
    }

    // A23: LLM 실패·타임아웃(초안은 LLM 없이 만들 수 없음), A22: match 호출 실패
    public static AiException unavailable() {
        return new AiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "직접 입력해 주세요");
    }
}
