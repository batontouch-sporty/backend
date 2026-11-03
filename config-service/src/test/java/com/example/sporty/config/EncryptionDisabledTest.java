package com.example.sporty.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 키스토어 환경변수가 없을 때(팀원 로컬·CI Compose). 암호화 없이 기동하고 /encrypt는 암호문을 만들지 않는지 본다.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class EncryptionDisabledTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 키가_없어도_기동하고_encrypt는_실패한다() {
        assertThatThrownBy(() -> mockMvc.perform(post("/encrypt").contentType(MediaType.TEXT_PLAIN).content("plain")))
                .hasRootCauseInstanceOf(UnsupportedOperationException.class);
    }
}
