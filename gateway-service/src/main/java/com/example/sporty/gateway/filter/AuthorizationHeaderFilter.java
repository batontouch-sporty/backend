package com.example.sporty.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

/**
 * 액세스 토큰을 검증해 회원 정보를 X-User-Id, X-User-Role 헤더로 서비스에 넘긴다.
 * 클라이언트가 보낸 같은 이름의 헤더는 토큰 유무와 상관없이 지운다.
 * 라우트마다 거는 필터는 새 라우트에서 빠뜨릴 수 있어 GlobalFilter로 둔다.
 */
@Component
public class AuthorizationHeaderFilter implements GlobalFilter, Ordered {

    private static final String USER_ID = "X-User-Id";
    private static final String USER_ROLE = "X-User-Role";
    private static final String BEARER = "Bearer ";

    // 만료된 토큰이 남아 있어도 가입·로그인·재발급은 되어야 하므로 토큰을 보지 않는다.
    private static final Pattern TOKEN_IGNORED_REQUEST =
            Pattern.compile("POST /user-service/api/(users|auth/(login|refresh))");

    private static final Pattern PUBLIC_REQUEST = Pattern.compile(String.join("|",
            "GET /match-service/api/matches(/\\d+)?",
            "GET /facility-service/api/services(/\\d+)?",
            "POST /ai-service/api/ai/matches/search",
            "GET /[^/]+/(health-check|actuator/health)"));

    private static final Pattern ADMIN_PATH =
            Pattern.compile("(/prometheus|/[^/]+/api/admin)(/.*)?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern SEPARATOR = Pattern.compile("[/\\\\]");

    private final JwtParser jwtParser;

    public AuthorizationHeaderFilter(@Value("${jwt.secret}") String secret) {
        this.jwtParser = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (hasParentSegment(rawPath)) {
            return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_PATH", "잘못된 경로입니다.");
        }
        if (isTokenIgnored(request.getMethod(), rawPath)) {
            return forward(exchange, chain, null);
        }
        if (authorization == null) {
            return isPublic(request.getMethod(), rawPath)
                    ? forward(exchange, chain, null)
                    : reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
        }

        LoginUser user = authenticate(authorization);
        if (user == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
        }
        if (isAdmin(rawPath) && !"ADMIN".equals(user.role())) {
            return reject(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다.");
        }
        return forward(exchange, chain, user);
    }

    private LoginUser authenticate(String authorization) {
        if (!authorization.startsWith(BEARER)) {
            return null;
        }
        try {
            Claims claims = jwtParser.parseClaimsJws(authorization.substring(BEARER.length())).getBody();
            String role = claims.get("role", String.class);
            long userId = Long.parseLong(claims.getSubject());
            boolean valid = "ACCESS".equals(claims.get("tokenType", String.class))
                    && claims.getExpiration() != null
                    && userId > 0
                    && ("USER".equals(role) || "ADMIN".equals(role));
            return valid ? new LoginUser(Long.toString(userId), role) : null;
        } catch (RuntimeException e) {
            // jjwt는 JwtException 말고도 망가진 토큰에 ClassCastException, IllegalStateException을 던진다.
            return null;
        }
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, LoginUser user) {
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(USER_ROLE);
            if (user != null) {
                headers.set(USER_ID, user.id());
                headers.set(USER_ROLE, user.role());
            }
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, message)
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    static boolean isTokenIgnored(HttpMethod method, String rawPath) {
        return matchesEveryDecoding(TOKEN_IGNORED_REQUEST, method, rawPath);
    }

    static boolean isPublic(HttpMethod method, String rawPath) {
        return matchesEveryDecoding(PUBLIC_REQUEST, method, rawPath);
    }

    // 몇 번 디코딩할지에 따라 뜻이 달라지는 경로는 공개로 보지 않는다.
    private static boolean matchesEveryDecoding(Pattern request, HttpMethod method, String rawPath) {
        List<String> paths = decodedPaths(rawPath);
        return !paths.isEmpty() && paths.stream()
                .allMatch(path -> request.matcher(method.name() + " " + path).matches());
    }

    static boolean isAdmin(String rawPath) {
        return decodedPaths(rawPath).stream()
                .map(path -> segments(path)
                        .filter(segment -> !segment.isEmpty() && !segment.equals("."))
                        .collect(Collectors.joining("/", "/", "")))
                .anyMatch(path -> ADMIN_PATH.matcher(path).matches());
    }

    // 뒤 서비스마다 ..를 푸는 방식이 달라 경로를 풀어서 판정하지 않고 거부한다.
    static boolean hasParentSegment(String rawPath) {
        return decodedPaths(rawPath).stream()
                .flatMap(AuthorizationHeaderFilter::segments)
                .anyMatch(".."::equals);
    }

    private static Stream<String> segments(String path) {
        return SEPARATOR.splitAsStream(path).map(segment -> segment.split(";", 2)[0]);
    }

    // InternalPathBlockFilter와 같은 이유로 최대 3번까지 디코딩하고, 단계별 경로를 모두 돌려준다.
    private static List<String> decodedPaths(String rawPath) {
        List<String> paths = new ArrayList<>();
        String path = rawPath;
        for (int i = 0; i < 3; i++) {
            try {
                path = UriUtils.decode(path, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                break;
            }
            paths.add(path);
        }
        return paths;
    }

    // RewritePath(순서 1)가 경로를 바꾸기 전에 서비스 경로로 판정해야 한다.
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private record LoginUser(String id, String role) {
    }
}
