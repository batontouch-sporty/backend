package com.example.sporty.ai.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.example.sporty.ai.tools.MatchDraftAiTool;

/**
 * 초안용 LLM 호출만 담당한다. 테스트에서는 이 빈을 목으로 바꿔 OpenAI를 실제로 부르지 않는다.
 * 도구(draftMatch)가 호출되면 초안 JSON, 호출되지 않으면 AI의 안내 문장이 돌아온다.
 */
@Component
public class DraftChat {

    private final ChatClient chatClient;
    private final MockLlm mockLlm;

    public DraftChat(ChatClient.Builder builder, MatchDraftAiTool matchDraftAiTool, MockLlm mockLlm) {
        this.chatClient = builder.defaultTools(matchDraftAiTool).build();
        this.mockLlm = mockLlm;
    }

    public String call(String systemPrompt, String userPrompt) {
        // 시연 대비 목업 모드면 OpenAI를 부르지 않는다(AI_MOCK_ENABLED=true).
        if (mockLlm.enabled()) {
            return mockLlm.draft(userPrompt);
        }
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}
