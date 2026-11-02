package com.example.sporty.ai.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import com.example.sporty.ai.client.dto.MatchDto;
import com.example.sporty.ai.client.dto.MatchSearchQuery;
import com.example.sporty.ai.client.dto.MyMatchDto;

/** match-service 공개 API를 Feign으로 부른다(명세 결정 15: 내부 경로를 따로 만들지 않음). */
@FeignClient(name = "match-service")
public interface MatchClient {

    // A10 매치 목록 조회(검색)
    @GetMapping("/api/matches")
    List<MatchDto> searchMatches(@SpringQueryMap MatchSearchQuery query);

    // A16 내 매치 목록. 추천에서 내가 만들었거나 참가한 매치를 뺀다.
    @GetMapping("/api/matches/me")
    List<MyMatchDto> getMyMatches(@RequestHeader("X-User-Id") Long userId);
}
