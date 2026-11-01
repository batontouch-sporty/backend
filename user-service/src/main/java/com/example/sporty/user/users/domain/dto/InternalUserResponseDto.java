package com.example.sporty.user.users.domain.dto;

import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.District;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

// [I1] 내부 회원 단건 조회 응답. match는 참가 검사(성별·상태), ai는 추천(자치구·선호 종목)에 쓴다.
@Builder
@Getter
public class InternalUserResponseDto {

    private Long id;
    private Gender gender;
    private UserStatus status;
    private District district;
    private List<SportType> sportTypes;

    // 가입 때 프로필이 같이 만들어지지만, 없으면 자치구는 null·선호 종목은 빈 목록으로 준다.
    public static InternalUserResponseDto fromEntity(UserEntity user, ProfileEntity profile, List<SportType> sportTypes) {
        return InternalUserResponseDto.builder()
                .id(user.getId())
                .gender(user.getGender())
                .status(user.getStatus())
                .district(profile == null ? null : profile.getDistrict())
                .sportTypes(sportTypes)
                .build();
    }

}
