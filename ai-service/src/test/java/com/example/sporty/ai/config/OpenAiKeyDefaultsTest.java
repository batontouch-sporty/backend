package com.example.sporty.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OpenAiKeyDefaultsTest {

    @Test
    void 키가_빈_값이면_더미_키를_넣는다() {
        MockEnvironment env = new MockEnvironment().withProperty(OpenAiKeyDefaults.KEY, "");

        new OpenAiKeyDefaults().postProcessEnvironment(env, null);

        assertThat(env.getProperty(OpenAiKeyDefaults.KEY)).isEqualTo(OpenAiKeyDefaults.NOT_CONFIGURED);
    }

    @Test
    void 키가_있으면_그대로_둔다() {
        MockEnvironment env = new MockEnvironment().withProperty(OpenAiKeyDefaults.KEY, "sk-real");

        new OpenAiKeyDefaults().postProcessEnvironment(env, null);

        assertThat(env.getProperty(OpenAiKeyDefaults.KEY)).isEqualTo("sk-real");
    }
}
