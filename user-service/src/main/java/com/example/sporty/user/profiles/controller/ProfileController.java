package com.example.sporty.user.profiles.controller;

import com.example.sporty.user.commons.config.AuthHeaders;
import com.example.sporty.user.profiles.domain.dto.ProfileDetailResponseDto;
import com.example.sporty.user.profiles.domain.dto.ProfileUpdateRequestDto;
import com.example.sporty.user.profiles.domain.dto.ProfileUpdateResponseDto;
import jakarta.validation.Valid;
import com.example.sporty.user.profiles.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService profileService;

    // [PR-01] 프로필 수정
    @PutMapping("/me")
    public ResponseEntity<ProfileUpdateResponseDto> updateProfile(
            @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @Valid @RequestBody ProfileUpdateRequestDto request) {
        return ResponseEntity.ok(profileService.updateProfile(userId, request));
    }

    // [PR-02] 프로필 조회
    // 비로그인 조회는 막는다(A8). 조회 대상은 회원 ID가 아니라 프로필 ID다.
    @GetMapping("/{profileId}")
    public ResponseEntity<ProfileDetailResponseDto> getProfile(
            @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("profileId") Long profileId) {

        ProfileDetailResponseDto response = profileService.getProfile(profileId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(response);
    }

}
