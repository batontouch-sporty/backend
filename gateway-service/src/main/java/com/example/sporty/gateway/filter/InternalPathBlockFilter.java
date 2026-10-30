package com.example.sporty.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;

import reactor.core.publisher.Mono;

/**
 * 서비스 간 호출용 /internal 경로를 외부에서 막는다.
 * 뒤 서비스가 경로를 정리하면(//, .., %69) 라우트 조건을 빠져나가므로
 * 디코딩한 경로의 세그먼트를 라우팅 전에 직접 검사한다.
 */
@Component
public class InternalPathBlockFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalPathBlockFilter.class);

    private static final Pattern SEPARATOR = Pattern.compile("[/\\\\]");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (isInternal(rawPath)) {
            log.warn("Blocked internal path: {}", rawPath);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    static boolean isInternal(String rawPath) {
        String decoded = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        return SEPARATOR.splitAsStream(decoded)
                .map(segment -> segment.split(";", 2)[0])
                .anyMatch("internal"::equalsIgnoreCase);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
