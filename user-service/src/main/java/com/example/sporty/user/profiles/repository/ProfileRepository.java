package com.example.sporty.user.profiles.repository;

import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProfileRepository extends JpaRepository<ProfileEntity, Long> {

    boolean existsByNickname(String nickname);

    // [PR-01] 해당 닉네임을 사용하는 다른 프로필이 있는지 검사
    boolean existsByNicknameAndIdNot(String nickname, Long id);

    Optional<ProfileEntity> findByUserId(Long userId);

    // [I2] 여러 회원의 프로필을 쿼리 한 번으로 읽는다. user_id 컬럼만 비교하므로 user 테이블은 조인하지 않는다.
    List<ProfileEntity> findByUserIdIn(Collection<Long> userIds);

}
