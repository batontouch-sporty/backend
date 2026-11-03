package com.example.sporty.user.points;

import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.commons.handler.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/points")
@RequiredArgsConstructor
class PointController {
    private final PointQueryService points;
    private final PointSettlementService settlement;

    @GetMapping
    PointQueryService.Balance balance(@RequestHeader(AuthHeaders.USER_ID) long userId) {
        // 해제할 때가 지난 정지는 먼저 풀고(별도 쓰기 트랜잭션) 읽는다.
        settlement.expireIfDue(userId);
        return points.balance(userId);
    }

    @GetMapping("/history")
    ResponseEntity<?> history(@RequestHeader(AuthHeaders.USER_ID) long userId,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(ErrorResponse.builder().code("INVALID_INPUT")
                    .message("page는 0 이상, size는 1~100이어야 합니다.").build());
        }
        settlement.expireIfDue(userId);
        return ResponseEntity.ok(points.history(userId, page, size));
    }
}
