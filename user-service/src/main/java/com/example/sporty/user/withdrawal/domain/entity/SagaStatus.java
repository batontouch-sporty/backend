package com.example.sporty.user.withdrawal.domain.entity;

import lombok.Getter;

// 탈퇴 Saga 상태(테이블 명세 R7 withdrawal_saga.status). 접수하면 바로 PROCESSING, 나머지는 종료 상태다.
@Getter
public enum SagaStatus {

    PROCESSING("처리 중"),
    CLEANED("매치 정리 완료 → 탈퇴"),
    REJECTED("매치 쪽 거절 → 복구"),
    TIMED_OUT("결과 없음 → 복구");

    private final String description;

    SagaStatus(String description) {
        this.description = description;
    }

}
