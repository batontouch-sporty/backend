package com.example.sporty.facility;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.sporty.facility.commons.exception.ServiceNotFoundException;
import com.example.sporty.facility.facilities.service.FacilityQueryService;
import com.example.sporty.facility.openapi.SeoulFacilityApiClient;

// 처리하지 않은 예외는 500 {code, message}로, 기존 4xx와 404·405 같은 표준 상태는 그대로 나가는지 본다.
@SpringBootTest
@AutoConfigureMockMvc
class FacilityErrorResponseTest {

    @Autowired MockMvc mvc;
    @MockitoBean FacilityQueryService facilityQueryService;
    @MockitoBean SeoulFacilityApiClient apiClient;

    @Test
    void 처리하지_않은_예외는_500과_공통_형식으로_답한다() throws Exception {
        when(facilityQueryService.getFacility(anyLong())).thenThrow(new IllegalStateException("db password=secret"));
        mvc.perform(get("/api/services/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret"))));
    }

    @Test
    void 기존_4xx_응답은_그대로다() throws Exception {
        when(facilityQueryService.getFacility(anyLong())).thenThrow(new ServiceNotFoundException());
        mvc.perform(get("/api/services/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
        mvc.perform(get("/api/services/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_ID"));
    }

    @Test
    void 표준_404_405는_상태_코드를_지킨다() throws Exception {
        mvc.perform(post("/api/services/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
