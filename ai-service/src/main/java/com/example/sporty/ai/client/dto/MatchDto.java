package com.example.sporty.ai.client.dto;

import java.time.LocalDateTime;

import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.domain.enums.MatchStatus;
import com.example.sporty.ai.domain.enums.SkillLevel;
import com.example.sporty.ai.domain.enums.SportType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** match-service A10 매치 목록 응답 항목. A22·A24 응답의 matches에도 그대로 쓴다. */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchDto {

    private Long matchId;
    private String title;
    private String description;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer numCurrentParticipant;
    private Integer maxParticipant;
    private Double distance;
    private MatchStatus status;
    private SkillLevel skillLevel;
    private SportType sportType;
    private GenderGroup genderGroup;
    private String region;
    private Boolean isFree;
}
