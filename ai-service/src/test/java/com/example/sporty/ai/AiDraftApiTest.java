package com.example.sporty.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.sporty.ai.agent.DraftChat;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * A23 매치 초안 생성. 1차 AI-01을 옮기고 2차 결정(503 AI_UNAVAILABLE, 에러 코드, facilityCandidates)을 더했다.
 * OpenAI는 DraftChat 목으로 바꿔 실제로 호출하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiDraftApiTest {

    static final String PROMPT = "토요일 저녁 7시 송파에서 농구 10명 모집";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry registry;

    @MockitoBean DraftChat draftChat;

    ResultActions requestDraft(String prompt) throws Exception {
        return mvc.perform(post("/api/ai/matches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("prompt", prompt))));
    }

    double count(String outcome) {
        Timer timer = registry.find("ai.request").tag("type", "draft").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void 초안을_만들고_종료시각과_성별_기본값을_채운다() throws Exception {
        when(draftChat.call(anyString(), anyString())).thenReturn("""
                {"sportType":"BASKETBALL","region":"송파구","startAt":"2026-11-07T19:00:00",
                 "maxParticipant":10,"title":"송파 토요일 저녁 농구 한 판","description":"초보도 환영해요."}
                """);
        double before = count("success");

        requestDraft(PROMPT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialValues.sportType").value("BASKETBALL"))
                .andExpect(jsonPath("$.initialValues.region").value("송파구"))
                .andExpect(jsonPath("$.initialValues.startAt").value("2026-11-07T19:00:00"))
                .andExpect(jsonPath("$.initialValues.endAt").value("2026-11-07T21:00:00"))
                .andExpect(jsonPath("$.initialValues.maxParticipant").value(10))
                .andExpect(jsonPath("$.initialValues.genderGroup").value("MIXED"))
                .andExpect(jsonPath("$.facilityCandidates", hasSize(0)));

        assertThat(count("success")).isEqualTo(before + 1);
    }

    @Test
    void LLM이_실패하면_503_AI_UNAVAILABLE() throws Exception {
        when(draftChat.call(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));
        double before = count("error");

        requestDraft(PROMPT)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("직접 입력해 주세요"));

        assertThat(count("error")).isEqualTo(before + 1);
    }

    @Test
    void 매치_정보를_못_찾으면_400_AI_DRAFT_INSUFFICIENT() throws Exception {
        // 도구가 호출되지 않으면 AI의 안내 문장이 그대로 온다
        when(draftChat.call(anyString(), anyString())).thenReturn("어떤 매치를 만들고 싶으신가요?");

        requestDraft("안녕하세요")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_DRAFT_INSUFFICIENT"));
    }

    @Test
    void 지원하지_않는_종목이면_400_AI_UNSUPPORTED_SPORT() throws Exception {
        when(draftChat.call(anyString(), anyString())).thenThrow(new IllegalStateException(
                "tool call failed",
                new IllegalArgumentException("No enum constant com.example.sporty.ai.domain.enums.SportType.SQUASH")));

        requestDraft("송파에서 스쿼시 4명")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_UNSUPPORTED_SPORT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("농구")));
    }

    @Test
    void 실력만_있는_초안은_400_AI_DRAFT_INSUFFICIENT() throws Exception {
        when(draftChat.call(anyString(), anyString()))
                .thenReturn("{\"skillLevel\":\"BEGINNER\",\"title\":\"초보 환영\"}");

        requestDraft("초보만 오세요")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_DRAFT_INSUFFICIENT"));
    }

    @Test
    void 종목이_아닌_값_변환_오류는_503_AI_UNAVAILABLE() throws Exception {
        when(draftChat.call(anyString(), anyString())).thenThrow(new IllegalArgumentException(
                "No enum constant com.example.sporty.ai.domain.enums.SkillLevel.EXPERT"));

        requestDraft("송파 농구 고수만")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
    }

    @Test
    void 빈_입력은_LLM_호출_없이_400_INVALID_INPUT() throws Exception {
        requestDraft(" ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청 내용을 입력해주세요."));

        verifyNoInteractions(draftChat);
    }

    @Test
    void 입력이_500자를_넘으면_400_INVALID_INPUT() throws Exception {
        requestDraft("가".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청은 500자 이하로 입력해주세요."));
    }

    @Test
    void 제목은_50자_설명은_500자로_자른다() throws Exception {
        when(draftChat.call(anyString(), anyString())).thenReturn(json.writeValueAsString(Map.of(
                "sportType", "FUTSAL",
                "startAt", LocalDateTime.of(2026, 11, 8, 10, 0).toString(),
                "title", "가".repeat(80),
                "description", "나".repeat(600))));

        requestDraft("일요일 오전 10시 풋살")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialValues.title").value("가".repeat(50)))
                .andExpect(jsonPath("$.initialValues.description").value("나".repeat(500)));
    }
}
