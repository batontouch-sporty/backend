package com.example.sporty.ai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.sporty.ai.agent.MatchDraftAiAgent;
import com.example.sporty.ai.domain.dto.AiDraftResponseDto;
import com.example.sporty.ai.domain.dto.AiRequestDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * AI 매칭 API. Gateway 경로는 /ai-service/api/ai/**.
 * A22 검색·A24 추천은 match CRUD(#10) 머지 후 Feign 연동과 함께 추가한다.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final MatchDraftAiAgent matchDraftAiAgent;

    // A23 (1차 AI-01): 매치 초안 생성. DB에 저장하지 않고 개설 폼에 채울 값만 돌려준다.
    // 로그인 필요(Gateway가 JWT 검증). body: { "prompt": "토요일 저녁 7시 송파에서 농구 10명 모집" }
    @PostMapping("/matches")
    public ResponseEntity<AiDraftResponseDto> draftMatch(@Valid @RequestBody AiRequestDto request) {
        return ResponseEntity.ok(matchDraftAiAgent.draft(request.getPrompt()));
    }
}
