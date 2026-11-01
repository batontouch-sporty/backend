package com.example.sporty.ai.domain.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** AI가 고른 추천 매치 ID (추천 순서대로). 도구 → 에이전트 내부 전달용 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiRecommendDto {

    private List<Long> matchIds;
}
