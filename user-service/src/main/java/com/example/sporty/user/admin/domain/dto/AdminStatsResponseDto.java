package com.example.sporty.user.admin.domain.dto;

import lombok.Builder;
import lombok.Getter;

// [A28] 관리자 탈퇴 Saga·회원 통계. 누적값, 기간 조건 없음. 없는 상태도 0으로 채운다.
@Builder
@Getter
public class AdminStatsResponseDto {

    private WithdrawalSagaStats withdrawalSaga;
    private UserStats users;

    // requested = 전체 Saga, succeeded = CLEANED, rejected = REJECTED, timedOut = TIMED_OUT, inProgress = PROCESSING
    @Builder
    @Getter
    public static class WithdrawalSagaStats {
        private long requested;
        private long succeeded;
        private long rejected;
        private long timedOut;
        private long inProgress;
    }

    @Builder
    @Getter
    public static class UserStats {
        private long active;
        private long withdrawing;
        private long withdrawn;
    }

}
