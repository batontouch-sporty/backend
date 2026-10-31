package com.example.sporty.match.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MatchEntityStatusTest {
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 11, 8, 18, 0);
    private static final LocalDateTime LATER = FIRST.plusMinutes(1);

    @Test
    void cancelledMatchCannotReopenOrOverwriteCancellation() {
        MatchEntity match = newMatch();
        match.cancel("시설 이용 불가", FIRST);

        assertThatThrownBy(() -> match.reopenRecruitment(LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.changeStatus(MatchStatus.RECRUITING, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.changeStatus(MatchStatus.CANCELLED, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.cancel("다른 사유", LATER))
                .isInstanceOf(IllegalStateException.class);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(match.getCancelReason()).isEqualTo("시설 이용 불가");
        assertThat(match.getCancelledAt()).isEqualTo(FIRST);
        assertThat(match.getStatusChangedAt()).isEqualTo(FIRST);
    }

    @Test
    void completedMatchCannotReopenOrBeCancelled() {
        MatchEntity match = newMatch();
        match.changeStatus(MatchStatus.COMPLETED, FIRST);

        assertThatThrownBy(() -> match.reopenRecruitment(LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.changeStatus(MatchStatus.CANCELLED, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.cancel("뒤늦은 취소", LATER))
                .isInstanceOf(IllegalStateException.class);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(match.getStatusChangedAt()).isEqualTo(FIRST);
        assertThat(match.getCancelReason()).isNull();
        assertThat(match.getCancelledAt()).isNull();
    }

    @Test
    void recruitmentChangesAreRejectedAfterMatchStarts() {
        MatchEntity match = newMatch();
        match.changeStatus(MatchStatus.IN_PROGRESS, FIRST);

        assertThatThrownBy(() -> match.closeRecruitment(LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.reopenRecruitment(LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.changeStatus(MatchStatus.RECRUITING, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> match.changeStatus(MatchStatus.CLOSED, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(match.getStatus()).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(match.getStatusChangedAt()).isEqualTo(FIRST);
    }

    @Test
    void invalidFacilityDoesNotPartiallyUpdateDetails() {
        MatchEntity match = newMatch();
        MatchEntity.Details changed = new MatchEntity.Details("변경된 제목", null,
                FIRST.plusDays(2), FIRST.plusDays(2).plusHours(2), 5,
                SkillLevel.BEGINNER, SportType.BASKETBALL, GenderGroup.MIXED);

        assertThatThrownBy(() -> match.update(changed,
                new MatchEntity.FacilitySnapshot(0, "체육관", "잠실", "SONGPA", true, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(match.getTitle()).isEqualTo("농구");
        assertThat(match.getStartAt()).isEqualTo(FIRST.plusDays(1));
        assertThat(match.getMaxParticipant()).isEqualTo(4);
        assertThat(match.getFacilityId()).isEqualTo(12);

        assertThatThrownBy(() -> match.update(changed, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(match.getTitle()).isEqualTo("농구");
    }

    @Test
    void invalidDetailsDoNotPartiallyUpdateFacility() {
        MatchEntity match = newMatch();
        MatchEntity.Details invalid = new MatchEntity.Details("변경된 제목", null,
                FIRST.plusDays(2), FIRST.plusDays(2).plusHours(2), 5,
                SkillLevel.BEGINNER, null, GenderGroup.MIXED);

        assertThatThrownBy(() -> match.update(invalid,
                new MatchEntity.FacilitySnapshot(99, "새 체육관", "강남", "GANGNAM", true, null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(match.getTitle()).isEqualTo("농구");
        assertThat(match.getFacilityId()).isEqualTo(12);
    }

    @Test
    void invalidCancellationTimeDoesNotChangeMetadata() {
        MatchEntity match = newMatch();

        assertThatThrownBy(() -> match.cancel("사유", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(match.getStatus()).isEqualTo(MatchStatus.RECRUITING);
        assertThat(match.getCancelReason()).isNull();
        assertThat(match.getCancelledAt()).isNull();
    }

    private MatchEntity newMatch() {
        return new MatchEntity(
                new MatchEntity.Details("농구", null, FIRST.plusDays(1),
                        FIRST.plusDays(1).plusHours(2), 4,
                        SkillLevel.BEGINNER, SportType.BASKETBALL, GenderGroup.MIXED),
                new MatchEntity.FacilitySnapshot(12, "체육관", "잠실", "SONGPA", true, null));
    }
}
