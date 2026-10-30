package com.example.sporty.notification.notifications.domain.dto;

import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import com.example.sporty.notification.notifications.domain.entity.NotificationType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// DB 컬럼 이름(body·target_match_id·read_at)을 API 명세 이름(message·matchId·read)으로 바꿔 내보낸다.
@Builder
@Getter
public class NotificationResponseDto {

    private Long id;
    private NotificationType type;
    private String message;
    private Long matchId;
    private boolean read;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    public static NotificationResponseDto fromEntity(NotificationEntity entity) {
        return NotificationResponseDto.builder()
                .id(entity.getId())
                .type(entity.getType())
                .message(entity.getBody())
                .matchId(entity.getTargetMatchId())
                .read(entity.isRead())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
