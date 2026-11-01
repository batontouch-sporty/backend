package com.example.sporty.ai.domain.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.example.sporty.ai.client.dto.MatchSearchQuery;
import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.domain.enums.MatchStatus;
import com.example.sporty.ai.domain.enums.SkillLevel;
import com.example.sporty.ai.domain.enums.SportType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문장에서 추출한 검색 조건. A22 응답의 criteria(조건 직접 수정 폼 초기값)로도 쓴다. */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiSearchConditionDto {

    private SportType sportType;
    private String region;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private SkillLevel skillLevel;
    private GenderGroup genderGroup;
    private MatchStatus status;

    public MatchSearchQuery toQuery() {
        return MatchSearchQuery.builder()
                .sportType(sportType).region(region)
                .startAt(startAt).endAt(endAt)
                .skillLevel(skillLevel).genderGroup(genderGroup).status(status)
                .build();
    }

    @JsonIgnore
    public boolean isEmpty() {
        return sportType == null && region == null && startAt == null && endAt == null
                && skillLevel == null && genderGroup == null && status == null;
    }

    // 프론트 조건 요약용. 순서: 종목 → 성별 → 날짜 → 자치구 → 실력 수준 → 모집 상태 (1차와 같음)
    public List<AiConditionDto> toConditions() {
        List<AiConditionDto> conditions = new ArrayList<>();
        if (sportType != null) {
            conditions.add(new AiConditionDto("종목", sportType.getDescription()));
        }
        if (genderGroup != null) {
            conditions.add(new AiConditionDto("성별", genderGroup.getDescription()));
        }
        String date = toDateText();
        if (date != null) {
            conditions.add(new AiConditionDto("날짜", date));
        }
        if (region != null && !region.isBlank()) {
            conditions.add(new AiConditionDto("자치구", region));
        }
        if (skillLevel != null) {
            conditions.add(new AiConditionDto("실력 수준", toSkillText(skillLevel)));
        }
        if (status != null) {
            conditions.add(new AiConditionDto("모집 상태", status.getDescription()));
        }
        return conditions;
    }

    // 하루면 2026-10-03, 기간이면 2026-10-03 ~ 2026-10-04
    private String toDateText() {
        if (startAt == null && endAt == null) {
            return null;
        }
        if (startAt == null) {
            return "~ " + endAt.toLocalDate();
        }
        if (endAt == null || startAt.toLocalDate().equals(endAt.toLocalDate())) {
            return startAt.toLocalDate().toString();
        }
        return startAt.toLocalDate() + " ~ " + endAt.toLocalDate();
    }

    private static String toSkillText(SkillLevel skillLevel) {
        return switch (skillLevel) {
            case BEGINNER -> "초급";
            case INTERMEDIATE -> "중급";
            case ADVANCED -> "고급";
        };
    }
}
