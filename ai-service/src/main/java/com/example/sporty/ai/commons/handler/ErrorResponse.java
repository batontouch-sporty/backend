package com.example.sporty.ai.commons.handler;

import lombok.Builder;
import lombok.Getter;

/** 에러 본문 { code, message } */
@Builder
@Getter
public class ErrorResponse {

    private String code;
    private String message;
}
