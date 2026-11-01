package com.example.sporty.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.sporty.ai.agent.SearchChat;
import com.example.sporty.ai.client.MatchClient;
import com.example.sporty.ai.client.dto.MatchDto;
import com.example.sporty.ai.client.dto.MatchSearchQuery;
import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.domain.enums.MatchStatus;
import com.example.sporty.ai.domain.enums.SportType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A22 자연어 매치 검색. LLM(SearchChat)과 match-service(MatchClient)는 목으로 바꿔 외부 호출이 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiSearchApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean SearchChat searchChat;
    @MockitoBean MatchClient matchClient;

    static MatchDto match(long id) {
        return MatchDto.builder().matchId(id).title("강남 풋살").sportType(SportType.FUTSAL)
                .region("강남구").genderGroup(GenderGroup.MIXED).status(MatchStatus.RECRUITING)
                .startAt(LocalDateTime.of(2026, 11, 8, 10, 0)).build();
    }

    ResultActions search(String prompt) throws Exception {
        return mvc.perform(post("/api/ai/matches/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("prompt", prompt))));
    }

    MatchSearchQuery sentQuery() {
        ArgumentCaptor<MatchSearchQuery> captor = ArgumentCaptor.forClass(MatchSearchQuery.class);
        verify(matchClient).searchMatches(captor.capture());
        return captor.getValue();
    }

    @Test
    void LLM이_뽑은_조건으로_매치를_검색한다() throws Exception {
        when(searchChat.call(anyString(), anyString()))
                .thenReturn("{\"sportType\":\"FUTSAL\",\"region\":\"강남구\"}");
        when(matchClient.searchMatches(any())).thenReturn(List.of(match(41)));

        search("강남에서 풋살 찾아줘")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(false))
                .andExpect(jsonPath("$.conditions[0].label").value("종목"))
                .andExpect(jsonPath("$.conditions[0].value").value("풋살"))
                .andExpect(jsonPath("$.criteria.region").value("강남구"))
                .andExpect(jsonPath("$.matches[0].matchId").value(41));

        assertThat(sentQuery().getSportType()).isEqualTo(SportType.FUTSAL);
    }

    @Test
    void LLM이_실패하면_문장_속_종목_자치구로_대체_검색한다() throws Exception {
        when(searchChat.call(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));
        when(matchClient.searchMatches(any())).thenReturn(List.of(match(41)));

        search("이번 주말 강남에서 풋살 할 사람")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.conditions", hasSize(0)))
                .andExpect(jsonPath("$.matches", hasSize(1)));

        MatchSearchQuery query = sentQuery();
        assertThat(query.getSportType()).isEqualTo(SportType.FUTSAL);
        assertThat(query.getRegion()).isEqualTo("강남구");
    }

    @Test
    void LLM이_실패하고_단어도_없으면_모집_중_매치를_준다() throws Exception {
        when(searchChat.call(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));
        when(matchClient.searchMatches(any())).thenReturn(List.of());

        search("운동 같이 할 사람")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.conditions", hasSize(0)));

        assertThat(sentQuery().getStatus()).isEqualTo(MatchStatus.RECRUITING);
    }

    @Test
    void 중랑구는_중구로_잘못_찾지_않는다() throws Exception {
        when(searchChat.call(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));
        when(matchClient.searchMatches(any())).thenReturn(List.of());

        search("중랑구 배드민턴").andExpect(status().isOk());

        assertThat(sentQuery().getRegion()).isEqualTo("중랑구");
    }

    @Test
    void LLM이_조건을_못_찾으면_400_AI_CONDITION_NOT_FOUND() throws Exception {
        when(searchChat.call(anyString(), anyString())).thenReturn("어떤 매치를 찾으시나요?");

        search("안녕")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_CONDITION_NOT_FOUND"));

        verifyNoInteractions(matchClient);
    }

    @Test
    void 지원하지_않는_종목이면_400_AI_UNSUPPORTED_SPORT() throws Exception {
        when(searchChat.call(anyString(), anyString())).thenThrow(new IllegalArgumentException(
                "No enum constant com.example.sporty.ai.domain.enums.SportType.SQUASH"));

        search("스쿼시 할 사람")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_UNSUPPORTED_SPORT"));
    }

    @Test
    void match_호출이_실패하면_503_AI_UNAVAILABLE() throws Exception {
        when(searchChat.call(anyString(), anyString())).thenReturn("{\"sportType\":\"FUTSAL\"}");
        when(matchClient.searchMatches(any())).thenThrow(FeignErrors.status(503));

        search("풋살")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("매치 목록을 불러오지 못했습니다. 잠시 후 다시 시도해주세요."));
    }
}
