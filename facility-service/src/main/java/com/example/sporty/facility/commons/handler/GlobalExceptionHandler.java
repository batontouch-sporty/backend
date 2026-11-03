package com.example.sporty.facility.commons.handler;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.sporty.facility.commons.exception.InvalidSearchConditionException;
import com.example.sporty.facility.commons.exception.ServiceNotFoundException;

/**
 * 시설 API(A20·A21)와 내부 API(I3)가 함께 쓰는 에러 응답. 본문은 { code, message }.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleServiceNotFound(ServiceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", e.getMessage());
    }

    // 2차 결정 14: startTime > endTime도 400 (1차는 핸들러가 없어 500)
    @ExceptionHandler(InvalidSearchConditionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSearchCondition(InvalidSearchConditionException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_CONDITION", e.getMessage());
    }

    // 시간 형식이 HH:mm이 아닌 검색 조건
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCondition(BindException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_CONDITION",
                "검색 조건 형식이 올바르지 않습니다. 시간은 HH:mm 형식으로 입력해주세요.");
    }

    // 숫자가 아닌 서비스 ID
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidServiceId(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_SERVICE_ID",
                "서비스 ID 형식이 올바르지 않습니다. 숫자로 입력해주세요.");
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
            // 405의 Allow 같은 표준 응답 헤더는 그대로 넘긴다.
            return ResponseEntity.status(status).headers(standard.getHeaders())
                    .body(error(status, status.name(), message).getBody());
        }
        log.error("처리하지 않은 예외", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.builder().code(code).message(message).build());
    }
}
