package com.example.sporty.match;

import com.example.sporty.match.integration.MatchDependencyAdvice.Error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 다른 Advice가 처리하지 않은 예외를 { code, message }로 답한다. 가장 마지막에 돌도록 우선순위를 가장 낮게 둔다.
 * Spring MVC 표준 예외(404·405, ResponseStatusException 등)와 형식 오류(400 INVALID_INPUT)는 원래 상태 코드를 지킨다.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class MatchFallbackAdvice {
    private static final Logger log = LoggerFactory.getLogger(MatchFallbackAdvice.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> handleUnexpected(Exception e) {
        // 경로·쿼리 타입 오류, JSON 형식 오류, 필수 파라미터 없음은 400 INVALID_INPUT (명세 공통 규칙 ③)
        if (e instanceof MethodArgumentTypeMismatchException || e instanceof HttpMessageNotReadableException
                || e instanceof MissingServletRequestParameterException) {
            return ResponseEntity.badRequest().body(new Error("INVALID_INPUT", "입력 형식이 올바르지 않습니다."));
        }
        if (e instanceof org.springframework.web.ErrorResponse standard) {
            HttpStatus status = HttpStatus.valueOf(standard.getStatusCode().value());
            String message = switch (status) {
                case NOT_FOUND -> "요청한 경로를 찾을 수 없습니다.";
                case METHOD_NOT_ALLOWED -> "지원하지 않는 요청 방식입니다.";
                default -> "요청을 처리할 수 없습니다.";
            };
            return ResponseEntity.status(status).body(new Error(status.name(), message));
        }
        log.error("처리하지 않은 예외", e);
        return ResponseEntity.internalServerError()
                .body(new Error("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
