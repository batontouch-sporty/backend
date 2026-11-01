package com.example.sporty.user.users.domain.dto;

import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import lombok.Builder;
import lombok.Getter;

// [I2] 내부 회원 닉네임 일괄 조회 응답 한 건. match 상세의 참가자 목록(profileId·nickname·imageUrl)을 채운다.
@Builder
@Getter
public class InternalProfileResponseDto {

    private Long id;
    private Long profileId;
    private String nickname;
    private String imageUrl;

    public static InternalProfileResponseDto fromEntity(Long userId, ProfileEntity profile) {
        return InternalProfileResponseDto.builder()
                .id(userId)
                .profileId(profile.getId())
                .nickname(profile.getNickname())
                .imageUrl(profile.getImageUrl())
                .build();
    }

}
