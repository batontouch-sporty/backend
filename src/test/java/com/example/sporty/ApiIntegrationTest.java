package com.example.sporty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.example.sporty.support.FacilityFixtures;
import com.example.sporty.features.exerciseMatching.repository.MatchRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.sporty.features.commons.token.JwtProvider;
import com.example.sporty.features.users.repository.UserRepository;
import com.example.sporty.features.users.domain.entity.UserEntity;
import com.example.sporty.features.users.domain.entity.UserStatus;
import com.example.sporty.features.users.domain.entity.Gender;
import com.example.sporty.features.exerciseMatching.repository.MatchParticipantRepository;
import com.example.sporty.features.exerciseMatching.domain.enums.MatchParticipantRole;

/** MockMvc runs in the test transaction: real filters/services/JPA, with automatic rollback. */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false",
        "jwt.secret=integration-test-secret-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtProvider tokens;
    @Autowired MatchParticipantRepository participants;
    @Autowired StringRedisTemplate redis;
    @Autowired MatchRepository matches;
    private long serviceId;
    private final List<String> redisKeys = new ArrayList<>();

    @BeforeEach
    void createFacility() {
        serviceId = FacilityFixtures.newServiceId();
        FacilityFixtures.create(em, serviceId);
    }

    @Test
    void nonexistentServiceReturns404WithoutWritingMatchOrParticipant() throws Exception {
        UserEntity owner = user(UserStatus.ACTIVE);
        var absent = FacilityFixtures.create(em, FacilityFixtures.newServiceId());
        long absentId = absent.getId();
        em.remove(absent);
        reload();
        long matchesBefore = matches.count();
        long participantsBefore = participants.count();
        var body = new java.util.HashMap<>(matchRequest("Unknown facility"));
        body.put("serviceId", absentId);
        mvc.perform(post("/api/matches").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
        assertThat(matches.count()).isEqualTo(matchesBefore);
        assertThat(participants.count()).isEqualTo(participantsBefore);
    }

    @AfterEach
    void cleanRedisFixtures() {
        if (!redisKeys.isEmpty()) redis.delete(redisKeys);
    }

    @Test
    void loginStoresExpiringRefreshTokenAndAccessTokenAuthenticatesRequests() throws Exception {
        UserEntity user = user(UserStatus.ACTIVE);
        reload();
        String key = "auth:refresh:" + user.getId();
        assertThat(redis.hasKey(key)).isFalse();
        redisKeys.add(key);
        var response = postJson("/api/auth/login", Map.of("email", user.getEmail(), "password", "TestPass123!"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(tokens.getAccessTokenExpirationSeconds()))
                .andReturn().getResponse().getContentAsString();
        var login = json.readTree(response);
        String refresh = login.get("refreshToken").asText();
        assertThat(redis.opsForValue().get(key)).isEqualTo(refresh);
        assertThat(redis.getExpire(key)).isBetween(tokens.getRefreshTokenExpirationSeconds() - 30,
                tokens.getRefreshTokenExpirationSeconds());
        mvc.perform(post("/api/matches").header("Authorization", "Bearer " + login.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(matchRequest(UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/matches").header("Authorization", "Bearer " + refresh)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(matchRequest("test"))))
                .andExpect(status().isUnauthorized());
        var second = postJson("/api/auth/login", Map.of("email", user.getEmail(), "password", "TestPass123!"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replacement = json.readTree(second).get("refreshToken").asText();
        assertThat(replacement).isNotEqualTo(refresh);
        assertThat(redis.opsForValue().get(key)).isEqualTo(replacement);
    }

    @Test
    void signupPersistsEncodedPasswordProfileAndPreferences() throws Exception {
        String email = email();
        String nickname = UUID.randomUUID().toString();
        signup(email, nickname).andExpect(status().isCreated());
        reload();
        UserEntity user = users.findByEmail(email).orElseThrow();
        assertThat(user.getPassword()).isNotEqualTo("TestPass123!");
        assertThat(passwords.matches("TestPass123!", user.getPassword())).isTrue();
        assertThat(user.getProfileEntity().getNickname()).isEqualTo(nickname);
        assertThat(user.getProfileEntity().getSports()).hasSize(1);
        assertThat(user.getProfileEntity().getSports().get(0).getSportType().name()).isEqualTo("FUTSAL");
    }

    @Test
    void duplicateEmailAndNicknameReturnConflictWithoutCreatingAnotherUser() throws Exception {
        String email = email();
        String nickname = UUID.randomUUID().toString();
        signup(email, nickname).andExpect(status().isCreated());
        reload();
        long before = users.count();
        signup(email, UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        signup(email(), nickname).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void malformedSignupAndLoginReturnBadRequest() throws Exception {
        postJson("/api/users", Map.of("email", "invalid", "password", "short"))
                .andExpect(status().isBadRequest());
        postJson("/api/auth/login", Map.of("email", "invalid", "password", "short"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signupRejectsMissingNullAndNullElementPreferencesWithoutSavingUser() throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("email", email(), "password", "TestPass123!",
                "nickname", UUID.randomUUID().toString(), "gender", "MALE", "district", "GANGNAM"));
        postJson("/api/users", body).andExpect(status().isBadRequest());
        body.put("sportTypes", null);
        postJson("/api/users", body).andExpect(status().isBadRequest());
        body.put("sportTypes", java.util.Arrays.asList("FUTSAL", null));
        postJson("/api/users", body).andExpect(status().isBadRequest());
        assertThat(users.existsByEmail((String) body.get("email"))).isFalse();
    }

    @Test
    void signupAllowsEmptyPreferencesAndStoresRepeatedSportsOnlyOnce() throws Exception {
        for (String[] sports : new String[][]{ {}, {"FUTSAL", "FUTSAL"} }) {
            String email = email();
            postJson("/api/users", Map.of("email", email, "password", "TestPass123!",
                    "nickname", UUID.randomUUID().toString(), "gender", "MALE", "district", "GANGNAM",
                    "sportTypes", sports)).andExpect(status().isCreated());
            reload();
            assertThat(users.findByEmail(email).orElseThrow().getProfileEntity().getSports())
                    .hasSize(sports.length == 0 ? 0 : 1);
        }
    }

    @Test
    void loginRejectsUnknownUserWrongPasswordAndWithdrawnUser() throws Exception {
        UserEntity active = user(UserStatus.ACTIVE);
        UserEntity withdrawn = user(UserStatus.WITHDRAWN);
        reload();
        for (String email : new String[]{email(), active.getEmail(), withdrawn.getEmail()}) {
            postJson("/api/auth/login", Map.of("email", email,
                    "password", email.equals(active.getEmail()) ? "WrongPass123!" : "TestPass123!"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        }
    }

    @Test
    void authenticatedCreationPersistsOwnerAndSupportsSearchAndDetail() throws Exception {
        UserEntity owner = user(UserStatus.ACTIVE);
        UserEntity outsider = user(UserStatus.ACTIVE);
        String title = UUID.randomUUID().toString();
        String response = mvc.perform(post("/api/matches").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(matchRequest(title))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long matchId = json.readValue(response, Long.class);
        reload();
        assertThat(matches.findById(matchId).orElseThrow().getGenderGroup().name()).isEqualTo("MIXED");
        assertThat(participants.findByMatch_IdAndUserId(matchId, owner.getId()))
                .hasValueSatisfying(p -> assertThat(p.getRole()).isEqualTo(MatchParticipantRole.OWNER));
        mvc.perform(get("/api/matches").param("titleKeyword", title).param("serviceId", Long.toString(serviceId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].matchId").value(matchId))
                .andExpect(jsonPath("$[0].genderGroup").value("MIXED"));
        mvc.perform(get("/api/matches/{id}", matchId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isOwner").value(true))
                .andExpect(jsonPath("$.isParticipant").value(true))
                .andExpect(jsonPath("$.currentParticipantCount").value(1))
                .andExpect(jsonPath("$.genderGroup").value("MIXED"));
        for (String authorization : new String[]{"", bearer(outsider), "Bearer invalid"}) {
            mvc.perform(get("/api/matches/{id}", matchId).header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.isOwner").value(false))
                    .andExpect(jsonPath("$.isParticipant").value(false));
        }
    }

    @Test
    void creationRequiresAccessTokenAndActiveExistingUser() throws Exception {
        UserEntity user = user(UserStatus.WITHDRAWN);
        for (String authorization : new String[]{"", "Bearer broken", "Bearer " + tokens.createRefreshToken(user.getId())}) {
            mvc.perform(post("/api/matches").header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(matchRequest("test"))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/matches").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(matchRequest("test"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER_WITHDRAWN"));
        // Allocate and remove our own fixture to get a definitely absent ID.
        UserEntity absent = user(UserStatus.ACTIVE);
        em.remove(absent);
        reload();
        mvc.perform(post("/api/matches").header("Authorization", bearer(absent))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(matchRequest("test"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MATCH_USER_NOT_FOUND"));
    }

    @Test
    void invalidMatchInputsReturnBadRequest() throws Exception {
        UserEntity owner = user(UserStatus.ACTIVE);
        mvc.perform(post("/api/matches").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        for (String parameter : new String[]{"serviceId", "maxParticipant"}) {
            mvc.perform(get("/api/matches").param(parameter, "-1")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/matches").param("startAt", "2026-10-10T20:00:00")
                .param("endAt", "2026-10-10T18:00:00")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/matches/not-a-number")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/matches/-1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));
    }

    ResultActions signup(String email, String nickname) throws Exception {
        return postJson("/api/users", Map.of("email", email, "password", "TestPass123!",
                "nickname", nickname, "gender", "MALE", "district", "GANGNAM",
                "sportTypes", new String[]{"FUTSAL"}));
    }

    ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    UserEntity user(UserStatus status) {
        return users.saveAndFlush(UserEntity.builder().email(email()).gender(Gender.MALE)
                .password(passwords.encode("TestPass123!")).status(status).build());
    }

    String email() { return UUID.randomUUID() + "@test.invalid"; }
    String bearer(UserEntity user) { return "Bearer " + tokens.createAccessToken(user.getId()); }
    void reload() { em.flush(); em.clear(); }
    Map<String, Object> matchRequest(String title) {
        return Map.of("serviceId", serviceId, "title", title, "description", "integration test",
                "startAt", "2026-10-10T18:00:00", "endAt", "2026-10-10T20:00:00",
                "maxParticipant", 10, "skillLevel", "BEGINNER", "sportType", "FUTSAL", "genderGroup", "MIXED");
    }
}
