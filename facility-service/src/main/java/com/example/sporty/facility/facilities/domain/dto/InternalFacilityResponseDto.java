package com.example.sporty.facility.facilities.domain.dto;

import java.time.LocalTime;

import com.example.sporty.facility.facilities.domain.entity.LocationEntity;
import com.example.sporty.facility.facilities.domain.entity.ServiceEntity;

import lombok.Builder;
import lombok.Getter;

/**
 * I3 내부 시설 단건 조회 응답.
 * match-service가 name(→facilityName)·locationName·region·isFree·url을 match_db에 복사한다.
 */
@Getter
@Builder
public class InternalFacilityResponseDto {

    private Long id;
    private String name;
    private LocalTime startTime;
    private LocalTime endTime;
    private boolean active;
    private String url;
    private String locationName;
    private String region;
    private Boolean isFree;

    public static InternalFacilityResponseDto from(ServiceEntity service) {
        LocationEntity location = service.getLocation();

        return InternalFacilityResponseDto.builder()
                .id(service.getId())
                .name(service.getName())
                .startTime(service.getStartTime())
                .endTime(service.getEndTime())
                .active(service.isActive())
                .url(service.getUrl())
                .locationName(location.getName())
                .region(location.getRegion())
                .isFree(service.getIsFree())
                .build();
    }
}
