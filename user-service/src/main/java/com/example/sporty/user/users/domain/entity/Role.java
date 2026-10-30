package com.example.sporty.user.users.domain.entity;

import lombok.Getter;

@Getter
public enum Role {

    USER("일반 회원"),
    ADMIN("관리자");

    private final String description;

    Role(String description) {
        this.description = description;
    }

}
