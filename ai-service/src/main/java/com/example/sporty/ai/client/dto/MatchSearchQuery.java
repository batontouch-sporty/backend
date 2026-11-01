package com.example.sporty.ai.client.dto;

import java.time.LocalDateTime;

import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.domain.enums.MatchStatus;
import com.example.sporty.ai.domain.enums.SkillLevel;
import com.example.sporty.ai.domain.enums.SportType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A10 매치 목록 검색 조건(쿼리 파라미터). 값이 없는 조건은 보내지 않는다. */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MatchSearchQuery {

    private SportType sportType;
    private String region;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private SkillLevel skillLevel;
    private GenderGroup genderGroup;
    private MatchStatus status;
}
