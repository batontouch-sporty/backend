package com.example.sporty.match.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "match.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class MatchLifecycleScheduler {
    private static final Logger log = LoggerFactory.getLogger(MatchLifecycleScheduler.class);
    private final MatchLifecycle lifecycle;

    public MatchLifecycleScheduler(MatchLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(
            fixedDelayString = "${match.lifecycle.delay-ms:60000}",
            initialDelayString = "${match.lifecycle.initial-delay-ms:60000}")
    public void scan() {
        long cursor = 0;
        for (var page = lifecycle.candidates(cursor); !page.isEmpty(); page = lifecycle.candidates(cursor)) {
            for (Long id : page) {
                try {
                    lifecycle.advance(id);
                } catch (RuntimeException ex) {
                    log.warn("매치 상태 전이 실패, 다음 스캔에서 재시도합니다. matchId={}", id, ex);
                }
            }
            cursor = page.get(page.size() - 1);
        }
    }
}
