package com.example.sporty.user.withdrawal.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// 발행할 이벤트(테이블 명세 R7 outbox_event). 회원 상태 변경과 같은 트랜잭션에 저장하고,
// 발행기가 Kafka 응답(ACK)을 받은 뒤에만 published_at을 채운다.
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEventEntity {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    // Kafka key. 탈퇴 요청은 userId
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    // 보낼 토픽 이름
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    // DDL은 JSON 컬럼이지만 MariaDB의 JSON은 LONGTEXT(+json_valid 검사)라서 긴 문자열로 매핑한다.
    // SqlTypes.JSON으로 두면 ddl-auto=validate에서 타입이 다르다고 기동이 실패한다.
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public OutboxEventEntity(String eventId, String aggregateId, String eventType, String payload) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public void markPublished(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

}
