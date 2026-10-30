package com.example.sporty.user.profiles.domain.dto;

import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.District;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
public class ProfileDetailResponseDto {

        private Long id;
        private String nickname;
        private District district;
        private String imageUrl;
        private List<SportType> preferenceSports;

    public static ProfileDetailResponseDto fromEntity(ProfileEntity entity, List<SportType> preferenceSports) {
        return ProfileDetailResponseDto.builder()
                .id(entity.getId())
                .nickname(entity.getNickname())
                .district(entity.getDistrict())
                .imageUrl(entity.getImageUrl())
                .preferenceSports(preferenceSports)
                .build();

    }
}
