package com.example.sporty.user.withdrawal.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * match-service 내부 API. 탈퇴 Saga가 마감 + 유예를 넘겼을 때 회원을 되돌리기 전에 match의 처리 결과를 확인한다.
 * match는 정리와 같은 회원 잠금을 잡은 뒤 답하고, 마감 전이면 409, 잠금 대기 초과면 5xx로 답한다.
 * Eureka 이름으로 찾고, user.clients.match.url이 있으면 그 주소로 바로 부른다.
 */
@FeignClient(name = "match-service", contextId = "userMatchWithdrawalClient", url = "${user.clients.match.url:}")
public interface MatchWithdrawalClient {

    @GetMapping("/internal/withdrawals/{sagaId}")
    WithdrawalResult result(@PathVariable("sagaId") String sagaId,
                            @RequestParam("userId") Long userId,
                            @RequestParam("requestEventId") String requestEventId,
                            @RequestParam("deadlineAt") String deadlineAt);

    // result: CLEANED | REJECTED | NONE(마감이 지났고 match가 처리하지 않음)
    record WithdrawalResult(String sagaId, String requestEventId, Long userId, String result) {}
}
