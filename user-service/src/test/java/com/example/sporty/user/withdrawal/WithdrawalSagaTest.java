package com.example.sporty.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.exception.users.PasswordMismatchException;
import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.commons.exception.users.WithdrawalInProgressException;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.users.service.UserService;
import com.example.sporty.user.withdrawal.domain.entity.OutboxEventEntity;
import com.example.sporty.user.withdrawal.domain.entity.SagaStatus;
import com.example.sporty.user.withdrawal.domain.entity.WithdrawalSagaEntity;
import com.example.sporty.user.withdrawal.events.KafkaTopics;
import com.example.sporty.user.withdrawal.events.dto.WithdrawalResultEvent;
import com.example.sporty.user.withdrawal.repository.OutboxEventRepository;
import com.example.sporty.user.withdrawal.repository.ProcessedEventRepository;
import com.example.sporty.user.withdrawal.repository.WithdrawalSagaRepository;
import com.example.sporty.user.withdrawal.service.WithdrawalSagaService;

/**
 * 탈퇴 Saga 회원 측(R7): 접수 202 → 결과 적용 → 시간 초과 보상 → 늦은 결과 기록.
 * 서비스 트랜잭션이 실제로 커밋되어야 커밋 뒤 동작(지표·Refresh Token 삭제)을 확인할 수 있어 테스트 트랜잭션은 쓰지 않는다.
 * 시각은 Clock을 바꿔 끼워 움직인다. Kafka 없이 서비스 메서드를 직접 부른다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user_saga_test;MODE=MariaDB;DB_CLOSE_DELAY=-1")
class WithdrawalSagaTest {
    static final String PASSWORD = "TestPass123!";
    static final Instant T0 = Instant.parse("2026-11-01T03:00:00Z");

    @Autowired UserService userService;
    @Autowired WithdrawalSagaService sagaService;
    @Autowired UserRepository users;
    @Autowired WithdrawalSagaRepository sagas;
    @Autowired OutboxEventRepository outbox;
    @Autowired ProcessedEventRepository processed;
    @Autowired PasswordEncoder passwords;
    @Autowired MeterRegistry meters;
    @Autowired ObjectMapper json;
    @MockitoBean RefreshTokenService refreshTokens;
    @MockitoBean Clock clock;

    @BeforeEach
    void setUp() {
        processed.deleteAllInBatch();
        outbox.deleteAllInBatch();
        sagas.deleteAllInBatch();
        at(T0);
    }

    // 접수: 202 응답 값, 회원 WITHDRAWING, Saga PROCESSING, 요청 이벤트 Outbox
    @Test
    void acceptStoresSagaAndRequestEventInOneTransaction() throws Exception {
        UserEntity user = user(UserStatus.ACTIVE);

        var response = userService.withdrawal(user.getId(), PASSWORD);

        assertThat(response.getStatus()).isEqualTo(UserStatus.WITHDRAWING);
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWING);

        WithdrawalSagaEntity saga = sagas.findById(response.getSagaId()).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.PROCESSING);
        assertThat(saga.getUserId()).isEqualTo(user.getId());
        assertThat(saga.getRequestedAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 3, 0));
        assertThat(saga.getDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 3, 5));
        assertThat(saga.getFinishedAt()).isNull();

        OutboxEventEntity event = outbox.findById(saga.getClaimToken()).orElseThrow();
        assertThat(event.getEventType()).isEqualTo(KafkaTopics.WITHDRAWAL_REQUESTED);
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(user.getId()));
        assertThat(event.getPublishedAt()).isNull();
        JsonNode payload = json.readTree(event.getPayload());
        assertThat(payload.path("eventId").asText()).isEqualTo(saga.getClaimToken());
        assertThat(payload.path("sagaId").asText()).isEqualTo(saga.getSagaId());
        assertThat(payload.path("userId").asLong()).isEqualTo(user.getId());
        assertThat(payload.path("requestedAt").asText()).isEqualTo("2026-11-01T03:00Z");
        assertThat(payload.path("deadlineAt").asText()).isEqualTo("2026-11-01T03:05Z");
        verify(refreshTokens, never()).delete(user.getId());
    }

    // 진행 중 재요청 409, Saga는 하나만
    @Test
    void secondRequestWhileWithdrawingIsInProgressConflict() {
        UserEntity user = user(UserStatus.ACTIVE);
        userService.withdrawal(user.getId(), PASSWORD);

        assertThatThrownBy(() -> userService.withdrawal(user.getId(), PASSWORD))
                .isInstanceOf(WithdrawalInProgressException.class);
        assertThat(sagas.findAll()).hasSize(1);
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void wrongOrMissingPasswordChangesNothing() {
        UserEntity user = user(UserStatus.ACTIVE);
        for (String password : new String[]{null, "", " ", "wrong"}) {
            assertThatThrownBy(() -> userService.withdrawal(user.getId(), password))
                    .isInstanceOf(PasswordMismatchException.class);
        }
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(sagas.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void unknownOrAlreadyWithdrawnUserIsNotFound() {
        UserEntity withdrawn = user(UserStatus.WITHDRAWN);
        assertThatThrownBy(() -> userService.withdrawal(withdrawn.getId(), PASSWORD))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> userService.withdrawal(withdrawn.getId() + 1000, PASSWORD))
                .isInstanceOf(UserNotFoundException.class);
        assertThat(sagas.findAll()).isEmpty();
    }

    // CLEANED → WITHDRAWN + Refresh Token 삭제 + success
    @Test
    void cleanedResultCompletesWithdrawal() {
        double before = results("success");
        UserEntity user = user(UserStatus.ACTIVE);
        WithdrawalSagaEntity saga = accept(user);
        at(T0.plusSeconds(10));

        sagaService.handleCleaned(cleaned(saga));

        UserEntity reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(reloaded.getWithdrawnAt()).isNotNull();
        WithdrawalSagaEntity done = sagas.findById(saga.getSagaId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SagaStatus.CLEANED);
        assertThat(done.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 3, 0, 10));
        verify(refreshTokens).delete(user.getId());
        assertThat(results("success")).isEqualTo(before + 1);
    }

    // REJECTED → ACTIVE 복구 + 사유 기록 + rejected, Refresh Token 유지
    @Test
    void rejectedResultRestoresActiveUser() {
        double before = results("rejected");
        UserEntity user = user(UserStatus.ACTIVE);
        WithdrawalSagaEntity saga = accept(user);

        sagaService.handleRejected(rejected(saga, "OWNER_OF_IN_PROGRESS_MATCH"));

        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        WithdrawalSagaEntity done = sagas.findById(saga.getSagaId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SagaStatus.REJECTED);
        assertThat(done.getReason()).isEqualTo("OWNER_OF_IN_PROGRESS_MATCH");
        assertThat(done.getFinishedAt()).isNotNull();
        verify(refreshTokens, never()).delete(user.getId());
        assertThat(results("rejected")).isEqualTo(before + 1);
    }

    // 같은 결과 이벤트를 두 번 받아도 한 번만
    @Test
    void sameResultEventIsAppliedOnce() {
        double before = results("success");
        UserEntity user = user(UserStatus.ACTIVE);
        WithdrawalSagaEntity saga = accept(user);
        WithdrawalResultEvent event = cleaned(saga);

        sagaService.handleCleaned(event);
        sagaService.handleCleaned(event);

        assertThat(results("success")).isEqualTo(before + 1);
        assertThat(processed.findAll()).hasSize(1);
    }

    // sagaId·requestEventId·userId가 요청과 맞지 않으면 상태를 바꾸지 않는다
    @Test
    void resultNotMatchingRequestIsIgnored() {
        UserEntity user = user(UserStatus.ACTIVE);
        WithdrawalSagaEntity saga = accept(user);

        sagaService.handleCleaned(new WithdrawalResultEvent(uuid(), saga.getSagaId(), uuid(), user.getId(),
                null, List.of(), List.of(), null));
        sagaService.handleCleaned(new WithdrawalResultEvent(uuid(), saga.getSagaId(), saga.getClaimToken(),
                user.getId() + 1000, null, List.of(), List.of(), null));
        sagaService.handleRejected(new WithdrawalResultEvent(uuid(), uuid(), saga.getClaimToken(), user.getId(),
                "OWNER_OF_IN_PROGRESS_MATCH", null, null, List.of()));

        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWING);
        assertThat(sagas.findById(saga.getSagaId()).orElseThrow().getStatus()).isEqualTo(SagaStatus.PROCESSING);
        assertThat(processed.findAll()).hasSize(3);
    }

    // 필수 값이 빠진 결과는 처리하지 않는다(재시도 없이 DLT)
    @Test
    void resultWithoutRequiredIdsIsRejectedAsInvalid() {
        assertThatThrownBy(() -> sagaService.handleCleaned(new WithdrawalResultEvent(uuid(), uuid(), null, 1L,
                null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sagaService.handleRejected(new WithdrawalResultEvent(null, uuid(), uuid(), 1L,
                null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    // 마감 5분 + 유예 30초 전에는 그대로, 지나면 TIMED_OUT + ACTIVE 복구
    @Test
    void sagaTimesOutOnlyAfterDeadlinePlusGrace() {
        double before = results("timed_out");
        UserEntity user = user(UserStatus.ACTIVE);
        WithdrawalSagaEntity saga = accept(user);

        at(T0.plus(Duration.ofMinutes(5)).plusSeconds(29));
        assertThat(sagaService.timeOutExpired()).isZero();
        assertThat(sagas.findById(saga.getSagaId()).orElseThrow().getStatus()).isEqualTo(SagaStatus.PROCESSING);

        at(T0.plus(Duration.ofMinutes(5)).plusSeconds(31));
        assertThat(sagaService.timeOutExpired()).isEqualTo(1);

        WithdrawalSagaEntity done = sagas.findById(saga.getSagaId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SagaStatus.TIMED_OUT);
        assertThat(done.getFinishedAt()).isEqualTo(LocalDateTime.ofInstant(
                T0.plus(Duration.ofMinutes(5)).plusSeconds(31), ZoneOffset.UTC));
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(results("timed_out")).isEqualTo(before + 1);
        assertThat(sagaService.timeOutExpired()).isZero();
    }

    // 시간 초과 뒤 늦게 온 CLEANED·REJECTED는 회원 상태를 바꾸지 않고 기록만(늦은 CLEANED는 경보용 Counter)
    @Test
    void lateResultsAfterTimeoutAreRecordedWithoutChangingUser() {
        double lateCleaned = late("cleaned");
        double lateRejected = late("rejected");
        double success = results("success");
        UserEntity first = user(UserStatus.ACTIVE);
        UserEntity second = user(UserStatus.ACTIVE);
        WithdrawalSagaEntity firstSaga = accept(first);
        WithdrawalSagaEntity secondSaga = accept(second);
        at(T0.plus(Duration.ofMinutes(6)));
        assertThat(sagaService.timeOutExpired()).isEqualTo(2);

        sagaService.handleCleaned(cleaned(firstSaga));
        sagaService.handleRejected(rejected(secondSaga, "DEADLINE_EXCEEDED"));

        assertThat(users.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(sagas.findById(firstSaga.getSagaId()).orElseThrow().getStatus()).isEqualTo(SagaStatus.TIMED_OUT);
        assertThat(late("cleaned")).isEqualTo(lateCleaned + 1);
        assertThat(late("rejected")).isEqualTo(lateRejected + 1);
        assertThat(results("success")).isEqualTo(success);
        verify(refreshTokens, never()).delete(first.getId());
    }

    // 결과로 끝난 Saga는 시간이 지나도 TIMED_OUT으로 바뀌지 않는다
    @Test
    void finishedSagaIsNotTimedOut() {
        UserEntity user = user(UserStatus.ACTIVE);
        WithdrawalSagaEntity saga = accept(user);
        sagaService.handleCleaned(cleaned(saga));

        at(T0.plus(Duration.ofMinutes(10)));
        assertThat(sagaService.timeOutExpired()).isZero();
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    // 경보·패널이 처음부터 값을 보도록 Counter 5개가 기동 때 등록돼 있다
    @Test
    void countersAreRegisteredAtStartup() {
        for (String outcome : new String[]{"success", "rejected", "timed_out"}) {
            assertThat(meters.find("sporty.withdrawal.saga.results").tag("outcome", outcome).counter()).isNotNull();
        }
        for (String outcome : new String[]{"cleaned", "rejected"}) {
            assertThat(meters.find("sporty.withdrawal.saga.late.results").tag("outcome", outcome).counter()).isNotNull();
        }
    }

    private WithdrawalSagaEntity accept(UserEntity user) {
        String sagaId = userService.withdrawal(user.getId(), PASSWORD).getSagaId();
        return sagas.findById(sagaId).orElseThrow();
    }

    private WithdrawalResultEvent cleaned(WithdrawalSagaEntity saga) {
        return new WithdrawalResultEvent(uuid(), saga.getSagaId(), saga.getClaimToken(), saga.getUserId(),
                null, List.of(11L), List.of(12L), null);
    }

    private WithdrawalResultEvent rejected(WithdrawalSagaEntity saga, String reason) {
        return new WithdrawalResultEvent(uuid(), saga.getSagaId(), saga.getClaimToken(), saga.getUserId(),
                reason, null, null, List.of(13L));
    }

    private UserEntity user(UserStatus status) {
        return users.save(UserEntity.builder().email(uuid().substring(0, 20) + "@test.io")
                .password(passwords.encode(PASSWORD)).gender(Gender.MALE).status(status).build());
    }

    private void at(Instant instant) {
        when(clock.instant()).thenReturn(instant);
    }

    private double results(String outcome) {
        return meters.get("sporty.withdrawal.saga.results").tag("outcome", outcome).counter().count();
    }

    private double late(String outcome) {
        return meters.get("sporty.withdrawal.saga.late.results").tag("outcome", outcome).counter().count();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
