package com.example.sporty.user.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.District;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import com.example.sporty.user.profiles.repository.ProfileRepository;
import com.example.sporty.user.sportpreference.domain.entity.SportPreferenceEntity;
import com.example.sporty.user.sportpreference.repository.SportPreferenceRepository;
import com.example.sporty.user.users.domain.entity.Gender;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;

/**
 * 내부 API I1·I2. match-service Feign(MemberClient)이 기대하는 필드 이름·형식을 확인한다.
 * 로그인 헤더 없이 부른다(서비스 간 호출). 테스트마다 롤백된다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class InternalUserApiTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired SportPreferenceRepository preferences;
    @MockitoBean RefreshTokenService refreshTokens;

    // I1 회원 단건 조회
    @Test
    void getUserReturnsGenderStatusDistrictAndSportsWithoutLoginHeader() throws Exception {
        UserEntity user = user(UserStatus.ACTIVE, Gender.FEMALE, "mapo-runner", SportType.RUNNING, SportType.FUTSAL);

        mvc.perform(get("/internal/users/{id}", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.district").value("MAPO"))
                .andExpect(jsonPath("$.sportTypes", contains("FUTSAL", "RUNNING")));
    }

    @Test
    void getUserReturnsWithdrawingAndWithdrawnStatusInsteadOf404() throws Exception {
        UserEntity withdrawing = user(UserStatus.WITHDRAWING, Gender.MALE, nickname());
        UserEntity withdrawn = user(UserStatus.WITHDRAWN, Gender.MALE, nickname());

        mvc.perform(get("/internal/users/{id}", withdrawing.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWING"));
        mvc.perform(get("/internal/users/{id}", withdrawn.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.sportTypes", hasSize(0)));
    }

    @Test
    void getUserOfUnknownIdIsUserNotFound() throws Exception {
        mvc.perform(get("/internal/users/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void getUserWithNonNumericIdIsInvalidInput() throws Exception {
        mvc.perform(get("/internal/users/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // I2 회원 닉네임 일괄 조회
    @Test
    void getProfilesReadsCsvIdsSkipsUnknownAndDuplicateIds() throws Exception {
        UserEntity a = user(UserStatus.ACTIVE, Gender.MALE, "songpa-ball");
        UserEntity b = user(UserStatus.WITHDRAWN, Gender.FEMALE, "mapo-run");
        ProfileEntity profileA = profiles.findByUserId(a.getId()).orElseThrow();
        ProfileEntity profileB = profiles.findByUserId(b.getId()).orElseThrow();

        mvc.perform(get("/internal/users").param("ids", b.getId() + ",999999," + a.getId() + "," + a.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(a.getId()))
                .andExpect(jsonPath("$[0].profileId").value(profileA.getId()))
                .andExpect(jsonPath("$[0].nickname").value("songpa-ball"))
                .andExpect(jsonPath("$[0].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$[1].id").value(b.getId()))
                .andExpect(jsonPath("$[1].profileId").value(profileB.getId()))
                .andExpect(jsonPath("$[1].nickname").value("mapo-run"));
    }

    @Test
    void getProfilesAlsoAcceptsRepeatedIdsParameter() throws Exception {
        UserEntity a = user(UserStatus.ACTIVE, Gender.MALE, nickname());
        UserEntity b = user(UserStatus.ACTIVE, Gender.MALE, nickname());

        mvc.perform(get("/internal/users").param("ids", String.valueOf(a.getId()), String.valueOf(b.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getProfilesWithEmptyMissingOrUnknownIdsIsEmptyArray() throws Exception {
        mvc.perform(get("/internal/users").param("ids", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/internal/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/internal/users").param("ids", "999998,999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getProfilesWithNonNumericIdIsInvalidInput() throws Exception {
        mvc.perform(get("/internal/users").param("ids", "1,abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void getProfilesUsesOneQueryRegardlessOfMemberCount() throws Exception {
        String ids = String.join(",", List.of(
                user(UserStatus.ACTIVE, Gender.MALE, nickname()).getId().toString(),
                user(UserStatus.ACTIVE, Gender.MALE, nickname()).getId().toString(),
                user(UserStatus.ACTIVE, Gender.MALE, nickname()).getId().toString(),
                user(UserStatus.ACTIVE, Gender.MALE, nickname()).getId().toString(),
                user(UserStatus.ACTIVE, Gender.MALE, nickname()).getId().toString()));
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        mvc.perform(get("/internal/users").param("ids", ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    private UserEntity user(UserStatus status, Gender gender, String nickname, SportType... sports) {
        UserEntity user = users.save(UserEntity.builder().email(UUID.randomUUID().toString().substring(0, 20) + "@test.io")
                .password("encoded").gender(gender).status(status).build());
        ProfileEntity profile = profiles.save(ProfileEntity.builder().nickname(nickname)
                .district(District.MAPO).user(user).build());
        for (SportType sport : sports) {
            preferences.save(SportPreferenceEntity.builder().profile(profile).sportType(sport).build());
        }
        em.flush();
        em.clear();
        return user;
    }

    private String nickname() { return "n-" + UUID.randomUUID().toString().substring(0, 12); }
}
