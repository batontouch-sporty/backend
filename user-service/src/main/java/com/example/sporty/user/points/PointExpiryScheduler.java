package com.example.sporty.user.points;

import com.example.sporty.user.users.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@RequiredArgsConstructor
class PointExpiryScheduler {
    private final UserRepository users;
    private final PointSettlementService settlement;
    private final Clock clock;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void expireDue() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), PointSettlementService.SEOUL);
        users.findDueSuspensions(now).forEach(settlement::expireIfDue);
    }
}
