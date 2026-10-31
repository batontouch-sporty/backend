package com.example.sporty.notification.notifications.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// 수신자·매치는 다른 서비스 DB의 ID라 FK 없이 숫자로만 둔다.
@Entity
@Builder
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_event_recipient_type",
                columnNames = {"event_id", "recipient_user_id", "type"}),
        indexes = @Index(name = "idx_notification_recipient_read_created",
                columnList = "recipient_user_id, read_at, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 이벤트를 두 번 받아도 한 번만 저장하는 기준
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    // enum은 테이블 명세(R4)대로 VARCHAR에 저장한다. 지정하지 않으면 MariaDB ENUM 타입으로 만들어진다.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(name = "target_match_id")
    private Long targetMatchId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // null이면 안 읽은 알림
    @Column(name = "read_at")
    private LocalDateTime readAt;

    public boolean isRead() {
        return readAt != null;
    }

    // [A26] 읽음 처리. 이미 읽은 알림은 처음 읽은 시각을 유지한다.
    public void markRead() {
        if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }

}
