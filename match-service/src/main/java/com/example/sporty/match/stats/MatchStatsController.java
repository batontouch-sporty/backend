package com.example.sporty.match.stats;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gateway verifies JWTs and replaces client-supplied identity/role headers. */
@RestController
@RequestMapping("/api/admin")
public class MatchStatsController {
    private final MatchStatsService service;

    public MatchStatsController(MatchStatsService service) {
        this.service = service;
    }

    private record ErrorResponse(String code, String message) {}

    @GetMapping("/stats")
    public ResponseEntity<?> stats(
            @RequestHeader(value = "X-User-Id", required = false) String identity,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!validIdentity(identity))
            return ResponseEntity.status(401).body(new ErrorResponse("UNAUTHORIZED", "로그인이 필요합니다."));
        if (!"ADMIN".equals(role))
            return ResponseEntity.status(403).body(new ErrorResponse("FORBIDDEN", "관리자 권한이 필요합니다."));
        return ResponseEntity.ok(service.getStats());
    }

    private static boolean validIdentity(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
