package com.example.sporty.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.sporty.facility.facilities.domain.entity.LocationEntity;
import com.example.sporty.facility.facilities.domain.entity.ServiceEntity;
import com.example.sporty.facility.facilities.repository.LocationRepository;
import com.example.sporty.facility.facilities.repository.ServiceRepository;
import com.example.sporty.facility.facilities.service.FacilitySyncService;
import com.example.sporty.facility.facilities.service.FacilitySyncService.SyncResult;
import com.example.sporty.facility.openapi.SeoulFacilityApiClient;
import com.example.sporty.facility.openapi.domain.dto.SeoulFacilityResponseDto.Row;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 1차 시설 API(A20·A21)를 옮기고 2차 계약(I3, 결정 14·21)을 더했다.
 * 서울시 API는 목으로 바꿔 테스트에서 외부 호출을 하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacilityApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LocationRepository locations;
    @Autowired ServiceRepository services;
    @Autowired FacilitySyncService syncService;

    @MockitoBean SeoulFacilityApiClient apiClient;

    ServiceEntity activeService;
    ServiceEntity inactiveService;

    @BeforeEach
    void setUp() {
        services.deleteAll();
        locations.deleteAll();

        LocationEntity jamsil = locations.save(LocationEntity.builder()
                .region("송파구").name("잠실 실내체육관").facilityName("농구장").contact("02-000-0000").build());
        LocationEntity mapo = locations.save(LocationEntity.builder()
                .region("마포구").name("난지천공원").facilityName("풋살장").build());

        activeService = services.save(ServiceEntity.builder()
                .location(jamsil).serviceId("S0001").name("잠실 실내체육관 농구장").status("접수중")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(22, 0)).isFree(false)
                .url("https://yeyak.seoul.go.kr/1").reservationDeadlineAt(LocalDateTime.of(2026, 11, 7, 18, 0))
                .build());

        inactiveService = ServiceEntity.builder()
                .location(mapo).serviceId("S0002").name("난지천 풋살장").status("접수종료")
                .startTime(LocalTime.of(7, 0)).endTime(LocalTime.of(19, 0)).isFree(true)
                .url("https://yeyak.seoul.go.kr/2").build();
        inactiveService.deactivate();
        inactiveService = services.save(inactiveService);
    }

    // ---------- A20 시설 목록 ----------

    @Test
    void 시설_목록은_활성_시설만_돌려준다() throws Exception {
        mvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].serviceId").value(activeService.getId()))
                .andExpect(jsonPath("$[0].serviceType").value("농구장"))
                .andExpect(jsonPath("$[0].region").value("송파구"))
                .andExpect(jsonPath("$[0].isFree").value(false));
    }

    @Test
    void 시설_목록은_자치구로_거른다() throws Exception {
        mvc.perform(get("/api/services").param("region", "마포구"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 시작시간이_종료시간보다_늦으면_400() throws Exception {
        mvc.perform(get("/api/services").param("startTime", "20:00").param("endTime", "10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_CONDITION"));
    }

    @Test
    void 시간_형식이_틀리면_400() throws Exception {
        mvc.perform(get("/api/services").param("startTime", "25시"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_CONDITION"));
    }

    // ---------- A21 시설 상세 ----------

    @Test
    void 시설_상세를_조회한다() throws Exception {
        mvc.perform(get("/api/services/{id}", activeService.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeService.getId()))
                .andExpect(jsonPath("$.serviceId").value("S0001"))
                .andExpect(jsonPath("$.paymentMethod").value("유료"))
                .andExpect(jsonPath("$.url").value("https://yeyak.seoul.go.kr/1"));
    }

    @Test
    void 비활성_시설_상세는_404() throws Exception {
        mvc.perform(get("/api/services/{id}", inactiveService.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
    }

    @Test
    void 숫자가_아닌_서비스_ID는_400() throws Exception {
        mvc.perform(get("/api/services/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_ID"));
    }

    // ---------- I3 내부 시설 단건 ----------

    @Test
    void 내부_시설_조회는_복사에_필요한_필드를_준다() throws Exception {
        mvc.perform(get("/internal/facilities/{id}", activeService.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeService.getId()))
                .andExpect(jsonPath("$.name").value("잠실 실내체육관 농구장"))
                .andExpect(jsonPath("$.startTime").value("09:00:00"))
                .andExpect(jsonPath("$.endTime").value("22:00:00"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.url").value("https://yeyak.seoul.go.kr/1"))
                .andExpect(jsonPath("$.locationName").value("잠실 실내체육관"))
                .andExpect(jsonPath("$.region").value("송파구"))
                .andExpect(jsonPath("$.isFree").value(false));
    }

    @Test
    void 내부_조회는_비활성_시설도_200_active_false() throws Exception {
        mvc.perform(get("/internal/facilities/{id}", inactiveService.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void 내부_조회_없는_시설은_404() throws Exception {
        mvc.perform(get("/internal/facilities/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
    }

    // ---------- 동기화 ----------

    @Test
    void API_키가_없으면_외부_호출_없이_동기화를_건너뛴다() {
        when(apiClient.isConfigured()).thenReturn(false);

        SyncResult result = syncService.syncAll();

        assertThat(result.fetchedCount()).isZero();
        verify(apiClient, never()).fetchAll();
    }

    @Test
    void 동기화는_새_시설을_만들고_목록에_없는_시설을_비활성화한다() throws Exception {
        Row row = json.readValue("""
                {"SVCID":"S0003","MINCLASSNM":"테니스장","SVCNM":"응봉공원 테니스장","PLACENM":"응봉공원",
                 "AREANM":"성동구","SVCSTATNM":"접수중","PAYATNM":"유료","SVCURL":"https://yeyak.seoul.go.kr/3",
                 "RCPTENDDT":"2026-12-31 17:00:00.0","X":"127.02","Y":"37.55","TELNO":"02-2293-7646",
                 "V_MIN":"07:00","V_MAX":"19:00"}
                """, Row.class);
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchAll()).thenReturn(List.of(row));

        SyncResult result = syncService.syncAll();

        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.deactivatedCount()).isEqualTo(1); // S0001이 목록에 없어 꺼진다
        ServiceEntity synced = services.findByServiceId("S0003").orElseThrow();
        assertThat(synced.isActive()).isTrue();
        assertThat(synced.getStartTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(services.findByServiceId("S0001").orElseThrow().isActive()).isFalse();
    }

    @Test
    void 필수_필드가_빠진_기존_시설은_비활성화하지_않는다() throws Exception {
        // 리뷰 반영: 기존 활성 S0001이 응답에 있지만 PLACENM만 빠진 경우
        Row incomplete = json.readValue("""
                {"SVCID":"S0001","MINCLASSNM":"농구장","SVCNM":"잠실 실내체육관 농구장",
                 "AREANM":"송파구","SVCSTATNM":"접수중"}
                """, Row.class);
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchAll()).thenReturn(List.of(incomplete));

        SyncResult result = syncService.syncAll();

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.deactivatedCount()).isZero();
        ServiceEntity kept = services.findByServiceId("S0001").orElseThrow();
        assertThat(kept.isActive()).isTrue();
        assertThat(kept.getName()).isEqualTo("잠실 실내체육관 농구장"); // 기존 값 보존
    }

    @Test
    void SVCID가_빠진_행이_있으면_비활성화를_건너뛴다() throws Exception {
        Row noId = json.readValue("""
                {"MINCLASSNM":"농구장","SVCNM":"이름만 있는 시설","PLACENM":"어딘가","AREANM":"송파구"}
                """, Row.class);
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchAll()).thenReturn(List.of(noId));

        SyncResult result = syncService.syncAll();

        assertThat(result.deactivatedCount()).isZero();
        assertThat(services.findByServiceId("S0001").orElseThrow().isActive()).isTrue();
    }
}
