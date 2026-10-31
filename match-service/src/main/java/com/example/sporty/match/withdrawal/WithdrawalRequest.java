package com.example.sporty.match.withdrawal;

import java.time.*;
import java.util.UUID;

public record WithdrawalRequest(
        String eventId,
        String sagaId,
        Long userId,
        OffsetDateTime requestedAt,
        OffsetDateTime deadlineAt) {
    /** Require offset-aware timestamps with a positive deadline of at most five minutes. */
    public void validate() {
        UUID.fromString(eventId);
        UUID.fromString(sagaId);
        if (userId == null
                || userId <= 0
                || requestedAt == null
                || deadlineAt == null
                || !deadlineAt.toInstant().isAfter(requestedAt.toInstant())
                || deadlineAt.toInstant().isAfter(requestedAt.toInstant().plusSeconds(300)))
            throw new IllegalArgumentException("Invalid withdrawal request or deadline");
    }
}
