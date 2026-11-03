package com.example.sporty.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.example.sporty.ai.tools.MatchDraftAiTool;
import com.example.sporty.ai.tools.MatchRecommendAiTool;
import com.example.sporty.ai.tools.MatchSearchAiTool;

/** 목업 모드가 켜지면 OpenAI(ChatClient)를 부르지 않고, 꺼지면 부르는지 세 Chat 모두 확인한다. */
class ChatMockSwitchTest {

    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

    ChatClient.Builder builder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(chatClient);
        return builder;
    }

    @Test
    void 켜지면_ChatClient를_부르지_않는다() {
        MockLlm on = new MockLlm(true);

        String draft = new DraftChat(builder(), mock(MatchDraftAiTool.class), on).call("system", "토요일 저녁 7시 송파에서 농구 10명");
        String search = new SearchChat(builder(), mock(MatchSearchAiTool.class), on).call("system", "이번 주말 송파에서 농구");
        String recommend = new RecommendChat(builder(), mock(MatchRecommendAiTool.class), on).call("system", "");

        assertThat(draft).contains("BASKETBALL");
        assertThat(search).contains("송파구");
        assertThat(recommend).contains("matchIds");
        verify(chatClient, never()).prompt();
    }

    @Test
    void 꺼지면_ChatClient를_부른다() {
        MockLlm off = new MockLlm(false);

        new DraftChat(builder(), mock(MatchDraftAiTool.class), off).call("system", "농구");
        new SearchChat(builder(), mock(MatchSearchAiTool.class), off).call("system", "농구");
        new RecommendChat(builder(), mock(MatchRecommendAiTool.class), off).call("system", "농구");

        verify(chatClient, org.mockito.Mockito.times(3)).prompt();
    }
}
