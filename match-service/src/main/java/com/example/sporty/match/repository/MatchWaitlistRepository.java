package com.example.sporty.match.repository;

import com.example.sporty.match.domain.MatchWaitlistEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Order ties by PK so the first waiting user is deterministic. */
public interface MatchWaitlistRepository extends JpaRepository<MatchWaitlistEntity, Long> {
    Optional<MatchWaitlistEntity> findFirstByMatch_IdOrderByRegisteredAtAscIdAsc(Long matchId);

    Optional<MatchWaitlistEntity> findByMatch_IdAndUserId(Long matchId, Long userId);

    List<MatchWaitlistEntity> findAllByUserId(Long userId);

    boolean existsByMatch_IdAndUserId(Long matchId, Long userId);

    void deleteAllByMatch_Id(Long matchId);
}
