package com.example.sporty.user.users.domain.dto;

import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.District;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

// [I1] 내부 회원 단건 조회 응답. match는 참가 검사(성별·상태·참가 정지), ai는 추천(자치구·선호 종목)에 쓴다.
@Builder
@Getter
public class InternalUserResponseDto {

    private Long id;
    private Gender gender;
    private UserStatus status;
    private District district;
    private List<SportType> sportTypes;

    // 조회 시각 기준 참가 정지 여부. 정지 해제 스케줄러가 돌기 전이라도 종료 시각이 지났으면 false.
    private boolean participationSuspended;

    // 정지 종료 시각(KST, 시간대 없는 문자열 예: 2026-11-09T00:00:00). 정지 중이 아니면 null.
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime suspendedUntil;

    // 가입 때 프로필이 같이 만들어지지만, 없으면 자치구는 null·선호 종목은 빈 목록으로 준다.
    // now는 KST 현재 시각이다(suspended_until도 KST로 저장된다).
    public static InternalUserResponseDto fromEntity(UserEntity user, ProfileEntity profile, List<SportType> sportTypes,
                                                     LocalDateTime now) {
        LocalDateTime until = user.getSuspendedUntil();
        boolean suspended = until != null && until.isAfter(now);

        return InternalUserResponseDto.builder()
                .id(user.getId())
                .gender(user.getGender())
                .status(user.getStatus())
                .district(profile == null ? null : profile.getDistrict())
                .sportTypes(sportTypes)
                .participationSuspended(suspended)
                .suspendedUntil(suspended ? until : null)
                .build();
    }

}
