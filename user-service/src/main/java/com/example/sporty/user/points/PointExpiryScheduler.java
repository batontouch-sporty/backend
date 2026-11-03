package com.example.sporty.user.points;

import com.example.sporty.user.users.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    // 00시에 서비스가 내려가 있었으면 놓친 해제를 기동 직후 따라잡는다. 실패해도 기동은 막지 않는다(조회·다음 00시에 다시 처리).
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        try {
            expireDue();
        } catch (RuntimeException failure) {
            log.warn("기동 직후 포인트 정지 해제 실패. 조회 또는 다음 00시에 다시 처리합니다.", failure);
        }
    }

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
