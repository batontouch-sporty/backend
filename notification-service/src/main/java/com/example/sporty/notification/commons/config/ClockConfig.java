package com.example.sporty.notification.commons.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 알림 문구의 "오늘/내일"을 정할 때 쓰는 현재 시각. 테스트에서 고정 시각으로 바꿀 수 있게 빈으로 둔다.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
