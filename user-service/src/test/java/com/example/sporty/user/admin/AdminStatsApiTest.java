package com.example.sporty.user.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.Role;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.withdrawal.domain.entity.SagaStatus;
import com.example.sporty.user.withdrawal.domain.entity.WithdrawalSagaEntity;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.example.sporty.user.withdrawal.repository.ProcessedEventRepository;
import com.example.sporty.user.withdrawal.repository.WithdrawalSagaRepository;

/**
 * A28 관리자 통계. 프론트(adminApi.js getUserStats)는 8개 값이 모두 0 이상 정수여야 화면을 그린다.
 * 다른 테스트와 숫자가 섞이지 않게 H2 DB를 따로 쓰고, 매 테스트 전에 비운다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user_admin_stats_test;MODE=MariaDB;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AdminStatsApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired WithdrawalSagaRepository sagas;
    @Autowired OutboxEventRepository outbox;
    @Autowired ProcessedEventRepository processed;
    @MockitoBean RefreshTokenService refreshTokens;
    UserEntity admin;

    @BeforeEach
    void setUp() {
        processed.deleteAllInBatch();
        outbox.deleteAllInBatch();
        sagas.deleteAllInBatch();
        users.deleteAllInBatch();
        admin = user(UserStatus.ACTIVE, Role.ADMIN);
    }

    // 아무 Saga도 없을 때도 모든 값이 0으로 나온다
    @Test
    void emptyStatsAreAllZero() throws Exception {
        stats("ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawalSaga.requested").value(0))
                .andExpect(jsonPath("$.withdrawalSaga.succeeded").value(0))
                .andExpect(jsonPath("$.withdrawalSaga.rejected").value(0))
                .andExpect(jsonPath("$.withdrawalSaga.timedOut").value(0))
                .andExpect(jsonPath("$.withdrawalSaga.inProgress").value(0))
                .andExpect(jsonPath("$.users.active").value(1))
                .andExpect(jsonPath("$.users.withdrawing").value(0))
                .andExpect(jsonPath("$.users.withdrawn").value(0));
    }

    // Saga 상태별·회원 상태별 건수
    @Test
    void countsSagasAndUsersByStatus() throws Exception {
        saga(user(UserStatus.WITHDRAWN, Role.USER), SagaStatus.CLEANED);
        saga(user(UserStatus.WITHDRAWN, Role.USER), SagaStatus.CLEANED);
        UserEntity rejectedThenActive = user(UserStatus.ACTIVE, Role.USER);
        saga(rejectedThenActive, SagaStatus.REJECTED);
        saga(rejectedThenActive, SagaStatus.TIMED_OUT);
        saga(user(UserStatus.WITHDRAWING, Role.USER), SagaStatus.PROCESSING);

        stats("ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawalSaga.requested").value(5))
                .andExpect(jsonPath("$.withdrawalSaga.succeeded").value(2))
                .andExpect(jsonPath("$.withdrawalSaga.rejected").value(1))
                .andExpect(jsonPath("$.withdrawalSaga.timedOut").value(1))
                .andExpect(jsonPath("$.withdrawalSaga.inProgress").value(1))
                .andExpect(jsonPath("$.users.active").value(2))
                .andExpect(jsonPath("$.users.withdrawing").value(1))
                .andExpect(jsonPath("$.users.withdrawn").value(2));
    }

    // ADMIN이 아니면 403, 로그인 헤더가 없으면 401
    @Test
    void nonAdminIsForbiddenAndMissingLoginIsUnauthorized() throws Exception {
        stats("USER").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/stats").header(AuthHeaders.USER_ID, admin.getId()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/stats").header(AuthHeaders.USER_ROLE, "ADMIN"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private ResultActions stats(String role) throws Exception {
        return mvc.perform(get("/api/admin/stats")
                .header(AuthHeaders.USER_ID, admin.getId())
                .header(AuthHeaders.USER_ROLE, role));
    }

    private UserEntity user(UserStatus status, Role role) {
        return users.save(UserEntity.builder().email(UUID.randomUUID().toString().substring(0, 20) + "@test.io")
                .password("encoded").gender(Gender.MALE).status(status).role(role).build());
    }

    private void saga(UserEntity user, SagaStatus status) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 1, 3, 0);
        WithdrawalSagaEntity saga = WithdrawalSagaEntity.start(UUID.randomUUID().toString(), user.getId(),
                UUID.randomUUID().toString(), now, now.plusMinutes(5));
        if (status != SagaStatus.PROCESSING) {
            saga.finish(status, null, now.plusMinutes(1));
        }
        sagas.save(saga);
    }

}
