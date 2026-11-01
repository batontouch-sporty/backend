package com.example.sporty.user.users.service;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.exception.profiles.ProfileNotFoundException;
import com.example.sporty.user.commons.exception.users.DuplicateEmailException;
import com.example.sporty.user.commons.exception.users.DuplicateNicknameException;
import com.example.sporty.user.commons.exception.users.PasswordMismatchException;
import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.profiles.repository.ProfileRepository;
import com.example.sporty.user.sportpreference.domain.entity.SportPreferenceEntity;
import com.example.sporty.user.sportpreference.repository.SportPreferenceRepository;
import com.example.sporty.user.users.domain.dto.UserInfoResponseDto;
import com.example.sporty.user.users.domain.dto.UserSignUpRequestDto;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final SportPreferenceRepository sportPreferenceRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    // [USR-01] 회원가입
    public void signUp(UserSignUpRequestDto request) {


        // 1. 이메일, 닉네임 중복 여부 확인
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException();
        }

        // 프로필 수정과 같이 앞뒤 공백을 지운 닉네임으로 검사·저장한다.
        String nickname = request.getNickname().trim();

        if (profileRepository.existsByNickname(nickname)) {
            throw new DuplicateNicknameException();
        }

        // 2. 회원 생성
        String encodedPassword = passwordEncoder.encode(request.getPassword());
        // 비밀번호 해싱
        UserEntity userEntity =
                UserSignUpRequestDto.toUserEntity(request, encodedPassword);

        UserEntity savedUser = userRepository.save(userEntity);

        // 3. 프로필 생성
        ProfileEntity profileEntity = ProfileEntity.builder()
                .nickname(nickname)
                .district(request.getDistrict())
                .user(savedUser)
                .build();
        ProfileEntity savedProfile = profileRepository.save(profileEntity);

        // 4. 선호 종목 생성
        for (SportType sportType : request.getSportTypes().stream().distinct().toList()) {
            SportPreferenceEntity preference =
                    SportPreferenceEntity.builder()
                            .sportType(sportType)
                            .profile(savedProfile)
                            .build();

            sportPreferenceRepository.save(preference);
        }
    }

    // [USR-06] 회원 정보 조회
    @Transactional(readOnly = true)
    public UserInfoResponseDto getMyInfo(Long userId) {

            // 1. users 테이블에서 회원 정보 조회
            UserEntity userEntity
                    = userRepository.findById(userId)
                    .orElseThrow(UserNotFoundException::new);

            // 2. profile 테이블에서 프로필 정보 조회
            ProfileEntity profileEntity
                    = profileRepository.findByUserId(userId)
                    .orElseThrow(ProfileNotFoundException::new);

            // 3. 프로필 ID로 회원 선호 종목들 얻기
            List<SportType> sportTypes =
                    sportPreferenceRepository.findByProfileId(profileEntity.getId())
                    .stream()
                    .map(SportPreferenceEntity::getSportType)
                    .toList();

            // 4. dto로 변환
            return UserInfoResponseDto.fromEntity(userEntity, profileEntity, sportTypes);
    }

    // [USR-04] 회원탈퇴
    // 매치 정리는 match-service 소관이라 여기서 부르지 않는다. 탈퇴 Saga(202 WITHDRAWING)는 별도 이슈에서 붙인다.
    public void withdrawal(Long userId, String password) {

        // 1. 유저 ID로 회원 조회
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        // 2. 비밀번호 확인
        // 비밀번호 누락 검사
        if (password == null || password.isBlank()) {
            throw new PasswordMismatchException();
        }
        // 비밀번호 일치 여부 검사
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new PasswordMismatchException();
        }

        // 3. 탈퇴 처리(변경 감지) || 회원 상태를 탈퇴 상태로 변경, Refresh Token 삭제
        user.withdraw();
        refreshTokenService.delete(userId);


    }


}
