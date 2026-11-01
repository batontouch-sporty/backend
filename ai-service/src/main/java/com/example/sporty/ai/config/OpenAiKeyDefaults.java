package com.example.sporty.ai.config;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * OPENAI_API_KEY가 없거나 빈 값이어도 ai-service가 기동하도록 더미 키를 넣는다.
 * Spring AI는 빈 키면 기동 단계에서 실패한다. 더미 키면 호출만 실패하고 초안은 503, 검색·추천은 Fallback으로 응답한다.
 */
public class OpenAiKeyDefaults implements EnvironmentPostProcessor {

    static final String KEY = "spring.ai.openai.api-key";
    static final String NOT_CONFIGURED = "not-configured";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!StringUtils.hasText(environment.getProperty(KEY))) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("openAiKeyDefaults", Map.of(KEY, NOT_CONFIGURED)));
        }
    }
}
