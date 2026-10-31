package com.example.sporty.user.auth.domain.dto;

import com.example.sporty.user.users.domain.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponseDto {
    private Long userId;
    private Role role;
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    // 토큰 유효 시간(초)
    private long expiresIn;
}
