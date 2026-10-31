package com.example.sporty.gateway.filter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 실제 라우트 설정으로 Gateway를 띄우고, 모의 서버가 받은 경로와 헤더를 응답 헤더로 돌려받아 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=" + AuthorizationHeaderIntegrationTest.SECRET,
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class AuthorizationHeaderIntegrationTest {

    static final String SECRET = "test-only-jwt-secret-key-at-least-32-bytes-long";
    private static final String OTHER_SECRET = "test-only-other-jwt-secret-key-at-least-32-bytes";
    private static final String USER_ID = "X-User-Id";
    private static final String USER_ROLE = "X-User-Role";

    private static HttpServer downstream;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void routeToDownstream(DynamicPropertyRegistry registry) throws IOException {
        downstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        downstream.createContext("/", exchange -> {
            var received = exchange.getRequestHeaders();
            copy(received.getFirst(USER_ID), "Seen-User-Id", exchange);
            copy(received.getFirst(USER_ROLE), "Seen-User-Role", exchange);
            exchange.getResponseHeaders().set("Seen-Path", exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        downstream.start();

        String uri = "http://localhost:" + downstream.getAddress().getPort();
        for (String service : List.of("USER-SERVICE", "MATCH-SERVICE", "FACILITY-SERVICE", "AI-SERVICE")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", () -> uri);
        }
    }

    private static void copy(String value, String header, HttpExchange exchange) {
        if (value != null) {
            exchange.getResponseHeaders().set(header, value);
        }
    }

    @AfterAll
    static void stopDownstream() {
        downstream.stop(0);
    }

    @Test
    void publicPathWithoutTokenDropsForgedHeaders() {
        request(HttpMethod.GET, "/match-service/api/matches", null)
                .header(USER_ID, "999")
                .header(USER_ROLE, "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Seen-Path", "/api/matches")
                .expectHeader().doesNotExist("Seen-User-Id")
                .expectHeader().doesNotExist("Seen-User-Role");
    }

    @Test
    void validTokenOverwritesForgedHeaders() {
        request(HttpMethod.POST, "/match-service/api/matches", accessToken("7", "USER"))
                .header(USER_ID, "999")
                .header("x-user-role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Seen-User-Id", "7")
                .expectHeader().valueEquals("Seen-User-Role", "USER");
    }

    @Test
    void publicPathWithValidTokenPassesUser() {
        request(HttpMethod.GET, "/match-service/api/matches/1", accessToken("7", "USER"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Seen-User-Id", "7");
    }

    @Test
    void tokenIgnoredPathsPassExpiredTokenWithoutUserHeaders() {
        String expired = token(SECRET, "7", "ACCESS", "USER", Instant.now().minus(Duration.ofMinutes(1)));
        for (String path : List.of(
                "/user-service/api/users",
                "/user-service/api/auth/login",
                "/user-service/api/auth/refresh")) {
            request(HttpMethod.POST, path, expired)
                    .header(USER_ID, "999")
                    .header(USER_ROLE, "ADMIN")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().doesNotExist("Seen-User-Id")
                    .expectHeader().doesNotExist("Seen-User-Role");
        }
    }

    @Test
    void tokenIgnoredPathDoesNotPassValidTokenAsUser() {
        request(HttpMethod.POST, "/user-service/api/auth/login", accessToken("7", "USER"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Seen-User-Id")
                .expectHeader().doesNotExist("Seen-User-Role");
    }

    @Test
    void logoutStillNeedsValidToken() {
        expectUnauthorized(HttpMethod.POST, "/user-service/api/auth/logout", null);
        expectUnauthorized(HttpMethod.POST, "/user-service/api/auth/logout",
                token(SECRET, "7", "ACCESS", "USER", Instant.now().minus(Duration.ofMinutes(1))));
    }

    @Test
    void privatePathWithoutTokenIsUnauthorized() {
        expectUnauthorized(HttpMethod.GET, "/match-service/api/matches/me", null);
        expectUnauthorized(HttpMethod.POST, "/match-service/api/matches", null);
        expectUnauthorized(HttpMethod.GET, "/match-service/api/matches/%6de", null);
        expectUnauthorized(HttpMethod.GET, "/match-service/api/matches//me", null);
        expectUnauthorized(HttpMethod.GET, "/match-service/API/MATCHES", null);
    }

    @Test
    void badTokensAreUnauthorizedEvenOnPublicPath() {
        String publicPath = "/match-service/api/matches";
        Instant now = Instant.now();
        Instant later = now.plus(Duration.ofMinutes(30));

        expectUnauthorized(HttpMethod.GET, publicPath, "not-a-token");
        expectUnauthorized(HttpMethod.GET, publicPath, token(OTHER_SECRET, "7", "ACCESS", "USER", later));
        expectUnauthorized(HttpMethod.GET, publicPath,
                token(SECRET, "7", "ACCESS", "USER", now.minus(Duration.ofMinutes(1))));
        expectUnauthorized(HttpMethod.GET, publicPath, token(SECRET, "7", "ACCESS", "USER", null));
        expectUnauthorized(HttpMethod.GET, publicPath, token(SECRET, "7", "REFRESH", null, later));
        expectUnauthorized(HttpMethod.GET, publicPath, token(SECRET, "7", "REFRESH", "ADMIN", later));
        expectUnauthorized(HttpMethod.GET, publicPath, token(SECRET, "0", "ACCESS", "USER", later));
        expectUnauthorized(HttpMethod.GET, publicPath, token(SECRET, "abc", "ACCESS", "USER", later));
        expectUnauthorized(HttpMethod.GET, publicPath, token(SECRET, "7", "ACCESS", null, later));
        expectUnauthorized(HttpMethod.GET, publicPath, token(SECRET, "7", "ACCESS", "OWNER", later));
        expectUnauthorized(HttpMethod.GET, publicPath, unsignedToken("7"));
    }

    @Test
    void malformedTokensAreUnauthorizedNotServerError() {
        String publicPath = "/match-service/api/matches";
        expectUnauthorized(HttpMethod.GET, publicPath, "W10.W10.");
        expectUnauthorized(HttpMethod.GET, publicPath, "ImEi.ImEi.AAAA");
        expectUnauthorized(HttpMethod.GET, publicPath, Jwts.builder()
                .setSubject("7")
                .claim("tokenType", "ACCESS")
                .claim("role", "USER")
                .claim("exp", List.of(1, 2))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact());
    }

    @Test
    void nonBearerAuthorizationIsUnauthorized() {
        client().get().uri(uri("/match-service/api/matches"))
                .header(HttpHeaders.AUTHORIZATION, "Basic abc")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void normalUserCannotReachAdminPaths() {
        String user = accessToken("7", "USER");
        for (String path : List.of(
                "/user-service/api/admin/users",
                "/user-service/api/ADMIN/users",
                "/user-service/api/%61dmin/users",
                "/user-service//api/admin/users")) {
            request(HttpMethod.GET, path, user).exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().doesNotExist("Seen-Path")
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("FORBIDDEN")
                    .jsonPath("$.message").isNotEmpty();
        }
    }

    @Test
    void adminReachesAdminPath() {
        request(HttpMethod.GET, "/user-service/api/admin/users", accessToken("1", "ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Seen-Path", "/api/admin/users")
                .expectHeader().valueEquals("Seen-User-Role", "ADMIN");
    }

    @Test
    void parentSegmentPathsAreRejectedBeforeAuthentication() {
        String admin = accessToken("1", "ADMIN");
        for (String path : List.of(
                "/match-service/api/matches/1/../me",
                "/match-service/api/matches/1/%2e%2e/me",
                "/match-service/api/matches/1/..;x=1/me",
                "/user-service/api/auth/login/../logout",
                "/user-service/api/x/../admin/users")) {
            for (String token : Arrays.asList(null, admin)) {
                request(HttpMethod.POST, path, token).exchange()
                        .expectStatus().isBadRequest()
                        .expectHeader().doesNotExist("Seen-Path")
                        .expectBody()
                        .jsonPath("$.code").isEqualTo("INVALID_PATH")
                        .jsonPath("$.message").isNotEmpty();
            }
        }
    }

    @Test
    void internalBlockRunsBeforeAuthentication() {
        request(HttpMethod.GET, "/user-service/internal/users", null).exchange()
                .expectStatus().isForbidden()
                .expectBody().isEmpty();
    }

    private void expectUnauthorized(HttpMethod method, String path, String token) {
        request(method, path, token).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Seen-Path")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isNotEmpty();
    }

    private WebTestClient.RequestBodySpec request(HttpMethod method, String path, String token) {
        WebTestClient.RequestBodySpec spec = client().method(method).uri(uri(path));
        return token == null ? spec : spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    // URI.create는 %, //, ..를 그대로 둔다.
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String accessToken(String subject, String role) {
        return token(SECRET, subject, "ACCESS", role, Instant.now().plus(Duration.ofMinutes(30)));
    }

    private String token(String secret, String subject, String tokenType, String role, Instant expiration) {
        Key key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder().setSubject(subject).claim("tokenType", tokenType);
        if (role != null) {
            builder.claim("role", role);
        }
        if (expiration != null) {
            builder.setExpiration(Date.from(expiration));
        }
        return builder.signWith(key).compact();
    }

    private String unsignedToken(String subject) {
        return Jwts.builder()
                .setSubject(subject)
                .claim("tokenType", "ACCESS")
                .claim("role", "ADMIN")
                .setExpiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .compact();
    }
}
