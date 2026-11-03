package com.example.sporty.match.crud;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matches")
public class MatchCrudController {
    private final MatchCrudService service;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.sporty.match.deposit.DepositSaga deposits;

    public MatchCrudController(MatchCrudService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "X-User-Id", required = false) String identity,
            @Valid @RequestBody MatchDtos.Create request) {
        long userId=user(identity,true);
        var result=service.createResult(userId,request);
        return deposits.isEnabled() ? ResponseEntity.accepted().body(result.accepted())
                : ResponseEntity.status(201).body(result.matchId());
    }

    @GetMapping
    public List<MatchDtos.Summary> search(@Valid @ModelAttribute MatchDtos.Search request) {
        return service.search(request);
    }

    @GetMapping("/me")
    public List<MatchDtos.Mine> mine(
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        return service.mine(user(identity, true));
    }

    @GetMapping("/{matchId}")
    public MatchDtos.Detail detail(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        return service.detail(matchId, user(identity, false));
    }

    @PutMapping("/{matchId}")
    public MatchDtos.Detail update(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity,
            @Valid @RequestBody MatchDtos.Update request) {
        return service.update(matchId, user(identity, true), request);
    }

    @DeleteMapping("/{matchId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        service.delete(matchId, user(identity, true));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{matchId}/cancel")
    public MatchDtos.Cancelled cancel(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-User-Id", required = false) String identity) {
        return service.cancel(matchId, user(identity, true));
    }

    @PatchMapping("/{matchId}/participants/{participantId}/attendance")
    public MatchDtos.Attendance attendance(
            @PathVariable Long matchId,
            @PathVariable Long participantId,
            @RequestHeader(value = "X-User-Id", required = false) String identity,
            @Valid @RequestBody MatchDtos.AttendanceUpdate request) {
        return service.attendance(matchId, participantId, user(identity, true), request);
    }

    private static Long user(String identity, boolean required) {
        if (identity == null && !required) return null;
        try {
            long id = Long.parseLong(identity);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new MatchCrudProblem(401, "UNAUTHORIZED", "로그인이 필요합니다.");
    }
}
