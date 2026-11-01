package com.example.sporty.user.withdrawal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 Saga 지표. Prometheus 이름은 끝에 _total이 붙는다.
 * - sporty_withdrawal_saga_results_total{outcome=success|rejected|timed_out}: 최종 상태를 확정할 때 1번(모니터링 대시보드 README 이름)
 * - sporty_withdrawal_saga_late_results_total{outcome=cleaned|rejected}: 이미 TIMED_OUT으로 끝난 Saga에 결과가 늦게 왔을 때.
 *   늦은 cleaned는 Prometheus 경보 대상(#37). 자동 복구는 하지 않는다.
 * 경보·패널이 처음부터 0을 보도록 모든 값을 기동 때 등록한다.
 */
@Component
public class WithdrawalSagaMetrics {

    static final String RESULTS = "sporty.withdrawal.saga.results";
    static final String LATE_RESULTS = "sporty.withdrawal.saga.late.results";

    private final Counter success;
    private final Counter rejected;
    private final Counter timedOut;
    private final Counter lateCleaned;
    private final Counter lateRejected;

    public WithdrawalSagaMetrics(MeterRegistry registry) {
        success = counter(registry, RESULTS, "success", "탈퇴 Saga 최종 결과");
        rejected = counter(registry, RESULTS, "rejected", "탈퇴 Saga 최종 결과");
        timedOut = counter(registry, RESULTS, "timed_out", "탈퇴 Saga 최종 결과");
        lateCleaned = counter(registry, LATE_RESULTS, "cleaned", "시간 초과 뒤 늦게 도착한 탈퇴 Saga 결과");
        lateRejected = counter(registry, LATE_RESULTS, "rejected", "시간 초과 뒤 늦게 도착한 탈퇴 Saga 결과");
    }

    public void success() { success.increment(); }

    public void rejected() { rejected.increment(); }

    public void timedOut() { timedOut.increment(); }

    public void lateCleaned() { lateCleaned.increment(); }

    public void lateRejected() { lateRejected.increment(); }

    private static Counter counter(MeterRegistry registry, String name, String outcome, String description) {
        return Counter.builder(name)
                .description(description)
                .tag("outcome", outcome)
                .register(registry);
    }

}
