package com.example.sporty.ai.domain.enums;

import lombok.Getter;

/** match-service MatchStatus와 같은 값(2차 상태 확장) */
@Getter
public enum MatchStatus {
    RECRUITING("모집중"),
    CLOSED("마감"),
    IN_PROGRESS("진행중"),
    COMPLETED("종료"),
    CANCELLED("취소");

    private final String description;

    MatchStatus(String description) {
        this.description = description;
    }
}
