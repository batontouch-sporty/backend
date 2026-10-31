package com.example.sporty.facility.facilities.scheduler;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.sporty.facility.facilities.repository.ServiceRepository;
import com.example.sporty.facility.facilities.service.FacilitySyncService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기동 시 시설 테이블이 비어 있으면 한 번 동기화한다.
 * 1차에서 함께 만들던 데모 매치 생성은 match-service 몫이라 뺐다.
 * API 키가 없거나 호출이 실패해도 서비스 기동은 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "facility.sync.on-startup", havingValue = "true", matchIfMissing = true)
public class FacilityStartupSyncRunner implements ApplicationRunner {

    private final FacilitySyncService facilitySyncService;
    private final ServiceRepository serviceRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (!facilitySyncService.isSyncAvailable()) {
            log.warn("공공데이터 API 키가 없어 기동 시 동기화를 건너뜁니다. DB에 있는 시설 데이터로만 조회합니다.");
            return;
        }
        if (serviceRepository.count() > 0) {
            log.info("시설 데이터가 이미 있어 기동 시 동기화를 건너뜁니다. 정기 동기화는 매일 03:00에 실행됩니다.");
            return;
        }

        try {
            log.info("시설 테이블이 비어 있어 기동 시 동기화를 시작합니다.");
            facilitySyncService.syncAll();
        } catch (Exception e) {
            log.error("기동 시 체육시설 동기화에 실패했습니다: {}", e.getClass().getSimpleName());
        }
    }
}
