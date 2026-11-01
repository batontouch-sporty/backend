package com.example.sporty.user.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.profiles.repository.ProfileRepository;
import com.example.sporty.user.users.repository.UserRepository;

/**
 * 동시에 같은 이메일·닉네임으로 요청하면 둘 다 "중복 없음" 검사를 통과한 뒤 DB 유니크 제약에서 걸린다.
 * 검사 결과를 false로 바꿔 그 상황을 만들고, 500이 아니라 409가 나오는지 본다.
 * 서비스 트랜잭션이 실제로 롤백되는지 보려고 테스트 트랜잭션은 쓰지 않는다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user_race_test;MODE=MariaDB;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DuplicateRaceTest {
    static final String PASSWORD = "TestPass123!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean ProfileRepository profiles;
    @MockitoBean RefreshTokenService refreshTokens;

    @Test
    void signupWithEmailTakenAfterCheckIsDuplicateEmailNot500() throws Exception {
        String email = email();
        signup(email, nickname()).andExpect(status().isCreated());
        long before = users.count();

        doReturn(false).when(users).existsByEmail(email);
        signup(email, nickname())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));

        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void signupWithNicknameTakenAfterCheckIsDuplicateNicknameAndRollsBackUser() throws Exception {
        String nickname = nickname();
        signup(email(), nickname).andExpect(status().isCreated());
        long before = users.count();

        doReturn(false).when(profiles).existsByNickname(nickname);
        String email = email();
        signup(email, nickname)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));

        // 먼저 저장된 회원 행도 함께 롤백된다
        assertThat(users.count()).isEqualTo(before);
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void profileUpdateWithNicknameTakenAfterCheckIsDuplicateNicknameNot500() throws Exception {
        String taken = nickname();
        signup(email(), taken).andExpect(status().isCreated());
        String email = email();
        signup(email, nickname()).andExpect(status().isCreated());
        Long userId = users.findByEmail(email).orElseThrow().getId();

        doReturn(false).when(profiles).existsByNicknameAndIdNot(anyString(), anyLong());
        mvc.perform(put("/api/profiles/me").header(AuthHeaders.USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", taken, "district", "MAPO",
                                "sportTypes", List.of("TENNIS")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
    }

    private ResultActions signup(String email, String nickname) throws Exception {
        return mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD, "nickname", nickname,
                        "gender", "MALE", "district", "GANGNAM", "sportTypes", List.of("FUTSAL")))));
    }

    private static String email() { return UUID.randomUUID().toString().substring(0, 20) + "@test.io"; }

    private static String nickname() { return "n-" + UUID.randomUUID().toString().substring(0, 12); }
}
