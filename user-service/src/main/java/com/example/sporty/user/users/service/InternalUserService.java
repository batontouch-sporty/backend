package com.example.sporty.user.users.service;

import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.profiles.repository.ProfileRepository;
import com.example.sporty.user.sportpreference.domain.entity.SportPreferenceEntity;
import com.example.sporty.user.sportpreference.repository.SportPreferenceRepository;
import com.example.sporty.user.users.domain.dto.InternalProfileResponseDto;
import com.example.sporty.user.users.domain.dto.InternalUserResponseDto;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// 다른 서비스가 Feign으로 부르는 내부 조회. Gateway가 /internal/**를 외부에서 막는다.
@Service
@RequiredArgsConstructor
public class InternalUserService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final SportPreferenceRepository sportPreferenceRepository;

    // [I1] 회원 단건 조회
    // 탈퇴 중·탈퇴한 회원도 404가 아니라 status를 그대로 준다. 참가 가능 여부는 호출하는 쪽이 판단한다.
    @Transactional(readOnly = true)
    public InternalUserResponseDto getUser(Long userId) {

        // 1. 회원 엔티티 얻기
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        // 2. 프로필과 선호 종목 얻기
        ProfileEntity profile = profileRepository.findByUserId(userId).orElse(null);
        List<SportType> sportTypes = profile == null
                ? List.of()
                : sportPreferenceRepository.findByProfileId(profile.getId())
                        .stream()
                        .map(SportPreferenceEntity::getSportType)
                        .sorted()
                        .toList();

        return InternalUserResponseDto.fromEntity(user, profile, sportTypes);
    }

    // [I2] 회원 닉네임 일괄 조회
    // 없는 ID는 빼고 준다. 결과는 회원 ID 순서.
    @Transactional(readOnly = true)
    public List<InternalProfileResponseDto> getProfiles(List<Long> userIds) {

        // 1. 빈 값·중복 ID 정리
        List<Long> ids = userIds == null
                ? List.of()
                : userIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        // 2. 프로필을 한 번에 조회
        return profileRepository.findByUserIdIn(ids)
                .stream()
                .map(profile -> InternalProfileResponseDto.fromEntity(profile.getUser().getId(), profile))
                .sorted(Comparator.comparing(InternalProfileResponseDto::getId))
                .toList();
    }

}
