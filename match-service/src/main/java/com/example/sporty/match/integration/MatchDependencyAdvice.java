package com.example.sporty.match.integration;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class MatchDependencyAdvice {
    public record Error(String code, String message) {}

    @ExceptionHandler(MatchDependencyException.class)
    public ResponseEntity<Error> handle(MatchDependencyException ex) {
        return ResponseEntity.status(ex.status()).body(new Error(ex.code(), ex.getMessage()));
    }
}
