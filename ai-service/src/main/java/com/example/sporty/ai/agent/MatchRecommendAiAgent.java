package com.example.sporty.ai.agent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.sporty.ai.client.MatchClient;
import com.example.sporty.ai.client.UserClient;
import com.example.sporty.ai.client.dto.InternalUserDto;
import com.example.sporty.ai.client.dto.MatchDto;
import com.example.sporty.ai.client.dto.MatchSearchQuery;
import com.example.sporty.ai.client.dto.MyMatchDto;
import com.example.sporty.ai.commons.exception.AiException;
import com.example.sporty.ai.domain.dto.AiRecommendDto;
import com.example.sporty.ai.domain.dto.AiRecommendResponseDto;
import com.example.sporty.ai.domain.enums.Gender;
import com.example.sporty.ai.domain.enums.GenderGroup;
import com.example.sporty.ai.domain.enums.MatchStatus;
import com.example.sporty.ai.domain.enums.SportType;
import com.example.sporty.ai.metrics.AiMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A24 맞춤 매치 추천. 1차 AI-03을 옮기고 Feign·Fallback을 더했다.
 * - 회원 404 → 404 USER_NOT_FOUND / 회원 조회 장애 → 혼성 매치 최신순 3개, fallback=true
 * - LLM 실패·타임아웃, 후보 ID를 못 고름 → 후보 최신순 3개, fallback=true (1차는 500이었다)
 * - 매치 목록까지 못 받으면 Fallback도 불가 → 500 AI_RECOMMEND_FAILED
 * 최신순은 matchId 내림차순이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchRecommendAiAgent {

    static final int RECOMMEND_SIZE = 3;
    static final int CANDIDATE_MAX = 20;
    // 컨테이너는 UTC라서 서버 시간대를 따르면 9시간 전에 시작한 매치가 후보에 들어간다(match-service와 같은 기준).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Comparator<MatchDto> LATEST_FIRST =
            Comparator.comparing(MatchDto::getMatchId, Comparator.nullsLast(Comparator.reverseOrder()));

    private final RecommendChat recommendChat;
    private final UserClient userClient;
    private final MatchClient matchClient;
    private final AiMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public AiRecommendResponseDto recommend(Long userId) {
        long start = System.nanoTime();
        String outcome = AiMetrics.OUTCOME_SUCCESS;
        try {
            AiRecommendResponseDto response = doRecommend(userId);
            if (response.isFallback()) {
                outcome = AiMetrics.OUTCOME_FALLBACK;
            }
            return response;
        } catch (AiException e) {
            if (e.getStatus().is5xxServerError()) {
                outcome = AiMetrics.OUTCOME_ERROR;
            }
            throw e;
        } finally {
            metrics.record(AiMetrics.TYPE_RECOMMEND, outcome, start);
        }
    }

    private AiRecommendResponseDto doRecommend(Long userId) {
        // 1. 회원 정보: 성별, 활동 자치구, 선호 종목
        InternalUserDto me;
        try {
            me = userClient.getUser(userId);
        } catch (FeignException.NotFound e) {
            throw AiException.userNotFound();
        } catch (FeignException e) {
            log.warn("AI 추천 중 user-service 호출 실패, 혼성 매치 최신순으로 대체: status={}", e.status());
            return fallback(latest(mixedOnly(recruitingNotMine(userId))));
        }

        // 2. 후보: 모집중·시작 전·내가 안 들어간 매치 → 회원 조건으로 거르기 → 최대 20개
        List<MatchDto> candidates = candidates(me, recruitingNotMine(userId));
        if (candidates.isEmpty()) {
            return AiRecommendResponseDto.builder().matches(List.of()).fallback(false).build();
        }

        // 3. AI가 추천 순서대로 ID를 고른다. 실패하거나 못 고르면 최신순으로 대체한다.
        List<Long> pickedIds;
        try {
            pickedIds = askLlm(me, candidates);
        } catch (RuntimeException e) {
            log.warn("AI 추천 LLM 호출 실패, 최신순으로 대체: {}", e.getClass().getSimpleName());
            return fallback(latest(candidates));
        }
        List<MatchDto> picked = pick(pickedIds, candidates);
        if (picked.isEmpty()) {
            return fallback(latest(candidates));
        }
        return AiRecommendResponseDto.builder().matches(picked).fallback(false).build();
    }

    // 모집 중이고 아직 시작 전인 매치 중 내가 만들었거나 참가한 매치를 뺀다.
    private List<MatchDto> recruitingNotMine(Long userId) {
        List<MatchDto> recruiting;
        try {
            recruiting = matchClient.searchMatches(MatchSearchQuery.builder()
                    .status(MatchStatus.RECRUITING)
                    .startAt(LocalDateTime.now(SEOUL))
                    .build());
        } catch (FeignException e) {
            // 매치 목록이 없으면 대체 결과도 만들 수 없다.
            log.warn("AI 추천 중 match-service 목록 호출 실패: status={}", e.status());
            throw AiException.recommendFailed();
        }
        if (recruiting == null) {
            return List.of();
        }

        Set<Long> mine = myMatchIds(userId);
        return recruiting.stream()
                .filter(match -> !mine.contains(match.getMatchId()))
                .toList();
    }

    private Set<Long> myMatchIds(Long userId) {
        try {
            List<MyMatchDto> my = matchClient.getMyMatches(userId);
            return my == null ? Set.of() : my.stream()
                    .map(MyMatchDto::getMatchId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (FeignException e) {
            // 내 매치 목록을 못 받아도 추천은 계속한다(목록은 이미 받았으므로).
            log.warn("AI 추천 중 내 매치 목록 호출 실패, 제외 없이 진행: status={}", e.status());
            return Set.of();
        }
    }

    // 선호 종목이거나 같은 자치구 + 성별이 맞는 매치, 시작이 가까운 순 (AI 호출 없음)
    List<MatchDto> candidates(InternalUserDto me, List<MatchDto> matches) {
        List<SportType> sports = me.getSportTypes() == null ? List.of() : me.getSportTypes();
        // I1은 자치구 코드(GANGNAM), 매치는 자치구명(강남구)이라 변환해서 비교한다.
        String region = me.getDistrict() == null ? null : me.getDistrict().getName();
        return matches.stream()
                .filter(match -> sports.contains(match.getSportType())
                        || (region != null && region.equals(match.getRegion())))
                .filter(match -> isGenderAllowed(me.getGender(), match.getGenderGroup()))
                .sorted(Comparator.comparing(MatchDto::getStartAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(CANDIDATE_MAX)
                .toList();
    }

    // 혼성은 누구나, 남성/여성 전용은 같은 성별만
    private boolean isGenderAllowed(Gender gender, GenderGroup genderGroup) {
        if (genderGroup == GenderGroup.MIXED) {
            return true;
        }
        return gender != null && genderGroup != null && genderGroup.name().equals(gender.name());
    }

    private List<MatchDto> mixedOnly(List<MatchDto> matches) {
        return matches.stream()
                .filter(match -> match.getGenderGroup() == GenderGroup.MIXED)
                .toList();
    }

    private List<MatchDto> latest(List<MatchDto> matches) {
        return matches.stream().sorted(LATEST_FIRST).limit(RECOMMEND_SIZE).toList();
    }

    private AiRecommendResponseDto fallback(List<MatchDto> matches) {
        return AiRecommendResponseDto.builder().matches(matches).fallback(true).build();
    }

    private List<Long> askLlm(InternalUserDto me, List<MatchDto> candidates) {
        String systemPrompt = """
                당신은 운동 매칭 서비스의 추천 에이전트입니다.

                ## 규칙
                1. 회원 정보와 후보 매치 목록을 보고 회원에게 잘 맞는 순서대로 매치 %d개를 골라 recommendMatches 도구를 호출한다.
                   후보가 %d개보다 적으면 후보 전부를 순서대로 전달한다.
                2. 선호 종목과 활동 자치구가 모두 맞는 매치를 가장 우선하고, 그다음 선호 종목, 그다음 같은 자치구 순으로 고려한다.
                3. 조건이 비슷하면 모집 인원에 여유가 있고 일정이 가까운 매치를 우선한다.
                4. 후보 목록에 있는 ID만 사용하고, 추천 순서대로 전달한다.
                """.formatted(RECOMMEND_SIZE, RECOMMEND_SIZE);

        String result = recommendChat.call(systemPrompt, toPromptText(me, candidates));
        AiRecommendDto recommend = toRecommend(result);
        return recommend == null || recommend.getMatchIds() == null ? List.of() : recommend.getMatchIds();
    }

    private String toPromptText(InternalUserDto me, List<MatchDto> candidates) {
        String sports = me.getSportTypes() == null ? "" : me.getSportTypes().stream()
                .map(SportType::getDescription)
                .collect(Collectors.joining(", "));
        String matches = candidates.stream()
                .map(match -> "- id=%d | %s | %s | %s | %s | %s/%s명 | %s".formatted(
                        match.getMatchId(),
                        match.getSportType() == null ? "-" : match.getSportType().getDescription(),
                        match.getRegion(),
                        match.getStartAt(),
                        match.getGenderGroup() == null ? "-" : match.getGenderGroup().getDescription(),
                        match.getNumCurrentParticipant(), match.getMaxParticipant(),
                        match.getTitle()))
                .collect(Collectors.joining("\n"));
        return """
                ## 회원 정보
                - 활동 자치구: %s
                - 선호 종목: %s

                ## 후보 매치 (id | 종목 | 자치구 | 시작 일시 | 성별 구성 | 인원 | 제목)
                %s
                """.formatted(
                        me.getDistrict() == null ? "미설정" : me.getDistrict().getName(),
                        sports.isBlank() ? "없음" : sports,
                        matches);
    }

    // tool이 호출되면 ID 목록 JSON, 호출되지 않으면 AI의 안내 문장이 온다.
    private AiRecommendDto toRecommend(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(result, AiRecommendDto.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // 후보에 있는 ID만, 중복 없이, 상위 3개. 3개보다 적으면 후보 순서(시작이 가까운 순)로 채운다.
    private List<MatchDto> pick(List<Long> ids, List<MatchDto> candidates) {
        Map<Long, MatchDto> byId = candidates.stream()
                .collect(Collectors.toMap(MatchDto::getMatchId, Function.identity(), (a, b) -> a));
        List<MatchDto> picked = new ArrayList<>(ids.stream()
                .distinct()
                .map(byId::get)
                .filter(Objects::nonNull)
                .limit(RECOMMEND_SIZE)
                .toList());
        if (picked.isEmpty()) {
            return picked;
        }
        for (MatchDto candidate : candidates) {
            if (picked.size() >= RECOMMEND_SIZE) {
                break;
            }
            if (!picked.contains(candidate)) {
                picked.add(candidate);
            }
        }
        return picked;
    }
}
