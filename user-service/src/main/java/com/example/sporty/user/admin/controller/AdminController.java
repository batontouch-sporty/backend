package com.example.sporty.user.admin.controller;

import com.example.sporty.user.admin.domain.dto.AdminStatsResponseDto;
import com.example.sporty.user.admin.service.AdminStatsService;
import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.commons.exception.auth.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminStatsService adminStatsService;

    // [A28] 탈퇴 Saga·회원 통계
    // Gateway가 ADMIN 토큰만 통과시키지만, Gateway를 거치지 않은 호출도 막도록 역할 헤더를 한 번 더 본다.
    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponseDto> getStats(
            @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @RequestHeader(value = AuthHeaders.USER_ROLE, required = false) String role) {

        if (!"ADMIN".equals(role)) {
            throw new ForbiddenException();
        }
        return ResponseEntity.ok(adminStatsService.getStats());
    }

}
