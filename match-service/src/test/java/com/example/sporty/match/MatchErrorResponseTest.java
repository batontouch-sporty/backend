package com.example.sporty.match;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.sporty.match.crud.MatchCrudProblem;
import com.example.sporty.match.crud.MatchCrudService;
import com.example.sporty.match.integration.MatchRemoteChecks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 처리하지 않은 예외는 500 {code, message}로, 기존 4xx와 404·405 같은 표준 상태는 그대로 나가는지 본다.
@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "spring.config.import=",
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:errors;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "match.outbox.enabled=false",
            "match.withdrawal.enabled=false"
        })
@AutoConfigureMockMvc
class MatchErrorResponseTest {
    @Autowired MockMvc mvc;
    @MockitoBean MatchCrudService service;
    @MockitoBean MatchRemoteChecks checks;

    @Test
    void unexpectedErrorReturns500WithoutInternalMessage() throws Exception {
        when(service.detail(anyLong(), any())).thenThrow(new IllegalStateException("db password=secret"));
        mvc.perform(get("/api/matches/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret"))));
    }

    // 범위가 정해진 Advice(MatchCrudAdvice·ParticipationAdvice)가 먼저 처리한다
    @Test
    void existingErrorsKeepTheirCodes() throws Exception {
        when(service.detail(anyLong(), any())).thenThrow(new MatchCrudProblem(404, "MATCH_NOT_FOUND", "없음"));
        mvc.perform(get("/api/matches/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));
        mvc.perform(post("/api/matches/abc/participants").header("X-User-Id", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("ID 형식이 올바르지 않습니다."));
    }

    @Test
    void standardMvcErrorsKeepTheirStatus() throws Exception {
        mvc.perform(patch("/health-check"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 탈퇴 결과 조회의 형식 오류는 Advice가 없는 컨트롤러에서도 400 그대로다
    @Test
    void withdrawalResultBadRequestStays400() throws Exception {
        mvc.perform(get("/internal/withdrawals/s1")
                        .param("userId", "1").param("requestEventId", "e1").param("deadlineAt", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/internal/withdrawals/s1")
                        .param("userId", "abc").param("requestEventId", "e1").param("deadlineAt", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/internal/withdrawals/s1").param("userId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
