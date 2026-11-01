package com.example.sporty.facility.facilities.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.sporty.facility.facilities.service.FacilitySyncService;
import com.example.sporty.facility.facilities.service.FacilitySyncService.SyncResult;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FacilitySyncScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(FacilitySyncScheduler.class);

    private final FacilitySyncService facilitySyncService;

    @Scheduled(
            cron = "${facility.sync.cron:0 0 3 * * *}",
            zone = "${facility.sync.zone:Asia/Seoul}"
    )
    public void synchronizeFacilities() {
        if (!facilitySyncService.isSyncAvailable()) {
            log.warn("공공데이터 API 키가 없어 정기 동기화를 건너뜁니다.");
            return;
        }

        log.info("서울시 체육시설 정기 동기화를 시작합니다.");

        try {
            SyncResult result = facilitySyncService.syncAll();
            log.info(
                    "정기 동기화 성공: 조회 {}, 신규 {}, 갱신 {}, 비활성화 {}, 제외 {}",
                    result.fetchedCount(),
                    result.createdCount(),
                    result.updatedCount(),
                    result.deactivatedCount(),
                    result.skippedCount()
            );
        } catch (Exception exception) {
            log.error(
                    "서울시 체육시설 정기 동기화 실패: {}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
