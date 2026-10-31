package com.example.sporty.match.withdrawal;

import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.time.*;
import java.util.*;

/** Saga transaction boundary; no dependency on matching A's Java classes. */
public class WithdrawalProcessor {
    private final WithdrawalStore store;
    private final MemberLocks locks;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WithdrawalProcessor(
            WithdrawalStore store,
            MemberLocks locks,
            PlatformTransactionManager manager,
            Clock clock) {
        this.store = store;
        this.locks = locks;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static class DeadlineExceeded extends RuntimeException {}

    private boolean expired(WithdrawalRequest r) {
        return !clock.instant().isBefore(r.deadlineAt().toInstant());
    }

    /** Roll back expired cleanup before persisting a standalone rejection transaction. */
    public void handle(WithdrawalRequest r) {
        r.validate();
        try {
            tx.executeWithoutResult(s -> process(r));
        } catch (DeadlineExceeded ex) {
            tx.executeWithoutResult(
                    s -> {
                        locks.lock(r.userId());
                        if (!store.processed(r.eventId()) && !store.completed(r))
                            finish(r, "DEADLINE_EXCEEDED", List.of(), List.of(), List.of());
                    });
        }
    }

    /** Lock affected matches, reject running owners, and atomically persist cleanup and results. */
    private void process(WithdrawalRequest r) {
        locks.lock(r.userId());
        if (store.processed(r.eventId())) return;
        if (store.completed(r)) {
            store.mark(r.eventId());
            return;
        }
        if (expired(r)) {
            finish(r, "DEADLINE_EXCEEDED", List.of(), List.of(), List.of());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    public void beforeCommit(boolean readOnly) {
                        if (expired(r)) throw new DeadlineExceeded();
                    }
                });
        var matches = store.lockMatches(r.userId());
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
        var blocking =
                matches.stream()
                        .filter(m -> "OWNER".equals(m.role()))
                        .filter(m -> !Set.of("CANCELLED", "COMPLETED").contains(m.status()))
                        .filter(
                                m ->
                                        "IN_PROGRESS".equals(m.status())
                                                || (!m.start().isAfter(now)
                                                        && m.end().isAfter(now)))
                        .map(WithdrawalStore.Match::id)
                        .toList();
        if (!blocking.isEmpty()) {
            finish(r, "OWNER_OF_IN_PROGRESS_MATCH", List.of(), List.of(), blocking);
            return;
        }
        List<Long> canceled = new ArrayList<>(), left = new ArrayList<>();
        for (var m : matches) {
            if (Set.of("CANCELLED", "COMPLETED").contains(m.status()) || !m.end().isAfter(now))
                continue;
            if ("OWNER".equals(m.role())) {
                var recipients =
                        store.recipients(m.id()).stream()
                                .filter(id -> !id.equals(r.userId()))
                                .toList();
                store.cancel(m.id(), clock.instant());
                canceled.add(m.id());
                status(r, m, m.status(), "CANCELLED", r.userId(), recipients);
            } else {
                store.leave(m.id(), r.userId());
                left.add(m.id());
                if (m.start().isAfter(now) && store.reopen(m, clock.instant()))
                    status(r, m, "CLOSED", "RECRUITING", store.owner(m.id()), List.of());
            }
        }
        finish(r, null, canceled, left, List.of());
    }

    private void status(
            WithdrawalRequest r,
            WithdrawalStore.Match m,
            String from,
            String to,
            long owner,
            List<Long> recipients) {
        store.emit(
                "match.status-changed.v1",
                m.id(),
                Map.of(
                        "matchId",
                        m.id(),
                        "title",
                        m.title(),
                        "from",
                        from,
                        "to",
                        to,
                        "ownerId",
                        owner,
                        "participantIds",
                        recipients,
                        "reason",
                        "CANCELLED".equals(to) ? "모임장 회원 탈퇴로 인한 모임 취소" : "참가자 회원 탈퇴로 인한 모집 재개",
                        "sagaId",
                        r.sagaId()),
                clock.instant());
    }

    /** Correlate the result with the request and record consumption in the same transaction. */
    private void finish(
            WithdrawalRequest r,
            String reason,
            List<Long> canceled,
            List<Long> left,
            List<Long> blocking) {
        boolean cleaned = reason == null;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("sagaId", r.sagaId());
        fields.put("requestEventId", r.eventId());
        fields.put("userId", r.userId());
        fields.put(
                cleaned ? "cleanedAt" : "rejectedAt",
                clock.instant().atOffset(ZoneOffset.UTC).toString());
        if (cleaned) {
            fields.put("canceledMatchIds", canceled);
            fields.put("leftMatchIds", left);
        } else {
            fields.put("reason", reason);
            fields.put("blockingMatchIds", blocking);
        }
        store.emit(
                cleaned ? "match.withdrawal-cleaned.v1" : "match.withdrawal-rejected.v1",
                r.userId(),
                fields,
                clock.instant());
        store.mark(r.eventId());
    }
}
