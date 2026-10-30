package com.example.sporty.user.commons.util;

import lombok.Getter;

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
