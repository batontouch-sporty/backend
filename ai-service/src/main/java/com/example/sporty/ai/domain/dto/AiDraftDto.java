package com.example.sporty.ai.domain.dto;

import java.time.LocalDateTime;

import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.domain.enums.SkillLevel;
import com.example.sporty.ai.domain.enums.SportType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 매치 개설 폼에 채울 AI 초안(A23 initialValues). DB에 저장하지 않는다. */
@Builder
@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDraftDto {

    private SportType sportType;
    private String region;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer maxParticipant;
    private SkillLevel skillLevel;
    private GenderGroup genderGroup;
    private String title;
    private String description;
}
