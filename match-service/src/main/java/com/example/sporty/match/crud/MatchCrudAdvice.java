package com.example.sporty.match.crud;

import com.example.sporty.match.withdrawal.MemberLockTimeoutException;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = MatchCrudController.class)
public class MatchCrudAdvice {
    public record Error(String code, String message) {}

    @ExceptionHandler(MatchCrudProblem.class)
    ResponseEntity<Error> problem(MatchCrudProblem ex) {
        return ResponseEntity.status(ex.status()).body(new Error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MemberLockTimeoutException.class)
    ResponseEntity<Error> busy(MemberLockTimeoutException ex) {
        return ResponseEntity.status(409)
                .body(new Error("MEMBER_OPERATION_IN_PROGRESS", "처리 중인 요청이 있습니다. 잠시 후 다시 시도해주세요."));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<Error> invalid(BindException ex) {
        FieldError error = ex.getBindingResult().getFieldError();
        return ResponseEntity.badRequest()
                .body(
                        new Error(
                                "INVALID_INPUT",
                                error == null
                                        ? "입력값을 확인해주세요."
                                        : error.getField() + ": " + (error.isBindingFailure()
                                                ? "입력 형식이나 허용된 값을 확인해주세요."
                                                : error.getDefaultMessage())));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Error> malformed(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new Error("INVALID_INPUT", "입력 형식과 날짜·열거형 값을 확인해주세요."));
    }
}
