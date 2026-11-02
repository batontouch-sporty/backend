package com.example.sporty.ai.domain.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A23 응답: { initialValues, facilityCandidates }.
 * facilityCandidates는 facility I4(P1)로 받은 시설 후보 최대 5개. 지역·종목이 없거나 조회에 실패하면 빈 배열.
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
