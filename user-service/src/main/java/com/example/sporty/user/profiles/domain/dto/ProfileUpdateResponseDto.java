package com.example.sporty.user.profiles.domain.dto;

import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.District;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class ProfileUpdateResponseDto {

    private String nickname;
    private District district;
    private List<SportType> preferenceSports;

    public static ProfileUpdateResponseDto fromEntity(ProfileEntity profile, List<SportType> sports) {
        return ProfileUpdateResponseDto.builder()
                .nickname(profile.getNickname())
                .district(profile.getDistrict())
                .preferenceSports(sports)
                .build();
    }
}
