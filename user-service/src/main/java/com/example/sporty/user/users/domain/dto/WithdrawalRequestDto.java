package com.example.sporty.user.users.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 비밀번호 누락은 400 INVALID_INPUT이 아니라 PASSWORD_MISMATCH로 응답하므로 @Valid를 걸지 않는다.
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalRequestDto {
    private String password;
}
