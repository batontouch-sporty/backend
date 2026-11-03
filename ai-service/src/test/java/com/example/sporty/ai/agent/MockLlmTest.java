package com.example.sporty.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

import org.junit.jupiter.api.Test;

import com.example.sporty.ai.util.PromptDateTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class MockLlmTest {

    final MockLlm mock = new MockLlm(true);
    final ObjectMapper json = new ObjectMapper();

    JsonNode read(String text) throws Exception {
        return json.readTree(text);
    }

    @Test
    void 목업_모드는_설정값을_따른다() {
        assertThat(new MockLlm(false).enabled()).isFalse();
        assertThat(mock.enabled()).isTrue();
    }

    @Test
    void 초안은_종목_자치구_요일_시각_인원을_채운다() throws Exception {
        LocalDate saturday = PromptDateTable.today().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));

        JsonNode draft = read(mock.draft("토요일 저녁 7시 송파에서 농구 10명 모집"));

        assertThat(draft.get("sportType").asText()).isEqualTo("BASKETBALL");
        assertThat(draft.get("region").asText()).isEqualTo("송파구");
        assertThat(draft.get("startAt").asText()).isEqualTo(saturday + "T19:00:00");
        assertThat(draft.get("maxParticipant").asInt()).isEqualTo(10);
        assertThat(draft.get("title").asText()).isEqualTo("송파 농구 함께해요");
    }

    @Test
    void 초안_정보가_하나도_없으면_안내_문장을_준다() {
        assertThat(mock.draft("안녕하세요")).doesNotStartWith("{");
    }

    @Test
    void 검색은_이번_주말을_토요일부터_일요일까지로_잡는다() throws Exception {
        JsonNode condition = read(mock.search("이번 주말 송파에서 농구"));

        assertThat(condition.get("sportType").asText()).isEqualTo("BASKETBALL");
        assertThat(condition.get("region").asText()).isEqualTo("송파구");
        assertThat(condition.get("startAt").asText()).endsWith("T00:00:00");
        assertThat(condition.get("endAt").asText()).endsWith("T23:59:00");
    }

    @Test
    void 검색_조건이_하나도_없으면_안내_문장을_준다() {
        assertThat(mock.search("안녕")).doesNotStartWith("{");
    }

    @Test
    void 추천은_선호_종목과_같은_자치구를_먼저_고른다() throws Exception {
        String prompt = """
                ## 회원 정보
                - 활동 자치구: 송파구
                - 선호 종목: 농구

                ## 후보 매치 (id | 종목 | 자치구 | 시작 일시 | 성별 구성 | 인원 | 제목)
                - id=1 | 풋살 | 강남구 | 2026-11-07T10:00 | 혼성 | 1/10명 | 강남 풋살
                - id=2 | 농구 | 강남구 | 2026-11-07T11:00 | 혼성 | 1/10명 | 강남 농구
                - id=3 | 풋살 | 송파구 | 2026-11-07T12:00 | 혼성 | 1/10명 | 송파 풋살
                - id=4 | 농구 | 송파구 | 2026-11-07T13:00 | 혼성 | 1/10명 | 송파 농구
                """;

        JsonNode result = read(mock.recommend(prompt));

        assertThat(result.get("matchIds").toString()).isEqualTo("[4,2,3]");
    }

    @Test
    void 날짜_없이_시각만_있으면_시작_일시를_추측하지_않는다() throws Exception {
        JsonNode draft = read(mock.draft("저녁 7시 송파에서 농구 10명"));

        assertThat(draft.get("startAt").isNull()).isTrue();
        assertThat(draft.get("sportType").asText()).isEqualTo("BASKETBALL");
    }

    @Test
    void 다음_주_요일과_다음_주말은_다음_주_날짜로_잡는다() {
        LocalDate tuesday = LocalDate.of(2026, 11, 3);

        assertThat(MockLlm.dateRange("다음 주 토요일 농구", tuesday))
                .containsExactly(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 14));
        assertThat(MockLlm.dateRange("다음 주말 풋살", tuesday))
                .containsExactly(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15));
        assertThat(MockLlm.dateRange("토요일 농구", tuesday))
                .containsExactly(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7));
        assertThat(MockLlm.dateRange("이번 주말 풋살", tuesday))
                .containsExactly(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8));
    }

    @Test
    void 성별만_말해도_검색_조건으로_찾는다() throws Exception {
        JsonNode condition = read(mock.search("여자만 하는 매치"));

        assertThat(condition.get("genderGroup").asText()).isEqualTo("FEMALE");
    }

    @Test
    void 이번_주와_다음_주_토요일을_구분한다() {
        LocalDate tuesday = LocalDate.of(2026, 11, 3);

        assertThat(MockLlm.dateRange("이번 주 토요일 저녁 7시 농구", tuesday))
                .containsExactly(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7));
        assertThat(MockLlm.dateRange("다음 주 토요일 저녁 7시 농구", tuesday))
                .containsExactly(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 14));
        assertThat(MockLlm.dateRange("다음주 토요일 농구", tuesday))
                .containsExactly(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 14));
    }

    @Test
    void 분과_반을_시각에_반영한다() {
        assertThat(MockLlm.time("내일 저녁 7시 30분 농구")).isEqualTo(LocalTime.of(19, 30));
        assertThat(MockLlm.time("내일 저녁 7시 반 농구")).isEqualTo(LocalTime.of(19, 30));
        assertThat(MockLlm.time("오전 10시 풋살")).isEqualTo(LocalTime.of(10, 0));
        assertThat(MockLlm.time("토요일 저녁 7시 농구 10명")).isEqualTo(LocalTime.of(19, 0));
    }
}
