package com.example.sporty.match.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.sporty.match.domain.GenderGroup;
import com.example.sporty.match.domain.MatchEntity;
import com.example.sporty.match.domain.MatchParticipantEntity;
import com.example.sporty.match.domain.MatchParticipantRole;
import com.example.sporty.match.domain.MatchWaitlistEntity;
import com.example.sporty.match.domain.SkillLevel;
import com.example.sporty.match.domain.SportType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class MatchPersistenceTest {
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;

    @Test
    void sharedRepositoriesUseLocalMatchRelationsAndValueOnlyUserAndFacilityIds() {
        MatchEntity match = matches.saveAndFlush(new MatchEntity(
                new MatchEntity.Details("농구", null,
                        LocalDateTime.of(2026, 11, 8, 19, 0),
                        LocalDateTime.of(2026, 11, 8, 21, 0),
                        3, SkillLevel.BEGINNER, SportType.BASKETBALL, GenderGroup.MIXED),
                new MatchEntity.FacilitySnapshot(12, "체육관", "잠실", "SONGPA",
                        true, "https://example.org/reserve")));
        participants.saveAndFlush(new MatchParticipantEntity(match, 5, MatchParticipantRole.OWNER));
        waitlist.saveAndFlush(new MatchWaitlistEntity(match, 6));
        waitlist.saveAndFlush(new MatchWaitlistEntity(match, 7));

        assertThat(matches.findByIdForUpdate(match.getId())).isPresent();
        assertThat(matches.countParticipantsByMatchIds(java.util.List.of(match.getId()))
                .get(0).getParticipantCount()).isEqualTo(1);
        assertThat(participants.findByMatch_IdAndUserId(match.getId(), 5L)
                .orElseThrow().getRole()).isEqualTo(MatchParticipantRole.OWNER);
        assertThat(participants.findAllByUserIdOrderByMatch_StartAtDescMatch_IdDesc(5L))
                .extracting(MatchParticipantEntity::getUserId).containsExactly(5L);
        assertThat(waitlist.findFirstByMatch_IdOrderByRegisteredAtAscIdAsc(match.getId())
                .orElseThrow().getUserId()).isEqualTo(6L);
        assertThat(match.getFacilityId()).isEqualTo(12L);
        assertThat(match.getUrl()).isEqualTo("https://example.org/reserve");
    }

    @Test
    void hardDeletingAMatchRemovesWaitlistRowsThroughTheLocalForeignKey() {
        MatchEntity match = matches.saveAndFlush(new MatchEntity(
                new MatchEntity.Details("테니스", null,
                        LocalDateTime.of(2026, 11, 9, 19, 0),
                        LocalDateTime.of(2026, 11, 9, 21, 0),
                        2, SkillLevel.BEGINNER, SportType.TENNIS, GenderGroup.MIXED),
                new MatchEntity.FacilitySnapshot(20, "코트", "송파", "SONGPA",
                        false, null)));
        waitlist.saveAndFlush(new MatchWaitlistEntity(match, 8));
        participants.saveAndFlush(new MatchParticipantEntity(match, 9, MatchParticipantRole.OWNER));

        participants.deleteAllByMatch_Id(match.getId());
        participants.flush();
        assertThat(matches.deleteHardById(match.getId())).isEqualTo(1);

        assertThat(waitlist.count()).isZero();
    }
}
