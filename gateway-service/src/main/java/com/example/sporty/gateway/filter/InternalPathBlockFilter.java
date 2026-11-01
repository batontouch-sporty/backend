package com.example.sporty.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * 서비스 간 호출용 /internal 경로와 공개 health를 뺀 /actuator 경로를 외부에서 막는다.
 * 뒤 서비스가 경로를 정리하면(//, .., %69) 라우트 조건을 빠져나가므로
 * 디코딩한 경로의 세그먼트를 라우팅 전에 직접 검사한다.
 */
@Component
public class InternalPathBlockFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalPathBlockFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // WebFilter는 관리 포트(외부 미게시)에도 걸린다. 여기서 막으면 Prometheus가 수집하지 못한다.
        if (WebServerApplicationContext.hasServerNamespace(exchange.getApplicationContext(), "management")) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        if (isInternal(rawPath) || isBlockedActuator(request.getMethod(), rawPath)) {
            log.warn("Blocked path: {}", rawPath);
            return AuthorizationHeaderFilter.reject(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다.");
        }
        return chain.filter(exchange);
    }

    static boolean isInternal(String rawPath) {
        return hasSegment(rawPath, "internal");
    }

    // health는 공개 경로와 정확히 같을 때만 지나간다. 세그먼트 검사는 health 아래 경로까지 함께 막는다.
    static boolean isBlockedActuator(HttpMethod method, String rawPath) {
        return hasSegment(rawPath, "actuator") && !AuthorizationHeaderFilter.isPublic(method, rawPath);
    }

    private static boolean hasSegment(String rawPath, String name) {
        return AuthorizationHeaderFilter.decodedPaths(rawPath).stream()
                .flatMap(AuthorizationHeaderFilter::segments)
                .anyMatch(name::equalsIgnoreCase);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
