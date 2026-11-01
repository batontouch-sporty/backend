package com.example.sporty.ai;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.sporty.ai.agent.RecommendChat;
import com.example.sporty.ai.client.MatchClient;
import com.example.sporty.ai.client.UserClient;
import com.example.sporty.ai.client.dto.InternalUserDto;
import com.example.sporty.ai.client.dto.MatchDto;
import com.example.sporty.ai.client.dto.MyMatchDto;
import com.example.sporty.ai.domain.enums.District;
import com.example.sporty.ai.domain.enums.Gender;
import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.domain.enums.MatchStatus;
import com.example.sporty.ai.domain.enums.SportType;

/**
 * A24 맞춤 매치 추천. LLM·user·match는 목으로 바꿔 외부 호출이 없다.
 * 최신순 Fallback은 matchId 내림차순이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiRecommendApiTest {

    static final long USER_ID = 5L;

    @Autowired MockMvc mvc;

    @MockitoBean RecommendChat recommendChat;
    @MockitoBean UserClient userClient;
    @MockitoBean MatchClient matchClient;

    static MatchDto match(long id, SportType sport, String region, GenderGroup gender, int day) {
        return MatchDto.builder().matchId(id).title("매치" + id).sportType(sport).region(region)
                .genderGroup(gender).status(MatchStatus.RECRUITING)
                .startAt(LocalDateTime.of(2026, 11, day, 10, 0))
                .numCurrentParticipant(1).maxParticipant(10).build();
    }

    // 마포구에 사는 여성, 선호 종목 풋살
    static InternalUserDto me() {
        return InternalUserDto.builder().id(USER_ID).gender(Gender.FEMALE).status("ACTIVE")
                .district(District.MAPO).sportTypes(List.of(SportType.FUTSAL)).build();
    }

    List<MatchDto> recruiting() {
        return List.of(
                match(1, SportType.FUTSAL, "강남구", GenderGroup.MIXED, 10),     // 선호 종목
                match(2, SportType.TENNIS, "마포구", GenderGroup.MIXED, 11),     // 같은 자치구(코드 MAPO → 마포구)
                match(3, SportType.FUTSAL, "송파구", GenderGroup.MALE, 12),      // 남성 전용 → 제외
                match(4, SportType.BASKETBALL, "송파구", GenderGroup.MIXED, 13), // 조건 안 맞음 → 제외
                match(5, SportType.FUTSAL, "마포구", GenderGroup.FEMALE, 14),    // 여성 전용, 둘 다 맞음
                match(6, SportType.FUTSAL, "마포구", GenderGroup.MIXED, 15));    // 내가 참가한 매치 → 제외
    }

    ResultActions recommend() throws Exception {
        return mvc.perform(get("/api/ai/matches/recommendations").header("X-User-Id", USER_ID));
    }

    @Test
    void LLM이_고른_순서대로_추천하고_부족하면_후보로_채운다() throws Exception {
        when(userClient.getUser(USER_ID)).thenReturn(me());
        when(matchClient.searchMatches(any())).thenReturn(recruiting());
        when(matchClient.getMyMatches(eq(USER_ID))).thenReturn(List.of(new MyMatchDto(6L)));
        when(recommendChat.call(anyString(), anyString())).thenReturn("{\"matchIds\":[5,99,5]}");

        recommend()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(false))
                .andExpect(jsonPath("$.matches[*].matchId", contains(5, 1, 2)));
    }

    @Test
    void LLM이_후보_ID를_못_고르면_500이_아니라_최신순_fallback() throws Exception {
        when(userClient.getUser(USER_ID)).thenReturn(me());
        when(matchClient.searchMatches(any())).thenReturn(recruiting());
        when(matchClient.getMyMatches(eq(USER_ID))).thenReturn(List.of(new MyMatchDto(6L)));
        when(recommendChat.call(anyString(), anyString())).thenReturn("추천할 매치가 없어요");

        recommend()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.matches[*].matchId", contains(5, 2, 1)));
    }

    @Test
    void LLM이_실패하면_최신순_fallback() throws Exception {
        when(userClient.getUser(USER_ID)).thenReturn(me());
        when(matchClient.searchMatches(any())).thenReturn(recruiting());
        when(matchClient.getMyMatches(eq(USER_ID))).thenReturn(List.of());
        when(recommendChat.call(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        recommend()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.matches[*].matchId", contains(6, 5, 2)));
    }

    @Test
    void 회원_조회_장애면_혼성_매치_최신순_3개_fallback() throws Exception {
        when(userClient.getUser(USER_ID)).thenThrow(FeignErrors.status(503));
        when(matchClient.searchMatches(any())).thenReturn(recruiting());
        when(matchClient.getMyMatches(eq(USER_ID))).thenReturn(List.of(new MyMatchDto(6L)));

        recommend()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.matches[*].matchId", contains(4, 2, 1)));

        verifyNoInteractions(recommendChat);
    }

    @Test
    void 회원이_없으면_404_USER_NOT_FOUND() throws Exception {
        when(userClient.getUser(USER_ID)).thenThrow(FeignErrors.status(404));

        recommend()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void 후보가_없으면_LLM_호출_없이_빈_목록() throws Exception {
        when(userClient.getUser(USER_ID)).thenReturn(me());
        when(matchClient.searchMatches(any())).thenReturn(List.of());
        when(matchClient.getMyMatches(eq(USER_ID))).thenReturn(List.of());

        recommend()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(false))
                .andExpect(jsonPath("$.matches", hasSize(0)));

        verifyNoInteractions(recommendChat);
    }

    @Test
    void 매치_목록도_못_받으면_500_AI_RECOMMEND_FAILED() throws Exception {
        when(userClient.getUser(USER_ID)).thenReturn(me());
        when(matchClient.searchMatches(any())).thenThrow(FeignErrors.status(503));

        recommend()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AI_RECOMMEND_FAILED"));
    }

    @Test
    void 로그인_헤더가_없으면_401() throws Exception {
        mvc.perform(get("/api/ai/matches/recommendations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
