package com.example.sporty.match.participation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** X-User-Id is supplied by the JWT Gateway; service ports remain private in Compose. */
@RestController
@RequestMapping("/api/matches")
public class ParticipationController {
    private final ParticipationService service;

    public ParticipationController(ParticipationService service) {
        this.service = service;
    }

    @PostMapping("/{matchId}/participants")
    public ResponseEntity<ParticipationService.Participation> join(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        return ResponseEntity.status(201).body(service.join(matchId, userId(identity)));
    }

    @DeleteMapping("/{matchId}/participants/me")
    public ResponseEntity<Void> leave(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        service.leave(matchId, userId(identity));
        return ResponseEntity.noContent().build();
    }

    private static Long userId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (RuntimeException ignored) {
        }
        throw new ParticipationProblem(401, "UNAUTHORIZED", "인증 정보가 필요합니다.");
    }
}
