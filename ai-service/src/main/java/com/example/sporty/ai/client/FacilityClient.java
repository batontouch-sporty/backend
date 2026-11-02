package com.example.sporty.ai.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.sporty.ai.domain.dto.FacilityCandidateDto;

@FeignClient(name = "facility-service")
public interface FacilityClient {

    // I4 시설 후보 조회. district는 자치구 코드(예: SONGPA), sport는 SportType 코드. 활성 시설 최대 5개.
    @GetMapping("/internal/facilities")
    List<FacilityCandidateDto> getCandidates(
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "sport", required = false) String sport);
}
