package com.example.sporty.user.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 탈퇴 Saga 설정(withdrawal.saga.*).
 *
 * @param timeout          요청 마감(deadlineAt = 요청 시각 + timeout). match는 0초 초과 5분 이하만 받는다
 * @param grace            마감 뒤 결과를 더 기다리는 시간. 지나면 TIMED_OUT + 회원 복구
 * @param schedulingEnabled Outbox 발행·시간 초과 검사 스케줄러를 켤지(테스트에서는 끈다)
 * @param outboxDelayMs    Outbox 발행 주기
 * @param timeoutCheckMs   시간 초과 검사 주기
 */
@ConfigurationProperties(prefix = "withdrawal.saga")
public record WithdrawalSagaProperties(
        Duration timeout,
        Duration grace,
        Boolean schedulingEnabled,
        Long outboxDelayMs,
        Long timeoutCheckMs
) {
    public WithdrawalSagaProperties {
        if (timeout == null) timeout = Duration.ofMinutes(5);
        if (grace == null) grace = Duration.ofSeconds(30);
        if (schedulingEnabled == null) schedulingEnabled = true;
        if (outboxDelayMs == null) outboxDelayMs = 1000L;
        if (timeoutCheckMs == null) timeoutCheckMs = 5000L;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("withdrawal.saga.timeout은 0초 초과 5분 이하여야 한다(match가 거절함)");
        }
    }
}
