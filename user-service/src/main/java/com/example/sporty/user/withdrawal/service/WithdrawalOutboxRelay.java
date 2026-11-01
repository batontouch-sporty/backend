package com.example.sporty.user.withdrawal.service;

import com.example.sporty.user.withdrawal.domain.entity.OutboxEventEntity;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox에 쌓인 이벤트를 Kafka로 보낸다. Kafka 응답(ACK)을 받은 뒤에만 published_at을 채우므로,
 * 보내다 실패한 행은 다음 주기에 같은 eventId·payload로 다시 보낸다(받는 쪽은 eventId로 중복을 거른다).
 */
@Slf4j
@Component
public class WithdrawalOutboxRelay {

    private static final int BATCH = 50;
    private static final long ACK_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WithdrawalOutboxRelay(OutboxEventRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // 보낸 건수를 돌려준다.
    public int publishPending() {
        List<String> eventIds = outboxRepository.findUnpublishedIds(Limit.of(BATCH));
        int published = 0;
        for (String eventId : eventIds) {
            try {
                Boolean sent = transaction.execute(status -> publishOne(eventId));
                if (Boolean.TRUE.equals(sent)) {
                    published++;
                }
            } catch (RuntimeException e) {
                // 이 행은 미발행으로 남기고 다음 주기에 다시 보낸다.
                log.warn("Outbox 발행 실패, 다음 주기에 재시도: eventId={}", eventId, e);
                // 브로커가 응답하지 않으면 남은 행도 같은 시간을 기다리므로 이번 주기는 멈춘다.
                if (isTimeout(e) || Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        return published;
    }

    private boolean publishOne(String eventId) {
        // 다른 인스턴스가 먼저 보냈으면 건너뛴다.
        OutboxEventEntity event = outboxRepository.findUnpublishedForUpdate(eventId).orElse(null);
        if (event == null) {
            return false;
        }
        try {
            kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload())
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outbox 발행 중 중단됨", e);
        } catch (Exception e) {
            throw new IllegalStateException("Outbox 발행 실패", e);
        }
        event.markPublished(LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
        log.info("Outbox 발행: eventId={}, topic={}, key={}", eventId, event.getEventType(), event.getAggregateId());
        return true;
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof org.apache.kafka.common.errors.TimeoutException) {
                return true;
            }
        }
        return false;
    }

}
