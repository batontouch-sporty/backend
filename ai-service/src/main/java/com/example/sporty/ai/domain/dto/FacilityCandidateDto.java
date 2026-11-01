package com.example.sporty.ai.domain.dto;

import java.time.LocalTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A23 facilityCandidates 항목. facility I4 응답과 같은 모양(P1). */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FacilityCandidateDto {

    private Long id;
    private String name;
    private String locationName;
    private String region;
    private LocalTime startTime;
    private LocalTime endTime;
    private Boolean isFree;
    private String url;
}
