package com.example.sporty.user.users.controller;

import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.users.domain.dto.UserInfoResponseDto;
import com.example.sporty.user.users.domain.dto.UserSignUpRequestDto;
import com.example.sporty.user.users.domain.dto.WithdrawalRequestDto;
import com.example.sporty.user.users.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // [USR-01] 회원가입
    @PostMapping
    public ResponseEntity<Void> signUp(@RequestBody @Valid UserSignUpRequestDto request) {
        userService.signUp(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .build();
    }

    // [USR-06] 회원 정보 조회
    @GetMapping("/me")
    public ResponseEntity<UserInfoResponseDto> getMyInfo(@RequestHeader(AuthHeaders.USER_ID) Long userId) {

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(userService.getMyInfo(userId));
    }

    // [USR-04] 회원탈퇴
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdrawal(
            @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @RequestBody(required = false) WithdrawalRequestDto request) {

        userService.withdrawal(userId, request == null ? null : request.getPassword());

        return ResponseEntity.noContent().build();
    }

}
