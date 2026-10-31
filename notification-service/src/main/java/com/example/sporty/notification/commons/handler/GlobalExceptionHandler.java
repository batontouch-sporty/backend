package com.example.sporty.notification.commons.handler;

import com.example.sporty.notification.commons.exception.notifications.NotificationForbiddenException;
import com.example.sporty.notification.commons.exception.notifications.NotificationNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotificationNotFound(NotificationNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(NotificationForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleNotificationForbidden(NotificationForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, "NOTIFICATION_FORBIDDEN", e.getMessage());
    }

    // 경로의 알림 ID가 숫자가 아닐 때
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getName() + " 값의 형식이 올바르지 않습니다.");
    }

    // Gateway를 거치지 않아 X-User-Id가 없으면 비로그인 요청으로 본다.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    }

    // 위에서 처리하지 않은 예외. Spring MVC 표준 예외(404·405 등)는 원래 상태 코드를 지키고,
    // 나머지는 내부 메시지를 숨긴 채 500으로 답한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
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
                .body(ErrorResponse.builder()
                        .code(code)
                        .message(message)
                        .build());
    }

}
