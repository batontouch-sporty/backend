package com.example.sporty.facility.facilities.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.sporty.facility.facilities.domain.dto.InternalFacilityResponseDto;
import com.example.sporty.facility.facilities.service.FacilityQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 서비스끼리만 쓰는 내부 API. Gateway가 /internal/** 외부 호출을 막는다.
 */
@RestController
@RequestMapping("/internal/facilities")
@RequiredArgsConstructor
public class InternalFacilityController {

    private final FacilityQueryService facilityQueryService;

    // I3: 시설 단건 조회. match-service가 매치 생성·수정 시 운영시간·활성 여부 확인과 시설 정보 복사에 쓴다.
    @GetMapping("/{id}")
    public ResponseEntity<InternalFacilityResponseDto> getFacility(@PathVariable Long id) {
        return ResponseEntity.ok(facilityQueryService.getInternalFacility(id));
    }
}
