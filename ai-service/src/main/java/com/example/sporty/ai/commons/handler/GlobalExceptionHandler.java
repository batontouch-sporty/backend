package com.example.sporty.ai.commons.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.sporty.ai.commons.exception.AiException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiException.class)
    public ResponseEntity<ErrorResponse> handleAi(AiException e) {
        return error(e.getStatus(), e.getCode(), e.getMessage());
    }

    // 빈 입력·500자 초과 → 400 INVALID_INPUT (명세 결정)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInput(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("요청 내용을 입력해주세요.");
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    // 본문이 없거나 JSON이 아님
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "요청 내용을 입력해주세요.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.builder().code(code).message(message).build());
    }
}
