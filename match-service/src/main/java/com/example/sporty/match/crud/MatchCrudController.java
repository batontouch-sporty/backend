package com.example.sporty.match.crud;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matches")
public class MatchCrudController {
    private final MatchCrudService service;

    public MatchCrudController(MatchCrudService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Long> create(
            @RequestHeader(value = "X-User-Id", required = false) String identity,
            @Valid @RequestBody MatchDtos.Create request) {
        return ResponseEntity.status(201).body(service.create(user(identity, true), request));
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
