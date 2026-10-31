package com.example.sporty.notification.notifications.domain.dto;

import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class NotificationReadResponseDto {

    private Long id;
    private boolean read;

    public static NotificationReadResponseDto fromEntity(NotificationEntity entity) {
        return NotificationReadResponseDto.builder()
                .id(entity.getId())
                .read(entity.isRead())
                .build();
    }
}
