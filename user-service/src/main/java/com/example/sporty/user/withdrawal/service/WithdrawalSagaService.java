package com.example.sporty.user.withdrawal.service;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.withdrawal.WithdrawalSagaMetrics;
import com.example.sporty.user.withdrawal.WithdrawalSagaProperties;
import com.example.sporty.user.withdrawal.domain.entity.OutboxEventEntity;
import com.example.sporty.user.withdrawal.domain.entity.ProcessedEventEntity;
import com.example.sporty.user.withdrawal.domain.entity.SagaStatus;
import com.example.sporty.user.withdrawal.domain.entity.WithdrawalSagaEntity;
import com.example.sporty.user.withdrawal.events.KafkaTopics;
import com.example.sporty.user.withdrawal.events.dto.WithdrawalResultEvent;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.example.sporty.user.withdrawal.repository.ProcessedEventRepository;
import com.example.sporty.user.withdrawal.repository.WithdrawalSagaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 탈퇴 Saga 회원 측(R7).
 * 접수 → withdrawal_saga PROCESSING + 회원 WITHDRAWING + 요청 이벤트 Outbox 저장(한 트랜잭션)
 * 결과 → sagaId·requestEventId·userId가 요청과 맞을 때만 적용. CLEANED는 WITHDRAWN, REJECTED는 ACTIVE 복구
 * 시간 초과 → 마감 + 유예가 지나도 PROCESSING이면 TIMED_OUT + ACTIVE 복구
 * 늦은 결과 → 이미 TIMED_OUT이면 상태를 바꾸지 않고 기록. 늦은 CLEANED는 WARN 로그 + Counter(경보 대상)
 */
@Slf4j
@Service
public class WithdrawalSagaService {

    private static final int TIMEOUT_BATCH = 50;

    private final WithdrawalSagaRepository sagaRepository;
    private final OutboxEventRepository outboxRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final WithdrawalSagaMetrics metrics;
    private final WithdrawalSagaProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WithdrawalSagaService(WithdrawalSagaRepository sagaRepository, OutboxEventRepository outboxRepository,
                                 ProcessedEventRepository processedEventRepository, UserRepository userRepository,
                                 RefreshTokenService refreshTokenService, WithdrawalSagaMetrics metrics,
                                 WithdrawalSagaProperties properties, ObjectMapper objectMapper, Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.sagaRepository = sagaRepository;
        this.outboxRepository = outboxRepository;
        this.processedEventRepository = processedEventRepository;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.metrics = metrics;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // [USR-04] 탈퇴 접수. 회원 조회·비밀번호 확인을 마친 UserService 트랜잭션 안에서 부른다.
    @Transactional(propagation = Propagation.MANDATORY)
    public String start(UserEntity user) {

        // 1. 요청·마감 시각(DB 정밀도에 맞춰 마이크로초까지)
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant deadlineAt = requestedAt.plus(properties.timeout());
        String sagaId = UUID.randomUUID().toString();
        String requestEventId = UUID.randomUUID().toString();

        // 2. 회원 WITHDRAWING, Saga는 바로 PROCESSING(claim_token = 요청 이벤트 ID)
        user.startWithdrawal();
        sagaRepository.save(WithdrawalSagaEntity.start(sagaId, user.getId(), requestEventId,
                utc(requestedAt), utc(deadlineAt)));

        // 3. 요청 이벤트를 같은 트랜잭션에 Outbox로 저장. 발행은 WithdrawalOutboxRelay가 한다.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", requestEventId);
        payload.put("sagaId", sagaId);
        payload.put("userId", user.getId());
        payload.put("requestedAt", requestedAt.atOffset(ZoneOffset.UTC).toString());
        payload.put("deadlineAt", deadlineAt.atOffset(ZoneOffset.UTC).toString());
        outboxRepository.save(new OutboxEventEntity(requestEventId, String.valueOf(user.getId()),
                KafkaTopics.WITHDRAWAL_REQUESTED, toJson(payload)));

        log.info("탈퇴 Saga 접수: sagaId={}, userId={}, deadlineAt={}", sagaId, user.getId(), deadlineAt);
        return sagaId;
    }

    // match.withdrawal-cleaned.v1
    @Transactional
    public void handleCleaned(WithdrawalResultEvent event) {
        handleResult(event, true);
    }

    // match.withdrawal-rejected.v1
    @Transactional
    public void handleRejected(WithdrawalResultEvent event) {
        handleResult(event, false);
    }

    // 마감 + 유예가 지난 PROCESSING Saga를 TIMED_OUT으로 끝내고 회원을 ACTIVE로 되돌린다. 처리한 건수를 돌려준다.
    public int timeOutExpired() {
        LocalDateTime before = utc(clock.instant().minus(properties.grace()));
        List<String> sagaIds = sagaRepository.findIdsByStatusAndDeadlineBefore(
                SagaStatus.PROCESSING, before, Limit.of(TIMEOUT_BATCH));

        int count = 0;
        for (String sagaId : sagaIds) {
            // 한 건씩 따로 커밋해 한 건 실패가 다른 회원 복구를 막지 않게 한다.
            Boolean done = transaction.execute(status -> timeOutOne(sagaId, before));
            if (Boolean.TRUE.equals(done)) {
                count++;
            }
        }
        return count;
    }

    private boolean timeOutOne(String sagaId, LocalDateTime before) {
        // 잠근 뒤 다시 확인한다. 그사이 결과가 도착했으면 건드리지 않는다.
        WithdrawalSagaEntity saga = sagaRepository.findForUpdate(sagaId).orElse(null);
        if (saga == null || !saga.isProcessing() || !saga.getDeadlineAt().isBefore(before)) {
            return false;
        }
        saga.finish(SagaStatus.TIMED_OUT, null, now());
        userRepository.findById(saga.getUserId()).ifPresent(UserEntity::restoreFromWithdrawing);
        afterCommit(metrics::timedOut);
        log.warn("탈퇴 Saga 시간 초과 → 회원 복구: sagaId={}, userId={}, deadlineAt={}",
                saga.getSagaId(), saga.getUserId(), saga.getDeadlineAt());
        return true;
    }

    private void handleResult(WithdrawalResultEvent event, boolean cleaned) {
        String topic = cleaned ? KafkaTopics.WITHDRAWAL_CLEANED : KafkaTopics.WITHDRAWAL_REJECTED;

        // 1. 필수 값이 없으면 어느 요청의 결과인지 알 수 없다(재시도 없이 DLT로 간다).
        if (isBlank(event.eventId()) || isBlank(event.sagaId()) || isBlank(event.requestEventId())
                || event.userId() == null) {
            throw new IllegalArgumentException("eventId·sagaId·requestEventId·userId가 모두 있어야 합니다.");
        }

        // 2. 같은 결과 이벤트는 한 번만
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("이미 처리한 결과 이벤트라 건너뜀: eventId={}", event.eventId());
            return;
        }

        // 3. 요청 한 건과 맞는지 확인(잠근 뒤). 다른 요청의 결과면 상태를 바꾸지 않는다.
        WithdrawalSagaEntity saga = sagaRepository.findForUpdate(event.sagaId()).orElse(null);
        if (saga == null || !saga.getClaimToken().equals(event.requestEventId())
                || !saga.getUserId().equals(event.userId())) {
            log.warn("요청과 맞지 않는 탈퇴 결과라 무시: topic={}, eventId={}, sagaId={}, requestEventId={}, userId={}",
                    topic, event.eventId(), event.sagaId(), event.requestEventId(), event.userId());
            markProcessed(event.eventId(), topic);
            return;
        }

        // 4. 진행 중이면 결과 적용, 이미 끝났으면 기록만
        if (saga.isProcessing()) {
            apply(saga, event, cleaned);
        } else {
            recordLate(saga, event, cleaned);
        }
        markProcessed(event.eventId(), topic);
    }

