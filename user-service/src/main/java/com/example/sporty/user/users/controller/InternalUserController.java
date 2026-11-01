package com.example.sporty.user.users.controller;

import com.example.sporty.user.users.domain.dto.InternalProfileResponseDto;
import com.example.sporty.user.users.domain.dto.InternalUserResponseDto;
import com.example.sporty.user.users.service.InternalUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 서비스 간 Feign 전용. 로그인 헤더(X-User-Id) 없이 불린다. 외부 요청은 Gateway가 /internal/** 로 막는다.
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final InternalUserService internalUserService;

    // [I1] 회원 단건 조회
    @GetMapping("/{id}")
    public ResponseEntity<InternalUserResponseDto> getUser(@PathVariable("id") Long id) {
        return ResponseEntity.ok(internalUserService.getUser(id));
    }

    // [I2] 회원 닉네임 일괄 조회 (ids=1,2,3). ids가 없거나 비어 있으면 []
    @GetMapping
    public ResponseEntity<List<InternalProfileResponseDto>> getProfiles(
            @RequestParam(name = "ids", required = false) List<Long> ids) {
        return ResponseEntity.ok(internalUserService.getProfiles(ids));
    }

}
