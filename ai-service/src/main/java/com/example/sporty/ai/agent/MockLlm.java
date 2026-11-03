package com.example.sporty.ai.agent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.sporty.ai.domain.dto.AiSearchConditionDto;
import com.example.sporty.ai.domain.enums.District;
import com.example.sporty.ai.util.KeywordConditionExtractor;
import com.example.sporty.ai.util.PromptDateTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.extern.slf4j.Slf4j;

/**
 * 시연 대비 목업 LLM. ai.mock.enabled=true(AI_MOCK_ENABLED=true)일 때만 OpenAI 대신 쓴다.
 * OpenAI를 부르지 않고 문장 속 단어(종목·자치구·요일·시각·인원)로 도구 호출 결과와 같은 JSON을 만든다.
 * 그 뒤 처리(match 검색, 시설 후보 I4, 추천 후보 거르기)는 실제와 같다.
 */
@Slf4j
@Component
public class MockLlm {

    private static final Pattern HOUR = Pattern.compile("(오전|오후|아침|낮|저녁|밤)?\\s*(\\d{1,2})\\s*시");
    private static final Pattern PEOPLE = Pattern.compile("(\\d{1,3})\\s*명");
    private static final Pattern CANDIDATE = Pattern.compile("^- id=(\\d+) \\| ([^|]*) \\| ([^|]*) \\|", Pattern.MULTILINE);
    private static final int RECOMMEND_SIZE = 3;

    private final boolean enabled;
    // 일시는 실제 도구 결과처럼 "2026-11-07T19:00:00" 문자열로 쓴다.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public MockLlm(@Value("${ai.mock.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.warn("AI 목업 모드: OpenAI를 호출하지 않고 문장 속 단어로 응답합니다.");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    // A23 초안: 종목·자치구·요일·시각·인원. 아무것도 못 찾으면 안내 문장(→ 400 AI_DRAFT_INSUFFICIENT)
    public String draft(String prompt) {
        AiSearchConditionDto keyword = KeywordConditionExtractor.extract(prompt);
        LocalDateTime startAt = startAt(prompt);
        Integer people = people(prompt);
        if (keyword.getSportType() == null && keyword.getRegion() == null && startAt == null && people == null) {
            return "어떤 매치를 만들고 싶으신가요?";
        }
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("sportType", keyword.getSportType());
        draft.put("region", keyword.getRegion());
        draft.put("startAt", startAt);
        draft.put("maxParticipant", people);
        draft.put("title", title(keyword));
        draft.put("description", "함께 운동할 분을 모집해요. 일정과 장소를 확인하고 편하게 참여해 주세요.");
        return toJson(draft);
    }

    // A22 검색: 종목·자치구 + 날짜 표현(오늘·내일·주말·요일). 아무것도 못 찾으면 안내 문장(→ 400 AI_CONDITION_NOT_FOUND)
    public String search(String prompt) {
        AiSearchConditionDto keyword = KeywordConditionExtractor.extract(prompt);
        LocalDate[] range = dateRange(prompt);
        if (keyword.isEmpty() && range == null) {
            return "어떤 매치를 찾으시나요?";
        }
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("sportType", keyword.getSportType());
        condition.put("region", keyword.getRegion());
        if (range != null) {
            condition.put("startAt", range[0].atStartOfDay());
            condition.put("endAt", range[1].atTime(23, 59));
        }
        return toJson(condition);
    }

    // A24 추천: 후보 목록에서 선호 종목 2점 + 같은 자치구 1점, 동점이면 목록 순서(시작이 가까운 순)
    public String recommend(String userPrompt) {
        String text = userPrompt == null ? "" : userPrompt;
        String district = lineValue(text, "활동 자치구");
        String sports = lineValue(text, "선호 종목");
        List<long[]> scored = new ArrayList<>();
        Matcher matcher = CANDIDATE.matcher(text);
        int order = 0;
        while (matcher.find()) {
            long id = Long.parseLong(matcher.group(1));
            String sport = matcher.group(2).trim();
            String region = matcher.group(3).trim();
            int score = (!sport.isEmpty() && sports.contains(sport) ? 2 : 0) + (!region.isEmpty() && region.equals(district) ? 1 : 0);
            scored.add(new long[] {id, score, order++});
        }
        List<Long> ids = scored.stream()
                .sorted(Comparator.comparingLong((long[] row) -> -row[1]).thenComparingLong(row -> row[2]))
                .limit(RECOMMEND_SIZE)
                .map(row -> row[0])
                .toList();
        return toJson(Map.of("matchIds", ids));
    }

    private static String title(AiSearchConditionDto keyword) {
        String place = keyword.getRegion() == null ? "" : District.fromName(keyword.getRegion()).shortName() + " ";
        String sport = keyword.getSportType() == null ? "운동" : keyword.getSportType().getDescription();
        return place + sport + " 함께해요";
    }

    private static LocalDateTime startAt(String prompt) {
        LocalDate date = day(prompt);
        LocalTime time = time(prompt);
        if (date == null && time == null) {
            return null;
        }
        LocalDate today = PromptDateTable.today();
        return (date == null ? today.plusDays(1) : date).atTime(time == null ? LocalTime.MIDNIGHT : time);
    }

    // 하루짜리 날짜: 오늘·내일·모레·요일·주말(토요일)
    private static LocalDate day(String prompt) {
        LocalDate[] range = dateRange(prompt);
        return range == null ? null : range[0];
    }

    private static LocalDate[] dateRange(String prompt) {
        String text = prompt == null ? "" : prompt;
        LocalDate today = PromptDateTable.today();
        if (text.contains("오늘")) {
            return new LocalDate[] {today, today};
        }
        if (text.contains("내일")) {
            return new LocalDate[] {today.plusDays(1), today.plusDays(1)};
        }
        if (text.contains("모레")) {
            return new LocalDate[] {today.plusDays(2), today.plusDays(2)};
        }
        String[] names = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
        for (int i = 0; i < names.length; i++) {
            if (text.contains(names[i])) {
                LocalDate date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(i + 1)));
                return new LocalDate[] {date, date};
            }
        }
        if (text.contains("주말")) {
            if (today.getDayOfWeek() == DayOfWeek.SUNDAY) {
                return new LocalDate[] {today, today};
            }
            LocalDate saturday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            return new LocalDate[] {saturday, saturday.plusDays(1)};
        }
        return null;
    }

    private static LocalTime time(String prompt) {
        Matcher matcher = HOUR.matcher(prompt == null ? "" : prompt);
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(2));
        String part = matcher.group(1);
        boolean afternoon = part != null && (part.equals("오후") || part.equals("저녁") || part.equals("밤") || part.equals("낮") && hour < 6);
        if (afternoon && hour < 12) {
            hour += 12;
        }
        return hour <= 23 ? LocalTime.of(hour, 0) : null;
    }

    private static Integer people(String prompt) {
        Matcher matcher = PEOPLE.matcher(prompt == null ? "" : prompt);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static String lineValue(String text, String label) {
        Matcher matcher = Pattern.compile("- " + label + ": (.*)").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
