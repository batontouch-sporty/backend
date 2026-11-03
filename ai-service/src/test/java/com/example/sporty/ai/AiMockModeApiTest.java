package com.example.sporty.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.sporty.ai.client.FacilityClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 목업 모드(ai.mock.enabled=true)에서 실제 DraftChat·에이전트 경로로 A23 계약이 유지되는지 확인한다. */
@SpringBootTest(properties = "ai.mock.enabled=true")
@AutoConfigureMockMvc
class AiMockModeApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean FacilityClient facilityClient;

    ResultActions requestDraft(String prompt) throws Exception {
        return mvc.perform(post("/api/ai/matches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("prompt", prompt))));
    }

    @Test
    void 목업_모드에서도_초안_응답_형식은_같다() throws Exception {
        when(facilityClient.getCandidates(any(), any())).thenReturn(List.of());

        requestDraft("토요일 저녁 7시 송파에서 농구 10명")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialValues.sportType").value("BASKETBALL"))
                .andExpect(jsonPath("$.initialValues.region").value("송파구"))
                .andExpect(jsonPath("$.initialValues.maxParticipant").value(10))
                .andExpect(jsonPath("$.initialValues.genderGroup").value("MIXED"))
                .andExpect(jsonPath("$.facilityCandidates").isArray());
    }

    @Test
    void 목업_모드에서도_정보가_없으면_400_AI_DRAFT_INSUFFICIENT() throws Exception {
        requestDraft("안녕하세요")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_DRAFT_INSUFFICIENT"));
    }
}
