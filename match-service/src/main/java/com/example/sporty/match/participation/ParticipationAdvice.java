package com.example.sporty.match.participation;

import com.example.sporty.match.withdrawal.MemberLockTimeoutException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ParticipationController.class)
public class ParticipationAdvice {
    public record Error(String code, String message) {}

    @ExceptionHandler(MemberLockTimeoutException.class)
    ResponseEntity<Error> memberBusy(MemberLockTimeoutException ex) {
        return ResponseEntity.status(409)
                .body(new Error("MEMBER_OPERATION_IN_PROGRESS", "처리 중인 요청이 있습니다. 잠시 후 다시 시도해주세요."));
    }

    @ExceptionHandler(ParticipationProblem.class)
    ResponseEntity<Error> problem(ParticipationProblem ex) {
        return ResponseEntity.status(ex.status()).body(new Error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Error> invalidId(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new Error("INVALID_INPUT", "ID 형식이 올바르지 않습니다."));
    }
}
