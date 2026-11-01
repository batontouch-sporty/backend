package com.example.sporty.user.commons.exception.auth;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("권한이 없습니다.");
    }
}
