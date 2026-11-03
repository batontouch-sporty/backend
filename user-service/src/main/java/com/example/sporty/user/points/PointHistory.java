package com.example.sporty.user.points;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "point_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {
    public enum Reason {
        SIGNUP, DEPOSIT_CHARGED, DEPOSIT_CANCELLED, DEPOSIT_RETURNED,
        ATTENDANCE_REWARDED, NO_SHOW_FORFEITED, SUSPENSION_EXPIRED
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    private Long matchId;
    @Column(length = 36)
    private String requestId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Reason reason;
    private int beforePoints;
    private int afterPoints;
    private int delta;
    @Column(length = 64)
    private String sourceEventId;
    private LocalDateTime createdAt;

    PointHistory(long userId, Long matchId, String requestId, Reason reason, int before,
                 int after, String eventId, LocalDateTime now) {
        this.userId = userId;
        this.matchId = matchId;
        this.requestId = requestId;
        this.reason = reason;
        this.beforePoints = before;
        this.afterPoints = after;
        this.delta = after - before;
        this.sourceEventId = eventId;
        this.createdAt = now;
    }
}
