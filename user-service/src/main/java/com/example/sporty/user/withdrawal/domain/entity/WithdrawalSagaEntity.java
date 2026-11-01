package com.example.sporty.user.withdrawal.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// 탈퇴 Saga 한 건(테이블 명세 R7 withdrawal_saga).
// 시각은 모두 UTC로 저장한다. 이벤트에는 UTC 오프셋(Z)을 붙여 보낸다.
@Entity
@Table(name = "withdrawal_saga")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class WithdrawalSagaEntity {

    @Id
    @Column(name = "saga_id", length = 36, columnDefinition = "CHAR(36)")
    private String sagaId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SagaStatus status;

    // 거절 사유(OWNER_OF_IN_PROGRESS_MATCH·DEADLINE_EXCEEDED)
    @Column(length = 200)
    private String reason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;

    // 요청 이벤트 ID. 결과 이벤트의 requestEventId와 같아야 이 Saga의 결과로 본다.
    @Column(name = "claim_token", nullable = false, unique = true, length = 64)
    private String claimToken;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    // [USR-04] 탈퇴 접수: 바로 PROCESSING으로 저장한다(R7, 별도 선점 단계 없음).
    public static WithdrawalSagaEntity start(String sagaId, Long userId, String requestEventId,
                                             LocalDateTime requestedAt, LocalDateTime deadlineAt) {
        return WithdrawalSagaEntity.builder()
                .sagaId(sagaId)
                .userId(userId)
                .status(SagaStatus.PROCESSING)
                .requestedAt(requestedAt)
                .deadlineAt(deadlineAt)
                .claimToken(requestEventId)
                .build();
    }

    public boolean isProcessing() {
        return status == SagaStatus.PROCESSING;
    }

    // 종료 상태로 바꾼다. finished_at은 종료 상태에서만 채운다(R7 CHECK).
    public void finish(SagaStatus result, String reason, LocalDateTime finishedAt) {
        this.status = result;
        this.reason = reason;
        this.finishedAt = finishedAt;
    }

}
