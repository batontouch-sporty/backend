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

    // A23: LLM 실패·타임아웃. 초안은 LLM 없이 만들 수 없어 Fallback 대신 503
    public static AiException unavailable() {
        return new AiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "직접 입력해 주세요");
    }
}
