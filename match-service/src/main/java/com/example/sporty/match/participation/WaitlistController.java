package com.example.sporty.match.participation;

import com.example.sporty.match.waitlist.WaitlistService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/matches/{matchId}/waitlist")
public class WaitlistController {
    private final WaitlistService service;

    public WaitlistController(WaitlistService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WaitlistService.Registration> register(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        return ResponseEntity.status(201)
                .body(service.register(matchId, ParticipationController.userId(identity)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> cancel(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        service.cancel(matchId, ParticipationController.userId(identity));
        return ResponseEntity.noContent().build();
    }
}
