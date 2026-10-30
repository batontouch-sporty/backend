package com.example.sporty.notification.notifications.domain.entity;

import lombok.Getter;

// 알림 종류는 알림을 만든 Kafka 토픽 이름에서 따온다.
@Getter
public enum NotificationType {

    JOINED("참가 확정"),
    STATUS_CHANGED("모임 상태 변경"),
    WITHDRAWAL_REJECTED("탈퇴 거절"),
    STARTING_SOON("모임 시작 하루 전"),
    WAITLIST_PROMOTED("대기 승급");

    private final String description;

    NotificationType(String description) {
        this.description = description;
    }

}
