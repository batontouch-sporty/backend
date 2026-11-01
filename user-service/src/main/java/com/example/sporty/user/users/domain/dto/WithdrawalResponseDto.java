package com.example.sporty.user.users.domain.dto;

import com.example.sporty.user.users.domain.entity.UserStatus;
import lombok.Builder;
import lombok.Getter;

// [USR-04] 탈퇴 접수 응답(A3 202). 최종 결과는 알림으로 받는다.
@Builder
@Getter
public class WithdrawalResponseDto {

    private UserStatus status;
    private String sagaId;

    public static WithdrawalResponseDto withdrawing(String sagaId) {
        return WithdrawalResponseDto.builder()
                .status(UserStatus.WITHDRAWING)
                .sagaId(sagaId)
                .build();
    }

}
