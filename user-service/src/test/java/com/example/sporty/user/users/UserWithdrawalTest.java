package com.example.sporty.user.users;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.*;
import com.example.sporty.user.auth.service.RefreshTokenService;
import com.example.sporty.user.commons.exception.users.PasswordMismatchException;
import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.users.domain.entity.*;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.users.service.UserService;

// 1차 UserWithdrawalTest를 옮겼다. 매치 정리는 match-service로 넘어가 회원 상태·토큰만 검증한다.
// 서비스 트랜잭션이 실제로 커밋·롤백되도록 테스트 트랜잭션은 끈다.
@DataJpaTest
@Import(UserService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserWithdrawalTest {
    @Autowired UserService service;
    @Autowired UserRepository users;
    @MockitoBean PasswordEncoder passwords;
    @MockitoBean RefreshTokenService tokens;
    long userId;

    @BeforeEach void setup() {
        users.deleteAllInBatch();
        userId = users.save(UserEntity.builder().email(UUID.randomUUID() + "@test.io").password("hash").gender(Gender.MALE).build()).getId();
        when(passwords.matches("correct", "hash")).thenReturn(true);
    }

    @Test void withdrawsAndDeletesRefreshToken() {
        service.withdrawal(userId, "correct");
        UserEntity user = users.findById(userId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getWithdrawnAt()).isNotNull();
        verify(tokens).delete(userId);
    }

    @Test void withdrawingTwiceKeepsFirstWithdrawnAt() {
        service.withdrawal(userId, "correct");
        var first = users.findById(userId).orElseThrow().getWithdrawnAt();
        service.withdrawal(userId, "correct");
        UserEntity user = users.findById(userId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getWithdrawnAt()).isEqualTo(first);
    }

    @Test void wrongOrMissingPasswordChangesNothing() {
        for (String password : new String[]{null, "", " ", "wrong"})
            assertThatThrownBy(() -> service.withdrawal(userId, password)).isInstanceOf(PasswordMismatchException.class);
        assertThat(users.findById(userId).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        verifyNoInteractions(tokens);
    }

    @Test void unknownUserIsNotFound() {
        assertThatThrownBy(() -> service.withdrawal(userId + 100, "correct")).isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(tokens);
    }

    @Test void tokenStoreFailureRollsBackStatus() {
        doThrow(new IllegalStateException("token store failure")).when(tokens).delete(userId);
        assertThatThrownBy(() -> service.withdrawal(userId, "correct")).isInstanceOf(IllegalStateException.class);
        UserEntity user = users.findById(userId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getWithdrawnAt()).isNull();
    }
}
