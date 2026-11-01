package com.example.sporty.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.profiles.repository.ProfileRepository;
import com.example.sporty.user.sportpreference.domain.entity.SportPreferenceEntity;
import com.example.sporty.user.sportpreference.repository.SportPreferenceRepository;
import com.example.sporty.user.users.domain.entity.Role;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 1차 ApiIntegrationTest의 회원·인증 부분을 옮기고 2차 계약(A1~A8)을 더했다.
 * Gateway 대신 X-User-Id 헤더를 직접 넣어 부른다. 테스트마다 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserApiTest {
    static final String PASSWORD = "TestPass123!";
    static final String SECRET = "test-only-signing-secret-with-at-least-32-bytes";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired SportPreferenceRepository preferences;
    @Autowired PasswordEncoder passwords;
    @MockitoBean RefreshTokenService refreshTokens;

    // A1 회원가입
    @Test
    void signupPersistsEncodedPasswordProfileAndPreferencesAsUser() throws Exception {
        String email = email();
        String nickname = nickname();
        signup(email, nickname).andExpect(status().isCreated());
        reload();
        UserEntity user = users.findByEmail(email).orElseThrow();
        assertThat(user.getPassword()).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, user.getPassword())).isTrue();
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        ProfileEntity profile = profiles.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getNickname()).isEqualTo(nickname);
        assertThat(preferences.findByProfileId(profile.getId())).extracting(SportPreferenceEntity::getSportType)
                .containsExactly(SportType.FUTSAL);
    }

    @Test
    void signupIgnoresRoleInRequestBody() throws Exception {
        var body = signupBody(email(), nickname());
        body.put("role", "ADMIN");
        postJson("/api/users", body).andExpect(status().isCreated());
        reload();
        assertThat(users.findByEmail((String) body.get("email")).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void duplicateEmailAndNicknameReturnConflictWithoutCreatingAnotherUser() throws Exception {
        String email = email();
        String nickname = nickname();
        signup(email, nickname).andExpect(status().isCreated());
        reload();
        long before = users.count();
        signup(email, nickname()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        signup(email(), nickname).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void signupTrimsNicknameAndTreatsPaddedDuplicateAsTaken() throws Exception {
        String nickname = nickname();
        String email = email();
        signup(email, "  " + nickname + "  ").andExpect(status().isCreated());
        reload();
        long userId = users.findByEmail(email).orElseThrow().getId();
        assertThat(profiles.findByUserId(userId).orElseThrow().getNickname()).isEqualTo(nickname);
        signup(email(), " " + nickname).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
    }

    @Test
    void malformedSignupAndLoginReturnInvalidInput() throws Exception {
        postJson("/api/users", Map.of("email", "invalid", "password", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        postJson("/api/auth/login", Map.of("email", "invalid", "password", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        // 없는 enum 값처럼 본문을 읽지 못해도 같은 형식으로 답한다.
        var body = signupBody(email(), nickname());
        body.put("district", "NOWHERE");
        postJson("/api/users", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void signupRejectsMissingNullAndNullElementPreferencesWithoutSavingUser() throws Exception {
        var body = signupBody(email(), nickname());
        body.remove("sportTypes");
        postJson("/api/users", body).andExpect(status().isBadRequest());
        body.put("sportTypes", null);
        postJson("/api/users", body).andExpect(status().isBadRequest());
        body.put("sportTypes", Arrays.asList("FUTSAL", null));
        postJson("/api/users", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(users.existsByEmail((String) body.get("email"))).isFalse();
    }

    @Test
    void signupAllowsEmptyPreferencesAndStoresRepeatedSportsOnlyOnce() throws Exception {
        for (String[] sports : new String[][]{ {}, {"FUTSAL", "FUTSAL"} }) {
            var body = signupBody(email(), nickname());
            body.put("sportTypes", sports);
            postJson("/api/users", body).andExpect(status().isCreated());
            reload();
            long userId = users.findByEmail((String) body.get("email")).orElseThrow().getId();
            long profileId = profiles.findByUserId(userId).orElseThrow().getId();
            assertThat(preferences.findByProfileId(profileId)).hasSize(sports.length == 0 ? 0 : 1);
        }
    }

    // A4 로그인
    @Test
    void loginReturnsRoleAndStoresRefreshToken() throws Exception {
        UserEntity user = user(UserStatus.ACTIVE);
        String body = postJson("/api/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var login = json.readTree(body);
        String refresh = login.get("refreshToken").asText();
        Claims access = parse(login.get("accessToken").asText());
        assertThat(access.getSubject()).isEqualTo(user.getId().toString());
        assertThat(access.get("tokenType", String.class)).isEqualTo("ACCESS");
        assertThat(access.get("role", String.class)).isEqualTo("USER");
        verify(refreshTokens).save(eq(user.getId()), eq(refresh), eq(7L * 24 * 60 * 60));
    }

    @Test
    void loginRejectsUnknownUserWrongPasswordAndInactiveUsers() throws Exception {
        UserEntity active = user(UserStatus.ACTIVE);
        UserEntity withdrawing = user(UserStatus.WITHDRAWING);
        UserEntity withdrawn = user(UserStatus.WITHDRAWN);
        for (String email : new String[]{email(), active.getEmail(), withdrawing.getEmail(), withdrawn.getEmail()}) {
            postJson("/api/auth/login", Map.of("email", email,
                    "password", email.equals(active.getEmail()) ? "WrongPass123!" : PASSWORD))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        }
        verifyNoInteractions(refreshTokens);
    }

    // A5 로그아웃
    @Test
    void logoutDeletesRefreshTokenOfHeaderUser() throws Exception {
        mvc.perform(post("/api/auth/logout").header(AuthHeaders.USER_ID, 7))
                .andExpect(status().isNoContent());
        verify(refreshTokens).delete(7L);
    }

    @Test
    void requestsWithoutUserIdHeaderAreUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profiles/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(profileBody(nickname())))).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(refreshTokens);
    }

    // A2 내 정보
    @Test
    void myInfoIncludesStatusAndRole() throws Exception {
        UserEntity user = user(UserStatus.ACTIVE);
        mvc.perform(get("/api/users/me").header(AuthHeaders.USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.gender").value("MALE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.district").value("GANGNAM"))
                .andExpect(jsonPath("$.preferenceSports[0]").value("FUTSAL"));
        mvc.perform(get("/api/users/me").header(AuthHeaders.USER_ID, user.getId() + 1000))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // A7 프로필 수정 · A8 프로필 조회
    @Test
    void updateProfileReplacesPreferencesAndProfileCanBeRead() throws Exception {
        UserEntity user = user(UserStatus.ACTIVE);
        String nickname = nickname();
        var body = profileBody("  " + nickname + "  ");
        mvc.perform(put("/api/profiles/me").header(AuthHeaders.USER_ID, user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(nickname))
                .andExpect(jsonPath("$.district").value("MAPO"))
                .andExpect(jsonPath("$.preferenceSports.length()").value(2));
        reload();
        long profileId = profiles.findByUserId(user.getId()).orElseThrow().getId();
        mvc.perform(get("/api/profiles/{id}", profileId).header(AuthHeaders.USER_ID, 999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(profileId))
                .andExpect(jsonPath("$.nickname").value(nickname))
                .andExpect(jsonPath("$.preferenceSports.length()").value(2));
        mvc.perform(get("/api/profiles/{id}", profileId + 1000).header(AuthHeaders.USER_ID, 999))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    void updateProfileRejectsNicknameOfAnotherUser() throws Exception {
        UserEntity me = user(UserStatus.ACTIVE);
        UserEntity other = user(UserStatus.ACTIVE);
        String taken = profiles.findByUserId(other.getId()).orElseThrow().getNickname();
        mvc.perform(put("/api/profiles/me").header(AuthHeaders.USER_ID, me.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(profileBody(taken))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
    }

    // A3 탈퇴 (Saga 전 단계: 즉시 WITHDRAWN)
    @Test
    void withdrawalChecksPasswordThenAcceptsSaga() throws Exception {
        UserEntity user = user(UserStatus.ACTIVE);
        mvc.perform(delete("/api/users/me").header(AuthHeaders.USER_ID, user.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        mvc.perform(delete("/api/users/me").header(AuthHeaders.USER_ID, user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"WrongPass123!\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        mvc.perform(delete("/api/users/me").header(AuthHeaders.USER_ID, user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WITHDRAWING"))
                .andExpect(jsonPath("$.sagaId").isNotEmpty());
        reload();
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWING);
        // Refresh Token은 매치 정리(CLEANED)가 끝난 뒤에 지운다.
        verifyNoInteractions(refreshTokens);
    }

    private UserEntity user(UserStatus status) {
        UserEntity user = users.save(UserEntity.builder().email(email()).password(passwords.encode(PASSWORD))
                .gender(com.example.sporty.user.users.domain.entity.Gender.MALE).status(status).build());
        ProfileEntity profile = profiles.save(ProfileEntity.builder().nickname(nickname())
                .district(com.example.sporty.user.profiles.domain.entity.District.GANGNAM).user(user).build());
        preferences.save(SportPreferenceEntity.builder().profile(profile).sportType(SportType.FUTSAL).build());
        reload();
        return user;
    }

    private Map<String, Object> signupBody(String email, String nickname) {
        return new HashMap<>(Map.of("email", email, "password", PASSWORD, "nickname", nickname,
                "gender", "MALE", "district", "GANGNAM", "sportTypes", List.of("FUTSAL")));
    }

    private Map<String, Object> profileBody(String nickname) {
        return Map.of("nickname", nickname, "district", "MAPO", "sportTypes", List.of("TENNIS", "RUNNING", "TENNIS"));
    }

    private ResultActions signup(String email, String nickname) throws Exception {
        return postJson("/api/users", signupBody(email, nickname));
    }

    private ResultActions postJson(String uri, Object body) throws Exception {
        return mvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseClaimsJws(token).getBody();
    }

    private String email() { return UUID.randomUUID().toString().substring(0, 20) + "@test.io"; }

    private String nickname() { return "n-" + UUID.randomUUID().toString().substring(0, 12); }

    private void reload() { em.flush(); em.clear(); }
}
