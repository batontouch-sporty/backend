package com.example.sporty.ai.commons.handler;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.sporty.ai.commons.exception.AiException;

@Slf4j
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

    // Gateway가 넣어 주는 X-User-Id가 없음(로그인하지 않고 내부로 직접 호출한 경우)
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingUser(MissingRequestHeaderException e) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    }

    // 위에서 처리하지 않은 예외. Spring MVC 표준 예외(404·405 등)와 형식 오류(400 INVALID_INPUT)는 원래 상태 코드를 지키고,
    // 나머지는 내부 메시지를 숨긴 채 500으로 답한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 경로·쿼리 타입 오류, JSON 형식 오류, 필수 파라미터 없음은 400 INVALID_INPUT (명세 공통 규칙 ③)
        if (e instanceof MethodArgumentTypeMismatchException || e instanceof HttpMessageNotReadableException
                || e instanceof MissingServletRequestParameterException) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력 형식이 올바르지 않습니다.");
        }
        if (e instanceof org.springframework.web.ErrorResponse standard) {
            HttpStatus status = HttpStatus.valueOf(standard.getStatusCode().value());
            String message = switch (status) {
                case NOT_FOUND -> "요청한 경로를 찾을 수 없습니다.";
                case METHOD_NOT_ALLOWED -> "지원하지 않는 요청 방식입니다.";
                default -> "요청을 처리할 수 없습니다.";
            };
            return error(status, status.name(), message);
        }
        log.error("처리하지 않은 예외", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.builder().code(code).message(message).build());
    }
}
