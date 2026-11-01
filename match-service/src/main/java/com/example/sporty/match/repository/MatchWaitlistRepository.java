package com.example.sporty.match.repository;

import com.example.sporty.match.domain.MatchWaitlistEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Order ties by PK so the first waiting user is deterministic. */
public interface MatchWaitlistRepository extends JpaRepository<MatchWaitlistEntity, Long> {
    Optional<MatchWaitlistEntity> findFirstByMatch_IdOrderByRegisteredAtAscIdAsc(Long matchId);

    Optional<MatchWaitlistEntity> findByMatch_IdAndUserId(Long matchId, Long userId);

    List<MatchWaitlistEntity> findAllByUserId(Long userId);

    boolean existsByMatch_IdAndUserId(Long matchId, Long userId);

    @Query(
            "select count(w) from MatchWaitlistEntity w where w.match.id=:matchId and"
                + " (w.registeredAt<:at or (w.registeredAt=:at and w.id<=:id))")
    long position(@Param("matchId") Long matchId, @Param("at") Instant at, @Param("id") Long id);

    void deleteAllByMatch_Id(Long matchId);
}
