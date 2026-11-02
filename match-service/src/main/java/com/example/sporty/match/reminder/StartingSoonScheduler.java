package com.example.sporty.match.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "match.starting-soon.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StartingSoonScheduler {
    private static final Logger log = LoggerFactory.getLogger(StartingSoonScheduler.class);
    private final StartingSoonStore store;

    public StartingSoonScheduler(StartingSoonStore store) {
        this.store = store;
    }

    @Scheduled(
            fixedDelayString = "${match.starting-soon.delay-ms:60000}",
            initialDelayString = "${match.starting-soon.initial-delay-ms:60000}")
    public void scan() {
        for (Long id : store.candidates()) {
            try {
                store.enqueue(id);
            } catch (RuntimeException ex) {
                log.warn("시작 전 알림 저장 실패, 다음 스캔에서 재시도합니다. matchId={}", id, ex);
            }
        }
    }
}
