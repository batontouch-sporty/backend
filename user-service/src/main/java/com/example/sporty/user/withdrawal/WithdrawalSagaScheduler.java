package com.example.sporty.user.withdrawal;

import com.example.sporty.user.withdrawal.service.WithdrawalOutboxRelay;
import com.example.sporty.user.withdrawal.service.WithdrawalSagaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Outbox 발행과 시간 초과 검사를 주기적으로 돌린다. 테스트에서는 withdrawal.saga.scheduling-enabled=false로 끄고 직접 부른다.
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "withdrawal.saga.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class WithdrawalSagaScheduler {

    private final WithdrawalOutboxRelay outboxRelay;
    private final WithdrawalSagaService withdrawalSagaService;

    @Scheduled(fixedDelayString = "${withdrawal.saga.outbox-delay-ms:1000}")
    public void publishOutbox() {
        try {
            outboxRelay.publishPending();
        } catch (RuntimeException e) {
            log.warn("Outbox 발행 주기 실패, 다음 주기에 재시도", e);
        }
    }

    @Scheduled(fixedDelayString = "${withdrawal.saga.timeout-check-ms:5000}")
    public void timeOutExpiredSagas() {
        try {
            withdrawalSagaService.timeOutExpired();
        } catch (RuntimeException e) {
            log.warn("탈퇴 Saga 시간 초과 검사 실패, 다음 주기에 재시도", e);
        }
    }

}
