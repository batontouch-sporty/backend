package com.example.sporty.user.commons.exception.users;

public class PasswordMismatchException extends RuntimeException {

    public PasswordMismatchException() {
        super("비밀번호가 일치하지 않습니다.");
    }
}
