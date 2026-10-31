package com.example.sporty.match.repository;

import com.example.sporty.match.domain.MatchParticipantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** userId queries stay in match_db; user-service is consulted separately through its API. */
public interface MatchParticipantRepository extends JpaRepository<MatchParticipantEntity, Long> {
    @EntityGraph(attributePaths = "match")
    List<MatchParticipantEntity> findAllByUserIdOrderByMatch_StartAtDescMatch_IdDesc(Long userId);

    List<MatchParticipantEntity> findAllByMatch_IdOrderByIdAsc(Long matchId);

    long countByMatch_Id(Long matchId);

    boolean existsByMatch_IdAndUserId(Long matchId, Long userId);

    Optional<MatchParticipantEntity> findByMatch_IdAndUserId(Long matchId, Long userId);

    void deleteAllByMatch_Id(Long matchId);
}
