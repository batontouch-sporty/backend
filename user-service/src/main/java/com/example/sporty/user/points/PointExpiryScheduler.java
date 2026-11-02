package com.example.sporty.user.points;

import com.example.sporty.user.users.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
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
        for (Long userId : users.findDueSuspensions(now)) {
            try {
                settlement.expireIfDue(userId);
            } catch (RuntimeException failure) {
                log.warn("회원 {} 포인트 정지 해제 실패. 다음 만료 확인에서 재시도합니다.", userId, failure);
            }
        }
    }
}
