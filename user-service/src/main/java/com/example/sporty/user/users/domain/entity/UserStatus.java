package com.example.sporty.user.users.domain.entity;

import lombok.Getter;

@Getter
public enum UserStatus {

    ACTIVE("정상 회원"),
    // 탈퇴 Saga 진행 중. 로그인·매치 참가 불가
    WITHDRAWING("탈퇴 진행 중"),
    WITHDRAWN("탈퇴 회원");

    private final String description;

    UserStatus(String description) {
        this.description = description;
    }

}
