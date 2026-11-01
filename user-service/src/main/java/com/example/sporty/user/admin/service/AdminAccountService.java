package com.example.sporty.user.admin.service;

import com.example.sporty.user.profiles.domain.entity.District;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.profiles.repository.ProfileRepository;
import com.example.sporty.user.users.domain.dto.UserSignUpRequestDto;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.Role;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * 개발·시연용 관리자 초기 계정을 만든다(명세 A1 비고 "관리자 계정은 초기 데이터로 만든다").
 * 비밀번호는 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    // 프로필 기본값. 관리자 화면은 진입할 때 A2(/users/me)를 부르는데 A2는 프로필이 없으면 404라 프로필도 만든다.
    static final String DEFAULT_NICKNAME = "관리자";
    static final District DEFAULT_DISTRICT = District.JUNG;
    static final Gender DEFAULT_GENDER = Gender.MALE;

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    public enum Result { CREATED, SKIPPED_NOT_CONFIGURED, SKIPPED_INVALID, SKIPPED_EXISTS, SKIPPED_NICKNAME_TAKEN }

    @Transactional
    public Result ensureAdmin(String email, String password, String nickname) {

        // 1. 이메일·비밀번호가 둘 다 있어야 만든다(운영 환경에서 실수로 만들어지지 않게)
        if (isBlank(email) || isBlank(password)) {
            log.info("ADMIN_EMAIL·ADMIN_PASSWORD가 없어 관리자 초기 계정을 만들지 않음");
            return Result.SKIPPED_NOT_CONFIGURED;
        }
        String trimmedEmail = email.trim();
        String trimmedNickname = isBlank(nickname) ? DEFAULT_NICKNAME : nickname.trim();

        // 2. 가입(A1) DTO의 검증 규칙을 그대로 쓴다(이메일 @Email·50자, 비밀번호 8~20자, 닉네임 50자).
        //    규칙을 따로 두면 가입·로그인에서 거부되는 값으로 계정이 만들어져 로그인할 수 없게 될 수 있다.
        //    맞지 않으면 기동은 계속하고 경고만 남긴다.
        if (!signUpRuleValid("email", trimmedEmail) || !signUpRuleValid("password", password)
                || !signUpRuleValid("nickname", trimmedNickname)) {
            log.warn("관리자 초기 계정 값이 가입 규칙(이메일 형식·50자, 비밀번호 8~20자, 닉네임 50자)에 맞지 않아 만들지 않음: email={}",
                    trimmedEmail);
            return Result.SKIPPED_INVALID;
        }

        // 3. 같은 이메일이 있으면 아무것도 바꾸지 않는다(기존 USER를 관리자로 올리거나 비밀번호를 덮어쓰지 않음)
        UserEntity existing = userRepository.findByEmail(trimmedEmail).orElse(null);
        if (existing != null) {
            if (existing.getRole() == Role.ADMIN) {
                log.info("관리자 초기 계정이 이미 있음: userId={}, email={}", existing.getId(), trimmedEmail);
            } else {
                log.warn("ADMIN_EMAIL과 같은 이메일의 일반 회원이 있어 관리자로 바꾸지 않음: userId={}, email={}",
                        existing.getId(), trimmedEmail);
            }
            return Result.SKIPPED_EXISTS;
        }
        if (profileRepository.existsByNickname(trimmedNickname)) {
            log.warn("관리자 닉네임이 이미 쓰이고 있어 관리자 초기 계정을 만들지 않음(ADMIN_NICKNAME으로 바꿀 수 있음): nickname={}",
                    trimmedNickname);
            return Result.SKIPPED_NICKNAME_TAKEN;
        }

        // 4. 관리자 회원 + 프로필(선호 종목 없음)
        UserEntity admin = userRepository.save(UserEntity.builder()
                .email(trimmedEmail)
                .password(passwordEncoder.encode(password))
                .gender(DEFAULT_GENDER)
                .role(Role.ADMIN)
                .build());
        profileRepository.save(ProfileEntity.builder()
                .nickname(trimmedNickname)
                .district(DEFAULT_DISTRICT)
                .user(admin)
                .build());

        log.info("관리자 초기 계정 생성: userId={}, email={}", admin.getId(), trimmedEmail);
        return Result.CREATED;
    }

    private boolean signUpRuleValid(String field, String value) {
        return validator.validateValue(UserSignUpRequestDto.class, field, value).isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
