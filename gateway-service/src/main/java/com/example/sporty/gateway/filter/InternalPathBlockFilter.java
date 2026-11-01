package com.example.sporty.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * 서비스 간 호출용 /internal 경로를 외부에서 막는다.
 * 뒤 서비스가 경로를 정리하면(//, .., %69) 라우트 조건을 빠져나가므로
 * 디코딩한 경로의 세그먼트를 라우팅 전에 직접 검사한다.
 */
@Component
public class InternalPathBlockFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalPathBlockFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (isInternal(rawPath)) {
            log.warn("Blocked internal path: {}", rawPath);
            return AuthorizationHeaderFilter.reject(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다.");
        }
        return chain.filter(exchange);
    }

    static boolean isInternal(String rawPath) {
        return AuthorizationHeaderFilter.decodedPaths(rawPath).stream()
                .flatMap(AuthorizationHeaderFilter::segments)
                .anyMatch("internal"::equalsIgnoreCase);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
