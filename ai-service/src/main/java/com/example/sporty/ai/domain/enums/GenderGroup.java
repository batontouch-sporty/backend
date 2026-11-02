package com.example.sporty.ai.domain.enums;

import lombok.Getter;

/** match-service GenderGroup과 같은 값 */
@Getter
public enum GenderGroup {
    MALE("남성"),
    FEMALE("여성"),
    MIXED("혼성");

    private final String description;

    GenderGroup(String description) {
        this.description = description;
    }
}
