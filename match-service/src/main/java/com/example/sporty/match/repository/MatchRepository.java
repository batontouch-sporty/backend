package com.example.sporty.match.repository;

import com.example.sporty.match.domain.MatchEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Shared by CRUD and join/leave. Lock the match row before checking capacity. */
public interface MatchRepository extends JpaRepository<MatchEntity, Long>, JpaSpecificationExecutor<MatchEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MatchEntity m where m.id = :matchId")
    Optional<MatchEntity> findByIdForUpdate(@Param("matchId") Long matchId);

    /**
     * Only for the pre-start hard-delete API. Delete participant rows first; the DB then cascades
     * waitlist rows. Clear the persistence context so loaded waitlist entities cannot retain a
     * reference to the deleted match.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM matches WHERE id = :matchId", nativeQuery = true)
    int deleteHardById(@Param("matchId") Long matchId);

    interface ParticipantCount {
        Long getMatchId();
        Long getParticipantCount();
    }

    @Query("""
            select p.match.id as matchId, count(p) as participantCount
            from MatchParticipantEntity p
            where p.match.id in :matchIds
            group by p.match.id
            """)
    List<ParticipantCount> countParticipantsByMatchIds(@Param("matchIds") List<Long> matchIds);
}
