package com.example.sporty.user.commons.token;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import com.example.sporty.user.commons.exception.auth.InvalidRefreshTokenException;
import com.example.sporty.user.users.domain.entity.Role;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class JwtProvider {
    private static final Duration ACCESS_TOKEN_EXP = Duration.ofMinutes(30);
    private static final Duration REFRESH_TOKEN_EXP = Duration.ofDays(7);
    private final Key key;

    public JwtProvider(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // Gateway가 관리자 경로를 거를 수 있도록 AT에만 role 클레임을 넣는다.
    public String createAccessToken(Long id, Role role) {
        if (role == null) {
            throw new IllegalArgumentException("토큰 발급에 회원 역할이 필요합니다.");
        }
        return createToken(id, "ACCESS", ACCESS_TOKEN_EXP, role);
    }

    public String createRefreshToken(Long id) {
        return createToken(id, "REFRESH", REFRESH_TOKEN_EXP, null);
    }

    // [USR-05] 서명/만료/RT 타입을 검증한 뒤 회원 ID를 반환
    public Long validateRefreshToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build()
                    .parseClaimsJws(token).getBody();
            if (!"REFRESH".equals(claims.get("tokenType", String.class)) || claims.getExpiration() == null) {
                throw new InvalidRefreshTokenException();
            }
            Long userId = Long.valueOf(claims.getSubject());
            if (userId <= 0) throw new InvalidRefreshTokenException();
            return userId;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidRefreshTokenException();
        }
    }

    public long getAccessTokenExpirationSeconds() {
        return ACCESS_TOKEN_EXP.toSeconds();
    }

    public long getRefreshTokenExpirationSeconds() {
        return REFRESH_TOKEN_EXP.toSeconds();
    }

    private String createToken(Long id, String tokenType, Duration validity, Role role) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("토큰 발급에 유효한 회원 ID가 필요합니다.");
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .setSubject(id.toString())
                .setId(UUID.randomUUID().toString())
                .claim("tokenType", tokenType)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(validity)));
        if (role != null) {
            builder.claim("role", role.name());
        }
        return builder.signWith(key).compact();
    }

}