    private void apply(WithdrawalSagaEntity saga, WithdrawalResultEvent event, boolean cleaned) {
        UserEntity user = userRepository.findById(saga.getUserId()).orElse(null);
        if (cleaned) {
            saga.finish(SagaStatus.CLEANED, null, now());
            if (user != null) {
                user.completeWithdrawal();
            }
            // Refresh Token은 커밋 뒤 지운다. 실패해도 WITHDRAWN이라 재발급은 막힌다.
            afterCommit(() -> deleteRefreshToken(saga.getUserId()));
            afterCommit(metrics::success);
            log.info("탈퇴 Saga 완료 → WITHDRAWN: sagaId={}, userId={}, canceled={}, left={}",
                    saga.getSagaId(), saga.getUserId(), event.canceledMatchIds(), event.leftMatchIds());
        } else {
            saga.finish(SagaStatus.REJECTED, event.reason(), now());
            if (user != null) {
                user.restoreFromWithdrawing();
            }
            afterCommit(metrics::rejected);
            log.info("탈퇴 Saga 거절 → ACTIVE 복구: sagaId={}, userId={}, reason={}, blocking={}",
                    saga.getSagaId(), saga.getUserId(), event.reason(), event.blockingMatchIds());
        }
    }

    // 이미 끝난 Saga에 온 결과. 회원 상태는 다시 바꾸지 않는다.
    private void recordLate(WithdrawalSagaEntity saga, WithdrawalResultEvent event, boolean cleaned) {
        if (saga.getStatus() != SagaStatus.TIMED_OUT) {
            log.info("이미 {}로 끝난 Saga에 결과가 또 와서 기록만 함: sagaId={}, eventId={}",
                    saga.getStatus(), saga.getSagaId(), event.eventId());
            return;
        }
        if (cleaned) {
            // 매치 쪽은 정리됐는데 회원은 ACTIVE로 복구된 상태. 자동 복구 없이 운영자 확인이 필요하다.
            log.warn("[늦은 CLEANED] 시간 초과로 복구한 회원의 매치가 정리됨. 운영자 확인 필요: sagaId={}, userId={}, "
                            + "eventId={}, canceledMatchIds={}, leftMatchIds={}",
                    saga.getSagaId(), saga.getUserId(), event.eventId(),
                    event.canceledMatchIds(), event.leftMatchIds());
            afterCommit(metrics::lateCleaned);
        } else {
            log.info("시간 초과 뒤 늦은 REJECTED라 기록만 함: sagaId={}, userId={}, reason={}",
                    saga.getSagaId(), saga.getUserId(), event.reason());
            afterCommit(metrics::lateRejected);
        }
    }

    private void markProcessed(String eventId, String topic) {
        processedEventRepository.save(new ProcessedEventEntity(eventId, topic));
    }

    private void deleteRefreshToken(Long userId) {
        try {
            refreshTokenService.delete(userId);
        } catch (RuntimeException e) {
            log.warn("탈퇴 완료 후 Refresh Token 삭제 실패(재발급은 상태 검사로 막힘): userId={}", userId, e);
        }
    }

    // 트랜잭션이 커밋된 뒤에만 실행한다(롤백되면 지표를 올리지 않는다).
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("탈퇴 요청 이벤트를 JSON으로 만들 수 없습니다.", e);
        }
    }

    private LocalDateTime now() {
        return utc(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
