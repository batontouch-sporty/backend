package com.example.sporty.ai.util;

import com.example.sporty.ai.domain.dto.AiSearchConditionDto;
import com.example.sporty.ai.domain.enums.District;
import com.example.sporty.ai.domain.enums.SportType;

/**
 * LLM 없이 문장에서 종목·자치구 단어만 찾는다. A22 대체 검색(Fallback)에서 쓴다.
 * 예: "이번 주말 강남에서 풋살" → sportType=FUTSAL, region=강남구
 */
public final class KeywordConditionExtractor {

    private KeywordConditionExtractor() {
    }

    public static AiSearchConditionDto extract(String prompt) {
        String text = prompt == null ? "" : prompt;
        return AiSearchConditionDto.builder()
                .sportType(findSport(text))
                .region(findRegion(text))
                .build();
    }

    private static SportType findSport(String text) {
        // "탁구"가 "구"로 끝나는 자치구 단어와 섞이지 않도록 종목명은 그대로 비교한다.
        for (SportType sport : SportType.values()) {
            if (text.contains(sport.getDescription())) {
                return sport;
            }
        }
        return null;
    }

    private static String findRegion(String text) {
        for (District district : District.longestNameFirst()) {
            if (text.contains(district.getName()) || text.contains(district.shortName())) {
                return district.getName();
            }
        }
        return null;
    }
}
