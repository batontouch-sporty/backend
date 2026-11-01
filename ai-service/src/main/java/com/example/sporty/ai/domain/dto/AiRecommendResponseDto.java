package com.example.sporty.ai.domain.dto;

import java.util.List;

import com.example.sporty.ai.client.dto.MatchDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A24 응답: { matches, fallback }. 1차의 배열 응답을 검색(A22)과 같은 객체 모양으로 바꿨다(명세 결정 27). */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AiRecommendResponseDto {

    private List<MatchDto> matches;
    private boolean fallback;
}
