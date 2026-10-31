package com.example.sporty.match.withdrawal;
import java.time.*;
import java.util.UUID;
public record WithdrawalRequest(String eventId,String sagaId,Long userId,OffsetDateTime requestedAt,OffsetDateTime deadlineAt) {
    /** Require valid identifiers and exactly five minutes between offset-aware timestamps. */
    public void validate() {
        UUID.fromString(eventId);UUID.fromString(sagaId);
        if(userId==null || userId<=0 || requestedAt==null || deadlineAt==null
            || !requestedAt.toInstant().plusSeconds(300).equals(deadlineAt.toInstant()))
            throw new IllegalArgumentException("Invalid withdrawal request or deadline");
    }
    /** Compare absolute instants rather than local wall-clock offsets. */
    public boolean expired(){return !Instant.now().isBefore(deadlineAt.toInstant());}
}
