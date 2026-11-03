package com.example.sporty.match.participation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class OrderedEventIdTest {
    @Test
    void idsSortInGenerationOrderEvenWithinOneMillisecond() {
        String previous = ParticipationEvents.orderedEventId();
        for (int i = 0; i < 20_000; i++) {
            String next = ParticipationEvents.orderedEventId();
            // Relay ORDER BY created_at,event_id compares the text form.
            assertThat(next).isGreaterThan(previous);
            assertThat(UUID.fromString(next).version()).isEqualTo(7);
            assertThat(UUID.fromString(next).variant()).isEqualTo(2);
            previous = next;
        }
    }
}
