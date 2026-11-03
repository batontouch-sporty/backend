package com.example.sporty.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 테스트 안에서 keytool로 만든 일회용 JKS로 /encrypt·/decrypt 왕복과 서버 쪽 {cipher} 복호화를 본다.
 * 키스토어와 설정 파일은 임시 폴더에만 만들고 끝나면 지운다.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.profiles.active=native"})
@AutoConfigureMockMvc
class KeyStoreEncryptionTest {

    static final String STORE_PASSWORD = UUID.randomUUID().toString();
    static final String KEY_SECRET = UUID.randomUUID().toString();
    static final String[] PROPERTY_KEYS = {"encrypt.key-store.location", "encrypt.key-store.password",
            "encrypt.key-store.alias", "encrypt.key-store.secret", "spring.cloud.config.server.native.search-locations"};
    static Path dir;

    @Autowired
    MockMvc mockMvc;

    // 키 설정은 Spring Cloud가 컨텍스트보다 먼저 읽으므로 @DynamicPropertySource가 아닌 시스템 속성으로 넣는다.
    // 운영에서는 ENCRYPT_KEYSTORE_LOCATION·_PASSWORD·_ALIAS·_SECRET 환경변수로 같은 값을 넣는다.
    @BeforeAll
    static void createKeyStore() throws Exception {
        dir = Files.createTempDirectory("config-cipher-test");
        Path keyStore = dir.resolve("test.jks");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test-key", "-keyalg", "RSA",
                "-dname", "CN=test", "-storetype", "JKS", "-keystore", keyStore.toString(),
                "-storepass", STORE_PASSWORD, "-keypass", KEY_SECRET)
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();

        System.setProperty("encrypt.key-store.location", keyStore.toUri().toString());
        System.setProperty("encrypt.key-store.password", STORE_PASSWORD);
        System.setProperty("encrypt.key-store.alias", "test-key");
        System.setProperty("encrypt.key-store.secret", KEY_SECRET);
        System.setProperty("spring.cloud.config.server.native.search-locations", dir.toUri().toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        for (String key : PROPERTY_KEYS) {
            System.clearProperty(key);
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void encrypt_decrypt_왕복() throws Exception {
        String cipher = encrypt("db-password-1234");

        assertThat(cipher).isNotBlank().doesNotContain("db-password-1234");
        mockMvc.perform(post("/decrypt").contentType(MediaType.TEXT_PLAIN).content(cipher))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo("db-password-1234"));
    }

    @Test
    void cipher_값은_복호화해서_내려준다() throws Exception {
        String cipher = encrypt("jwt-secret-5678");
        Files.writeString(dir.resolve("demo-service-prod.yml"), "demo:\n  secret: '{cipher}" + cipher + "'\n");

        mockMvc.perform(get("/demo-service/prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources[0].source['demo.secret']").value("jwt-secret-5678"));
    }

    private String encrypt(String plain) throws Exception {
        return mockMvc.perform(post("/encrypt").contentType(MediaType.TEXT_PLAIN).content(plain))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
