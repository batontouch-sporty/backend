package com.example.sporty.ai.domain.dto;

import java.util.List;

import com.example.sporty.ai.client.dto.MatchDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A22 응답: { conditions, matches, criteria, fallback }.
 * fallback이 true면 LLM 대신 문장 속 종목·자치구 단어로 검색한 결과다(conditions는 []).
 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AiSearchResponseDto {

    private List<AiConditionDto> conditions;
    private List<MatchDto> matches;
    private AiSearchConditionDto criteria;
    private boolean fallback;
}
