package com.example.sporty.facility.commons.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.sporty.facility.commons.exception.InvalidSearchConditionException;
import com.example.sporty.facility.commons.exception.ServiceNotFoundException;

/**
 * 시설 API(A20·A21)와 내부 API(I3)가 함께 쓰는 에러 응답. 본문은 { code, message }.
 */
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

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.builder().code(code).message(message).build());
    }
}
