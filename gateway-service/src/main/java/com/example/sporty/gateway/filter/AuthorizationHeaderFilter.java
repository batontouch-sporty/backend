package com.example.sporty.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

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
import io.jsonwebtoken.JwtException;
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

    private static final Pattern PUBLIC_REQUEST = Pattern.compile(String.join("|",
            "POST /user-service/api/users",
            "POST /user-service/api/auth/(login|refresh)",
            "GET /match-service/api/matches(/\\d+)?",
            "GET /facility-service/api/services(/\\d+)?",
            "POST /ai-service/api/ai/matches/search",
            "GET /[^/]+/(health-check|actuator/health)"));

    private static final Pattern ADMIN_PATH =
            Pattern.compile("/[^/]+/api/admin(/.*)?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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

        if (authorization == null) {
            return isPublic(request.getMethod(), rawPath)
                    ? forward(exchange, chain, null)
                    : reject(exchange, HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }

        LoginUser user = authenticate(authorization);
        if (user == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        if (isAdmin(rawPath) && !"ADMIN".equals(user.role())) {
            return reject(exchange, HttpStatus.FORBIDDEN, "권한이 없습니다.");
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
        } catch (JwtException | IllegalArgumentException e) {
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

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = String.format("{\"code\":\"%s\",\"message\":\"%s\"}", status.name(), message)
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    // 몇 번 디코딩할지에 따라 뜻이 달라지는 경로는 공개로 보지 않는다.
    static boolean isPublic(HttpMethod method, String rawPath) {
        List<String> paths = decodedPaths(rawPath);
        return !paths.isEmpty() && paths.stream()
                .allMatch(path -> PUBLIC_REQUEST.matcher(method.name() + " " + path).matches());
    }

    static boolean isAdmin(String rawPath) {
        return decodedPaths(rawPath).stream().anyMatch(path -> ADMIN_PATH.matcher(path).matches());
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
            paths.add(normalize(path));
        }
        return paths;
    }

    // 뒤 서비스가 경로를 정리하는 방식에 맞춘다. 첫 세그먼트는 라우트를 고르므로 ..로 지우지 않는다.
    private static String normalize(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String part : SEPARATOR.split(path)) {
            String segment = part.split(";", 2)[0];
            if (segment.equals("..")) {
                if (segments.size() > 1) {
                    segments.removeLast();
                }
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    // RewritePath(순서 1)가 경로를 바꾸기 전에 서비스 경로로 판정해야 한다.
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private record LoginUser(String id, String role) {
    }
}
