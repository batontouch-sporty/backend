package com.example.sporty.ai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.sporty.ai.agent.MatchDraftAiAgent;
import com.example.sporty.ai.agent.MatchRecommendAiAgent;
import com.example.sporty.ai.agent.MatchSearchAiAgent;
import com.example.sporty.ai.domain.dto.AiDraftResponseDto;
import com.example.sporty.ai.domain.dto.AiRecommendResponseDto;
import com.example.sporty.ai.domain.dto.AiRequestDto;
import com.example.sporty.ai.domain.dto.AiSearchResponseDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** AI 매칭 API. Gateway 경로는 /ai-service/api/ai/**. 로그인 사용자는 Gateway가 X-User-Id로 넘긴다. */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final MatchSearchAiAgent matchSearchAiAgent;
    private final MatchDraftAiAgent matchDraftAiAgent;
    private final MatchRecommendAiAgent matchRecommendAiAgent;

    // A22 (1차 AI-02): 자연어 매치 검색. 로그인 불필요
    @PostMapping("/matches/search")
    public ResponseEntity<AiSearchResponseDto> searchMatches(@Valid @RequestBody AiRequestDto request) {
        return ResponseEntity.ok(matchSearchAiAgent.search(request.getPrompt()));
    }

    // A23 (1차 AI-01): 매치 초안 생성. DB에 저장하지 않고 개설 폼에 채울 값만 돌려준다.
    @PostMapping("/matches")
    public ResponseEntity<AiDraftResponseDto> draftMatch(@Valid @RequestBody AiRequestDto request) {
        return ResponseEntity.ok(matchDraftAiAgent.draft(request.getPrompt()));
    }

    // A24 (1차 AI-03): 맞춤 매치 추천. 로그인 필요
    @GetMapping("/matches/recommendations")
    public ResponseEntity<AiRecommendResponseDto> recommendMatches(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(matchRecommendAiAgent.recommend(userId));
    }
}
