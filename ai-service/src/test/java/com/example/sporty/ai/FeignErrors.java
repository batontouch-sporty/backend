package com.example.sporty.ai;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import feign.FeignException;
import feign.Request;
import feign.Response;

/** 테스트에서 match·user 장애를 흉내 내는 Feign 예외 */
final class FeignErrors {

    private FeignErrors() {
    }

    static FeignException status(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "/test", Map.of(), null,
                StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .reason("test")
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("test", response);
    }
}
