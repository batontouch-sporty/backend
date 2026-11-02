package com.example.sporty.match.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.repository.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:matchstats;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "match.outbox.enabled=false", "match.withdrawal.enabled=false", "match.starting-soon.enabled=false"
})
@AutoConfigureMockMvc
class MatchStatsTest {
    @Autowired MockMvc mvc;
    @Autowired MatchStatsService service;
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;

    @BeforeEach
    void clean() {
        waitlist.deleteAll();
        participants.deleteAll();
        matches.deleteAll();
    }

    @Test
    void emptyStatsIncludesEveryStatusAndZeros() throws Exception {
        mvc.perform(get("/api/admin/stats").header("X-User-Id", "1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.total").value(0))
                .andExpect(jsonPath("$.matches.byStatus.RECRUITING").value(0))
                .andExpect(jsonPath("$.matches.byStatus.CLOSED").value(0))
                .andExpect(jsonPath("$.matches.byStatus.IN_PROGRESS").value(0))
                .andExpect(jsonPath("$.matches.byStatus.COMPLETED").value(0))
                .andExpect(jsonPath("$.matches.byStatus.CANCELLED").value(0))
                .andExpect(jsonPath("$.participants.total").value(0));
    }

    @Test
    void countsAllStatesOwnersAndRetainedCancelledParticipantsButNotWaitlist() throws Exception {
        var now = LocalDateTime.now();
        for (var status : MatchStatus.values()) {
            var match = new MatchEntity(new MatchEntity.Details("Stats fixture", null,
                    now.minusDays(100), now.minusDays(99), 3, SkillLevel.BEGINNER,
                    SportType.FUTSAL, GenderGroup.MIXED),
                    new MatchEntity.FacilitySnapshot(1, "Court", "Location", "Region", true, null));
            if (status == MatchStatus.CANCELLED) match.cancel("Fixture", now);
            else if (status != MatchStatus.RECRUITING) match.changeStatus(status, now);
            matches.saveAndFlush(match);
            participants.saveAndFlush(new MatchParticipantEntity(match, 1L, MatchParticipantRole.OWNER));
            participants.saveAndFlush(new MatchParticipantEntity(match, 2L, MatchParticipantRole.PARTICIPANT));
            waitlist.saveAndFlush(new MatchWaitlistEntity(match, 3L));
        }
        var stats = service.getStats();
        assertThat(stats.matches().total()).isEqualTo(5);
        assertThat(stats.matches().byStatus().values()).containsOnly(1L);
        assertThat(stats.matches().byStatus()).hasSize(5);
        assertThat(stats.participants().total()).isEqualTo(10);
        mvc.perform(get("/api/admin/stats").header("X-User-Id", "1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.total").value(5))
                .andExpect(jsonPath("$.matches.byStatus.RECRUITING").value(1))
                .andExpect(jsonPath("$.matches.byStatus.CLOSED").value(1))
                .andExpect(jsonPath("$.matches.byStatus.IN_PROGRESS").value(1))
                .andExpect(jsonPath("$.matches.byStatus.COMPLETED").value(1))
                .andExpect(jsonPath("$.matches.byStatus.CANCELLED").value(1))
                .andExpect(jsonPath("$.participants.total").value(10));
    }

    @Test
    void requiresIdentityAndAdminRole() throws Exception {
        mvc.perform(get("/api/admin/stats"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        for (var identity : new String[]{"0", "-1", "invalid"})
            mvc.perform(get("/api/admin/stats").header("X-User-Id", identity).header("X-User-Role", "ADMIN"))
                    .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stats").header("X-User-Id", "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/stats").header("X-User-Id", "1").header("X-User-Role", "USER"))
                .andExpect(status().isForbidden());
    }
}
