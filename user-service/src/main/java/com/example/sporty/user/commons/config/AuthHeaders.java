package com.example.sporty.user.commons.config;

// Gateway가 JWT를 검증한 뒤 회원 ID를 이 헤더로 넘긴다. 클라이언트가 보낸 값은 Gateway가 지운다.
public final class AuthHeaders {
    private AuthHeaders() {}

    public static final String USER_ID = "X-User-Id";
}
