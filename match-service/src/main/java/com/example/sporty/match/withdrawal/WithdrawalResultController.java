package com.example.sporty.match.withdrawal;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import javax.sql.DataSource;

/**
 * user-service asks this after deadline + grace before restoring a member.
 *
 * <p>The member lock is the first statement of the transaction, so a running cleanup is waited
 * for. The transaction is READ_COMMITTED so the outbox read after the lock sees that commit
 * whatever snapshot rules apply (a FOR UPDATE read would lock outbox rows the relay updates).
 * NONE is returned only past deadlineAt; any later processing of the request then fails the
 * processor's start/commit deadline check and ends as DEADLINE_EXCEEDED. Before the deadline the
 * answer is 409, a lock timeout is 5xx: user-service keeps the member WITHDRAWING on both.
 *
 * <p>Always on: with match.withdrawal.enabled=false nothing is processed, so NONE is the true answer.
 */
@RestController
public class WithdrawalResultController {
    private final WithdrawalStore store;
    private final MemberLocks locks;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public WithdrawalResultController(
            DataSource ds, JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this(new WithdrawalStore(jdbc, mapper), new MemberLocks(ds), manager, Clock.systemUTC());
    }

    WithdrawalResultController(
            WithdrawalStore store, MemberLocks locks, PlatformTransactionManager manager, Clock clock) {
        this.store = store;
        this.locks = locks;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @GetMapping("/internal/withdrawals/{sagaId}")
    public WithdrawalStore.SagaResult result(
            @PathVariable String sagaId,
            @RequestParam long userId,
            @RequestParam String requestEventId,
            @RequestParam String deadlineAt) {
        OffsetDateTime deadline;
        try {
            deadline = OffsetDateTime.parse(deadlineAt);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deadlineAt 형식 오류");
        }
        return tx.execute(
                s -> {
                    locks.lock(userId);
                    var found = store.result(sagaId, userId);
                    if (found != null) return found;
                    if (clock.instant().isBefore(deadline.toInstant()))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "마감 전이라 아직 결정할 수 없음");
                    return new WithdrawalStore.SagaResult(sagaId, requestEventId, userId, "NONE");
                });
    }
}
