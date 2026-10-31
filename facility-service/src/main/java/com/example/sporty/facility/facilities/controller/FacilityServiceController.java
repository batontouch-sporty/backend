package com.example.sporty.facility.facilities.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.sporty.facility.facilities.domain.dto.ServiceDetailResponseDto;
import com.example.sporty.facility.facilities.domain.dto.ServiceRequestDto;
import com.example.sporty.facility.facilities.domain.dto.ServiceResponseDto;
import com.example.sporty.facility.facilities.service.FacilityQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class FacilityServiceController {

    private final FacilityQueryService facilityQueryService;

    // A20 (1차 FC-01): 체육시설 목록 조회
    @GetMapping
    public ResponseEntity<List<ServiceResponseDto>> getFacilities(
            @ModelAttribute ServiceRequestDto request
    ) {
        return ResponseEntity.ok(
                facilityQueryService.getFacilities(request)
        );
    }

    // A21 (1차 FC-02): 체육시설 상세 조회. Path의 serviceId는 DB PK다.
    @GetMapping("/{serviceId}")
    public ResponseEntity<ServiceDetailResponseDto> getFacility(
            @PathVariable Long serviceId
    ) {
        return ResponseEntity.ok(
                facilityQueryService.getFacility(serviceId)
        );
    }
}
