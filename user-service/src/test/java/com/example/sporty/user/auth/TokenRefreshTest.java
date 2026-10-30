package com.example.sporty.user.auth;

import com.example.sporty.user.auth.controller.AuthController;
import com.example.sporty.user.auth.service.AuthService;
import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.config.PasswordEncoderConfig;
import com.example.sporty.user.commons.token.JwtProvider;
import com.example.sporty.user.users.domain.entity.Role;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 1차 security/TokenRefreshTest를 옮겼다. JWT 필터는 Gateway로 갔으므로 토큰 발급·재발급만 검증한다.
@WebMvcTest(AuthController.class)
@Import({AuthService.class, JwtProvider.class, PasswordEncoderConfig.class})
class TokenRefreshTest {
    static final String SECRET = "test-only-signing-secret-with-at-least-32-bytes";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean RefreshTokenService refreshTokens;

    @Test
    void refreshesWithMatchingRedisTokenAndKeepsRole() throws Exception {
        String rt = tokens.createRefreshToken(1L);
        when(refreshTokens.findByUserId(1L)).thenReturn(rt);
        when(users.findById(1L)).thenReturn(Optional.of(user(UserStatus.ACTIVE, Role.ADMIN)));
        String body = mvc.perform(post("/api/auth/refresh")
                .contentType("application/json").content("{\"refreshToken\":\"" + rt + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").isString()).andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String at = body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        Claims claims = parse(at);
        assertThat(claims.get("tokenType", String.class)).isEqualTo("ACCESS");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        verify(refreshTokens, never()).save(anyLong(), anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"\"}", "{\"refreshToken\":\"bad-token\"}"})
    void rejectsMissingAndMalformedTokens(String body) throws Exception {
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        verifyNoInteractions(refreshTokens, users);
    }

    @Test
    void rejectsAccessToken() throws Exception { reject(tokens.createAccessToken(1L, Role.USER)); }

    @Test
    void rejectsExpiredToken() throws Exception {
        reject(Jwts.builder().setSubject("1").claim("tokenType", "REFRESH")
                .setExpiration(new Date(System.currentTimeMillis() - 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact());
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        reject(new JwtProvider("another-test-signing-secret-with-at-least-32-bytes").createRefreshToken(1L));
    }

    @Test
    void rejectsMissingRedisToken() throws Exception { reject(tokens.createRefreshToken(1L)); }

    @Test
    void rejectsReplacedRedisToken() throws Exception {
        when(refreshTokens.findByUserId(1L)).thenReturn(tokens.createRefreshToken(1L));
        reject(tokens.createRefreshToken(1L));
    }

    // 탈퇴 진행 중(WITHDRAWING)도 탈퇴 회원과 같이 재발급을 막는다.
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"WITHDRAWING", "WITHDRAWN"})
    void rejectsInactiveUser(UserStatus status) throws Exception {
        String rt = tokens.createRefreshToken(1L);
        when(refreshTokens.findByUserId(1L)).thenReturn(rt);
        when(users.findById(1L)).thenReturn(Optional.of(user(status, Role.USER)));
        reject(rt);
    }

    @Test
    // Redis 장애를 401(RT 무효)로 바꾸지 않는다. 프론트가 세션을 지우지 않도록 500 + {code,message}로 답한다.
    void doesNotConvertRedisOutageToInvalidToken() throws Exception {
        String rt = tokens.createRefreshToken(1L);
        when(refreshTokens.findByUserId(1L)).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("test outage"));
        mvc.perform(post("/api/auth/refresh").contentType("application/json")
                        .content("{\"refreshToken\":\"" + rt + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("test outage"))));
    }

    @Test
    void standardMvcErrorsKeepTheirStatus() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/refresh"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/auth/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private UserEntity user(UserStatus status, Role role) {
        return UserEntity.builder().id(1L).status(status).role(role).build();
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseClaimsJws(token).getBody();
    }

    private void reject(String token) throws Exception {
        mvc.perform(post("/api/auth/refresh").contentType("application/json")
                .content("{\"refreshToken\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }
}
