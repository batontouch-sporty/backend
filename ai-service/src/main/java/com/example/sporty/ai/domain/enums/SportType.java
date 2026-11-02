package com.example.sporty.ai.domain.enums;

import lombok.Getter;

/** 공통 모듈 없이 서비스마다 복사한다(구현계획 v2). 값은 match-service SportType과 같다. */
@Getter
public enum SportType {
    SOCCER("축구"),
    FUTSAL("풋살"),
    BASKETBALL("농구"),
    BASEBALL("야구"),
    TENNIS("테니스"),
    BADMINTON("배드민턴"),
    TABLE_TENNIS("탁구"),
    VOLLEYBALL("배구"),
    SWIMMING("수영"),
    RUNNING("러닝");

    private final String description;

    SportType(String description) {
        this.description = description;
    }
}
