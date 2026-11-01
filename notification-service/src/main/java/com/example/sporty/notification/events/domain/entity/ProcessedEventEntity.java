package com.example.sporty.notification.events.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// 처리를 끝낸 이벤트. 같은 eventId가 다시 오면 건너뛴다(테이블 명세 R4 processed_events).
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    // 받은 토픽 이름
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private LocalDateTime processedAt;

    public ProcessedEventEntity(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }

}
