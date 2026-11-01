package com.example.sporty.user.commons.handler;

import com.example.sporty.user.commons.exception.auth.ForbiddenException;
import com.example.sporty.user.commons.exception.auth.InvalidRefreshTokenException;
import com.example.sporty.user.commons.exception.auth.LoginFailException;
import com.example.sporty.user.commons.exception.profiles.ProfileNotFoundException;
import com.example.sporty.user.commons.exception.users.DuplicateEmailException;
import com.example.sporty.user.commons.exception.users.DuplicateNicknameException;
import com.example.sporty.user.commons.exception.users.PasswordMismatchException;
import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.commons.exception.users.WithdrawalInProgressException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException e) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", e.getMessage());
    }

    @ExceptionHandler(DuplicateNicknameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateNickname(DuplicateNicknameException e) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_NICKNAME", e.getMessage());
    }

    @ExceptionHandler(LoginFailException.class)
    public ResponseEntity<ErrorResponse> handleLoginFail(LoginFailException e) {
        return error(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", e.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", e.getMessage());
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProfileNotFound(ProfileNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(WithdrawalInProgressException.class)
    public ResponseEntity<ErrorResponse> handleWithdrawalInProgress(WithdrawalInProgressException e) {
        return error(HttpStatus.CONFLICT, "WITHDRAWAL_IN_PROGRESS", e.getMessage());
    }

    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePasswordMismatch(PasswordMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", e.getMessage());
    }

    // @Valid 실패는 첫 번째 항목의 오류 메시지만 돌려준다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInput(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("입력값이 올바르지 않습니다.");
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    // JSON 문법 오류, 없는 enum 값 등
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "요청 본문 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getName() + " 값의 형식이 올바르지 않습니다.");
    }

    // Gateway를 거치지 않아 X-User-Id가 없으면 비로그인 요청으로 본다.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    }

    // 위에서 처리하지 않은 예외(Redis·DB 장애 등). Spring MVC 표준 예외(404·405 등)는 원래 상태 코드를 지키고,
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
