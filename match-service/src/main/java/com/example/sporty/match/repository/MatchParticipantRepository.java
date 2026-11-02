package com.example.sporty.match.repository;

import com.example.sporty.match.domain.MatchParticipantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** userId queries stay in match_db; user-service is consulted separately through its API. */
public interface MatchParticipantRepository extends JpaRepository<MatchParticipantEntity, Long> {
    @Query("select p from MatchParticipantEntity p where p.userId=?1 and p.participationStatus='CONFIRMED' order by p.match.startAt desc,p.match.id desc")
    @EntityGraph(attributePaths = "match")
    List<MatchParticipantEntity> findAllByUserIdOrderByMatch_StartAtDescMatch_IdDesc(Long userId);

    @Query("select p from MatchParticipantEntity p where p.match.id=?1 and p.participationStatus='CONFIRMED' order by p.id")
    List<MatchParticipantEntity> findAllByMatch_IdOrderByIdAsc(Long matchId);

    @Query("select count(p) from MatchParticipantEntity p where p.match.id=?1 and p.participationStatus='CONFIRMED'")
    long countByMatch_Id(Long matchId);

    @Query("select (count(p)>0) from MatchParticipantEntity p where p.match.id=?1 and p.userId=?2 and p.participationStatus in ('CONFIRMED','PENDING')")
    boolean existsByMatch_IdAndUserId(Long matchId, Long userId);

    Optional<MatchParticipantEntity> findByMatch_IdAndUserId(Long matchId, Long userId);

    List<MatchParticipantEntity> findAllByMatch_Id(Long matchId);
    Optional<MatchParticipantEntity> findByRequestId(String requestId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MatchParticipantEntity p where p.match.id=?1 and p.userId=?2")
    Optional<MatchParticipantEntity> findRequestForUpdate(Long matchId,Long userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MatchParticipantEntity p where p.match.id=?1 and p.participationStatus='CONFIRMED' order by p.id")
    List<MatchParticipantEntity> findConfirmedForUpdate(Long matchId);

    /** Used by physical FK/CASCADE persistence tests; A13 cancellation preserves these rows. */
    void deleteAllByMatch_Id(Long matchId);
}
