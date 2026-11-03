package com.example.sporty.user.points;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 00시 해제를 놓친 정지를 기동 직후와 조회(포인트·이력·I1)에서 따라잡는다. 해제 시 현재 점수와 관계없이 30점.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PointExpiryCatchUpTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PointHistoryRepository history;
    @Autowired ConfigurableApplicationContext context;
    @MockitoBean RefreshTokenService refreshTokens;

    private long suspended(int balance, LocalDateTime until) {
        UserEntity user = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@x.test")
                .password("test").gender(Gender.MALE).points(balance).build());
        user.suspendUntil(until);
        return users.saveAndFlush(user).getId();
    }

    private long overdue(int balance) { return suspended(balance, LocalDateTime.now().minusDays(2)); }

    private void assertRestored(long uid) {
        UserEntity user = users.findById(uid).orElseThrow();
        assertThat(user.getPoints()).isEqualTo(30);
        assertThat(user.getSuspendedUntil()).isNull();
        assertThat(history.findByUserIdOrderByCreatedAtDescIdDesc(uid, Pageable.unpaged()))
                .filteredOn(h -> h.getReason() == PointHistory.Reason.SUSPENSION_EXPIRED).hasSize(1);
    }

    @Test
    void startupCatchesUpMissedExpiry() {
        long uid = overdue(0);
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        assertRestored(uid);
    }

    @Test
    void pointBalanceExpiresFirst() throws Exception {
        long uid = overdue(45);
        mvc.perform(get("/api/users/me/points").header(AuthHeaders.USER_ID, uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(30))
                .andExpect(jsonPath("$.participationSuspended").value(false));
        mvc.perform(get("/api/users/me/points").header(AuthHeaders.USER_ID, uid)).andExpect(status().isOk());
        assertRestored(uid);
    }

    @Test
    void pointHistoryExpiresFirst() throws Exception {
        long uid = overdue(0);
        mvc.perform(get("/api/users/me/points/history").header(AuthHeaders.USER_ID, uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reason").value("SUSPENSION_EXPIRED"));
        assertRestored(uid);
    }

    @Test
    void internalUserLookupExpiresFirst() throws Exception {
        long uid = overdue(0);
        mvc.perform(get("/internal/users/{id}", uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationSuspended").value(false));
        assertRestored(uid);
    }

    @Test
    void activeSuspensionIsKept() throws Exception {
        long uid = suspended(0, LocalDateTime.now().plusDays(3));
        mvc.perform(get("/api/users/me/points").header(AuthHeaders.USER_ID, uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(0))
                .andExpect(jsonPath("$.participationSuspended").value(true));
    }
}
