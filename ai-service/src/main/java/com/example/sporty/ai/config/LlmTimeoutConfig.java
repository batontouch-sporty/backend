package com.example.sporty.ai.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI(OpenAI)가 쓰는 RestClient에 타임아웃을 건다.
 * 기본값은 타임아웃이 길어 사용자가 오래 기다릴 수 있다. 응답을 약 15초 안에 끝내는 것이 목표다.
 * (프론트 draftAiMatch에는 아직 timeout이 없다)
 *
 * JDK HttpClient를 쓴다. Apache HttpClient는 연결 풀 대기 시간에 제한이 없고
 * 자체 재시도도 있어서, 재시도를 끈(max-attempts: 1) 의미와 8초 제한이 깨질 수 있다.
 */
@Configuration
public class LlmTimeoutConfig {

    @Bean
    public RestClientCustomizer llmTimeoutCustomizer(
            @Value("${ai.llm.connect-timeout:3s}") Duration connectTimeout,
            @Value("${ai.llm.read-timeout:8s}") Duration readTimeout
    ) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return builder -> builder.requestFactory(
                ClientHttpRequestFactoryBuilder.jdk().build(settings));
    }
}
