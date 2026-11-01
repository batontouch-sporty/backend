package com.example.sporty.user.admin;

import com.example.sporty.user.admin.service.AdminAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

// 기동할 때 관리자 초기 계정을 한 번 확인한다. 실패해도 서비스 기동은 막지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AdminAccountProperties.class)
public class AdminAccountInitializer implements ApplicationRunner {

    private final AdminAccountProperties properties;
    private final AdminAccountService adminAccountService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            adminAccountService.ensureAdmin(properties.email(), properties.password(), properties.nickname());
        } catch (RuntimeException e) {
            // 여러 인스턴스가 동시에 만들려다 유니크 제약에 걸리는 경우 등. 다른 쪽이 만들었으면 문제없다.
            log.warn("관리자 초기 계정 확인 실패(서비스는 계속 기동): {}", e.getMessage());
        }
    }

}
