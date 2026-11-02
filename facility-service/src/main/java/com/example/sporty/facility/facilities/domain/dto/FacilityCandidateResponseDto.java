package com.example.sporty.facility.facilities.domain.dto;

import java.time.LocalTime;

import com.example.sporty.facility.facilities.domain.entity.LocationEntity;
import com.example.sporty.facility.facilities.domain.entity.ServiceEntity;

import lombok.Builder;
import lombok.Getter;

/** I4 시설 후보 조회 응답 항목. AI 초안의 facilityCandidates로 그대로 쓴다. */
@Getter
@Builder
public class FacilityCandidateResponseDto {

    private Long id;
    private String name;
    private String locationName;
    private String region;
    private LocalTime startTime;
    private LocalTime endTime;
    private Boolean isFree;
    private String url;

    public static FacilityCandidateResponseDto from(ServiceEntity service) {
        LocationEntity location = service.getLocation();

        return FacilityCandidateResponseDto.builder()
                .id(service.getId())
                .name(service.getName())
                .locationName(location.getName())
                .region(location.getRegion())
                .startTime(service.getStartTime())
                .endTime(service.getEndTime())
                .isFree(service.getIsFree())
                .url(service.getUrl())
                .build();
    }
}
