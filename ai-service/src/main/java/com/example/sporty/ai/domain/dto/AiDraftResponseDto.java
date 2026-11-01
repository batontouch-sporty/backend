package com.example.sporty.ai.domain.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A23 응답: { initialValues, facilityCandidates }.
 * facilityCandidates는 P1(I4 연동)이라 P0에서는 항상 빈 배열이다(명세 결정).
 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AiDraftResponseDto {

    private AiDraftDto initialValues;

    @Builder.Default
    private List<FacilityCandidateDto> facilityCandidates = List.of();
}
