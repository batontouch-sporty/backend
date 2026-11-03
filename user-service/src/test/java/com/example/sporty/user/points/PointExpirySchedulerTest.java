package com.example.sporty.user.points;

import com.example.sporty.user.users.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import static org.mockito.Mockito.*;

class PointExpirySchedulerTest {
    @Test
    void continuesExpiringOtherMembersWhenOneIsLocked() {
        var clock = Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneOffset.UTC);
        var users = mock(UserRepository.class);
        var settlement = mock(PointSettlementService.class);
        when(users.findDueSuspensions(LocalDateTime.parse("2026-11-03T00:00:00")))
                .thenReturn(List.of(11L, 12L, 13L));
        doThrow(new CannotAcquireLockException("lock timeout")).when(settlement).expireIfDue(12L);

        new PointExpiryScheduler(users, settlement, clock).expireDue();

        var attempts = inOrder(settlement);
        attempts.verify(settlement).expireIfDue(11L);
        attempts.verify(settlement).expireIfDue(12L);
        attempts.verify(settlement).expireIfDue(13L);
    }
}
