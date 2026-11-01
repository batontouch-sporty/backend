package com.example.sporty.user.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 개발·시연용 관리자 초기 계정(admin.account.*). 값은 .env의 ADMIN_EMAIL·ADMIN_PASSWORD·ADMIN_NICKNAME에서 온다.
 * 이메일·비밀번호가 둘 다 있을 때만 계정을 만든다.
 */
@ConfigurationProperties(prefix = "admin.account")
public record AdminAccountProperties(String email, String password, String nickname) {

    // toString에 비밀번호가 찍히지 않게 한다.
    @Override
    public String toString() {
        return "AdminAccountProperties[email=" + email + ", nickname=" + nickname + "]";
    }
}
