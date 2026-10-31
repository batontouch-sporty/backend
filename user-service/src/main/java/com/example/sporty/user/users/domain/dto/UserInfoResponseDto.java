package com.example.sporty.user.users.domain.dto;

import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.District;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.Role;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
public class UserInfoResponseDto {

    private String email;
    private Gender gender;
    private UserStatus status;
    private Role role;
    private String nickname;
    private District district;
    private String imageUrl;
    private List<SportType> preferenceSports;


    public static UserInfoResponseDto fromEntity(UserEntity userEntity, ProfileEntity profileEntity, List<SportType> preferenceSports) {
        return UserInfoResponseDto.builder()
                .email(userEntity.getEmail())
                .gender(userEntity.getGender())
                .status(userEntity.getStatus())
                .role(userEntity.getRole())
                .nickname(profileEntity.getNickname())
                .district(profileEntity.getDistrict())
                .imageUrl(profileEntity.getImageUrl())
                .preferenceSports(preferenceSports)
                .build();

    }

}
