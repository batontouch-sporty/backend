package com.example.sporty.ai.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.sporty.ai.client.dto.InternalUserDto;

@FeignClient(name = "user-service")
public interface UserClient {

    // I1 회원 단건 조회: 성별·상태·활동 자치구(코드)·선호 종목
    @GetMapping("/internal/users/{id}")
    InternalUserDto getUser(@PathVariable("id") Long id);
}
