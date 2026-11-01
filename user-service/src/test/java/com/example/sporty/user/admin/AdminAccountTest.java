package com.example.sporty.user.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.sporty.user.admin.service.AdminAccountService;
import com.example.sporty.user.admin.service.AdminAccountService.Result;
import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.profiles.repository.ProfileRepository;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.Role;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;

/**
 * 개발·시연용 관리자 초기 계정. 기동할 때 admin.account.* 값으로 한 번 만들어지는지,
 * 그 계정으로 로그인해 관리자 화면이 쓰는 A2·A28을 부를 수 있는지, 이미 있거나 값이 이상하면 아무것도 바꾸지 않는지 본다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_admin_account_test;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "admin.account.email=boot-admin@test.io",
        "admin.account.password=AdminPass123!",
        "admin.account.nickname=부트관리자"
})
@AutoConfigureMockMvc
class AdminAccountTest {
    @Autowired MockMvc mvc;
    @Autowired AdminAccountService service;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired PasswordEncoder passwords;
    @MockitoBean RefreshTokenService refreshTokens;

    // 기동할 때 ADMIN 계정과 프로필이 만들어지고, 그 계정으로 로그인·A2·A28이 된다
    @Test
    void adminIsCreatedAtStartupAndCanUseAdminScreenApis() throws Exception {
        UserEntity admin = users.findByEmail("boot-admin@test.io").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwords.matches("AdminPass123!", admin.getPassword())).isTrue();
        ProfileEntity profile = profiles.findByUserId(admin.getId()).orElseThrow();
        assertThat(profile.getNickname()).isEqualTo("부트관리자");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"boot-admin@test.io\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get("/api/users/me").header(AuthHeaders.USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get("/api/admin/stats").header(AuthHeaders.USER_ID, admin.getId())
                        .header(AuthHeaders.USER_ROLE, "ADMIN"))
                .andExpect(status().isOk());
    }

    // 다시 기동해도(같은 값으로 다시 불러도) 늘지 않는다
    @Test
    void runningAgainDoesNotCreateAnotherAdmin() {
        long before = users.count();
        assertThat(service.ensureAdmin("boot-admin@test.io", "AdminPass123!", "부트관리자")).isEqualTo(Result.SKIPPED_EXISTS);
        assertThat(users.count()).isEqualTo(before);
    }

    // 이메일·비밀번호 중 하나라도 없으면 만들지 않는다
    @Test
    void missingEmailOrPasswordCreatesNothing() {
        long before = users.count();
        assertThat(service.ensureAdmin("", "AdminPass123!", null)).isEqualTo(Result.SKIPPED_NOT_CONFIGURED);
        assertThat(service.ensureAdmin(email(), " ", null)).isEqualTo(Result.SKIPPED_NOT_CONFIGURED);
        assertThat(service.ensureAdmin(null, null, null)).isEqualTo(Result.SKIPPED_NOT_CONFIGURED);
        assertThat(users.count()).isEqualTo(before);
    }

    // 가입 규칙에 맞지 않는 값이면 만들지 않는다
    @Test
    void invalidValuesCreateNothing() {
        long before = users.count();
        assertThat(service.ensureAdmin("not-an-email", "AdminPass123!", null)).isEqualTo(Result.SKIPPED_INVALID);
        assertThat(service.ensureAdmin(email(), "short", null)).isEqualTo(Result.SKIPPED_INVALID);
        assertThat(service.ensureAdmin(email(), "x".repeat(21), null)).isEqualTo(Result.SKIPPED_INVALID);
        assertThat(service.ensureAdmin(email(), "AdminPass123!", "n".repeat(51))).isEqualTo(Result.SKIPPED_INVALID);
        assertThat(users.count()).isEqualTo(before);
    }

    // 가입·로그인의 @Email이 거부하는 이메일로는 만들지 않는다(만들면 로그인 400으로 쓸 수 없는 계정이 됨)
    @Test
    void emailRejectedBySignupAndLoginValidationCreatesNothing() throws Exception {
        String email = "admin@demo..example.com";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isBadRequest());

        assertThat(service.ensureAdmin(email, "AdminPass123!", null)).isEqualTo(Result.SKIPPED_INVALID);
        assertThat(users.findByEmail(email)).isEmpty();
    }

    // 같은 이메일의 일반 회원이 있으면 관리자로 올리거나 비밀번호를 바꾸지 않는다
    @Test
    void existingUserWithSameEmailIsNotPromoted() {
        String email = email();
        UserEntity user = users.save(UserEntity.builder().email(email).password(passwords.encode("UserPass123!"))
                .gender(Gender.FEMALE).build());

        assertThat(service.ensureAdmin(email, "AdminPass123!", null)).isEqualTo(Result.SKIPPED_EXISTS);

        UserEntity reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(Role.USER);
        assertThat(passwords.matches("UserPass123!", reloaded.getPassword())).isTrue();
    }

    // 닉네임이 이미 쓰이면 만들지 않고, 닉네임을 비우면 기본 "관리자"
    @Test
    void takenNicknameCreatesNothingAndBlankNicknameUsesDefault() {
        long before = users.count();
        assertThat(service.ensureAdmin(email(), "AdminPass123!", "부트관리자")).isEqualTo(Result.SKIPPED_NICKNAME_TAKEN);
        assertThat(users.count()).isEqualTo(before);

        String email = email();
        assertThat(service.ensureAdmin(email, "AdminPass123!", " ")).isEqualTo(Result.CREATED);
        UserEntity admin = users.findByEmail(email).orElseThrow();
        assertThat(profiles.findByUserId(admin.getId()).orElseThrow().getNickname()).isEqualTo("관리자");
    }

    private static String email() {
        return UUID.randomUUID().toString().substring(0, 20) + "@test.io";
    }
}
