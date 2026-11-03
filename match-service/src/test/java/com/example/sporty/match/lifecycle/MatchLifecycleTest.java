package com.example.sporty.match.lifecycle;

import com.example.sporty.match.crud.*;
import com.example.sporty.match.deposit.*;
import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.participation.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.cloud.config.enabled=false","spring.config.import=",
        "eureka.client.enabled=false","spring.datasource.url=jdbc:h2:mem:lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop","match.outbox.enabled=false","match.withdrawal.enabled=false",
        "match.deposit.enabled=true","spring.kafka.listener.auto-startup=false","match.deposit.maintenance-delay-ms=86400000"})
@AutoConfigureMockMvc
class MatchLifecycleTest {
    @Autowired MatchLifecycle lifecycle;
    @Autowired DepositSaga saga;
    @Autowired DepositStore store;
    @Autowired MatchCrudService crud;
    @Autowired ParticipationService participation;
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;
    @Autowired ParticipationEvents events;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @MockitoBean MatchRemoteChecks checks;
    @MockitoBean(name="participationMemberLocks") MemberLocks locks;
    TransactionTemplate tx;
    LocalDateTime now;

    @BeforeEach void setup() {
        tx=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS outbox_event(event_id VARCHAR(64) PRIMARY KEY,aggregate_id VARCHAR(64),event_type VARCHAR(80),payload CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS processed_events(event_id VARCHAR(64) PRIMARY KEY,event_type VARCHAR(80),processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_events");
        waitlist.deleteAll(); participants.deleteAll(); matches.deleteAll();
        now=LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        when(checks.checkCreation(anyLong(),anyLong(),any(),any())).thenReturn(new MatchRemoteChecks.CreationContext(
            new MemberClient.Member(1L,"MALE","ACTIVE",null,List.of()),
            new MatchRemoteChecks.FacilitySnapshot(12L,"Court","Location","송파구",true,"https://example.org")));
        when(checks.activeMember(anyLong())).thenAnswer(i->new MemberClient.Member(i.getArgument(0),"MALE","ACTIVE",null,List.of()));
        when(checks.profiles(anyList())).thenReturn(Map.of());
    }

    String id(long match,long user) { return participants.findByMatch_IdAndUserId(match,user).orElseThrow().getRequestId(); }
    void charged(String id) {
        var r=tx.execute(s->store.get(id));
        saga.handle("user.deposit-charged.v1",mapper.valueToTree(Map.of("eventId",UUID.randomUUID().toString(),
            "requestId",id,"userId",r.userId,"matchId",r.matchId,"occurredAt",OffsetDateTime.now().toString())));
    }
    /** Owner 1 plus confirmed users, then the schedule is moved to the given times. */
    long match(int capacity,LocalDateTime start,LocalDateTime end,long... users) {
        var s=now.plusDays(2);
        long m=crud.create(1L,new MatchDtos.Create(12L,"Lifecycle","Description",s,s.plusHours(1),capacity,
            SkillLevel.BEGINNER,SportType.TENNIS,GenderGroup.MIXED));
        charged(id(m,1));
        for(long u:users) { participation.join(m,u); charged(id(m,u)); }
        jdbc.update("UPDATE matches SET start_at=?,end_at=? WHERE id=?",start,end,m);
        return m;
    }
    /** Joining needs a future start; restore the moved schedule afterwards. */
    void joinPending(long m,long u) {
        var row=jdbc.queryForMap("SELECT start_at,end_at FROM matches WHERE id=?",m);
        jdbc.update("UPDATE matches SET start_at=?,end_at=? WHERE id=?",now.plusDays(2),now.plusDays(2).plusHours(1),m);
        participation.join(m,u);
        jdbc.update("UPDATE matches SET start_at=?,end_at=? WHERE id=?",row.get("START_AT"),row.get("END_AT"),m);
    }
    MatchStatus state(long m) { return matches.findById(m).orElseThrow().getStatus(); }
    List<JsonNode> outbox(String topic) {
        return jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type=? ORDER BY created_at,event_id",String.class,topic)
            .stream().map(p->{ try { return mapper.readTree(p); } catch(Exception e) { throw new RuntimeException(e); } }).toList();
    }
    List<JsonNode> statusChanges() { return outbox("match.status-changed.v1"); }
    void clearOutbox() { jdbc.update("DELETE FROM outbox_event WHERE event_type IN ('match.status-changed.v1','match.joined.v1')"); }
    long participant(long m,long u) { return participants.findByMatch_IdAndUserId(m,u).orElseThrow().getId(); }
    void markNoShow(long m,long u) {
        jdbc.update("UPDATE match_participants SET match_participant_status='NO_SHOW' WHERE match_id=? AND user_id=?",m,u);
    }

    @Test void startedMatchMovesToInProgressAndEmitsStatusChangedToConfirmed() {
        long m=match(3,now.minusMinutes(1),now.plusHours(1),2L);
        joinPending(m,3L);   // PENDING request is not a recipient
        clearOutbox();
        assertThat(lifecycle.candidates(0)).containsExactly(m);
        assertThat(lifecycle.candidates(m)).isEmpty();
        assertThat(lifecycle.candidates(m-1)).containsExactly(m);
        lifecycle.advance(m);
        assertThat(state(m)).isEqualTo(MatchStatus.IN_PROGRESS);
        var e=statusChanges(); assertThat(e).hasSize(1);
        assertThat(e.get(0).path("from").asText()).isEqualTo("RECRUITING");
        assertThat(e.get(0).path("to").asText()).isEqualTo("IN_PROGRESS");
        assertThat(e.get(0).path("ownerId").asLong()).isEqualTo(1);
        assertThat(e.get(0).path("participantIds").toString()).isEqualTo("[1,2]");
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).isEmpty();
    }
    @Test void endedMatchCompletesAndRequestsSettlementPerConfirmedParticipant() {
        long m=match(3,now.minusHours(2),now.plusHours(1),2L,3L);
        lifecycle.advance(m); clearOutbox(); markNoShow(m,3L);
        jdbc.update("UPDATE matches SET end_at=? WHERE id=?",now.minusMinutes(1),m);
        lifecycle.advance(m);
        assertThat(state(m)).isEqualTo(MatchStatus.COMPLETED);
        var e=statusChanges(); assertThat(e).hasSize(1);
        assertThat(e.get(0).path("from").asText()).isEqualTo("IN_PROGRESS");
        assertThat(e.get(0).path("to").asText()).isEqualTo("COMPLETED");
        var s=outbox(MatchLifecycle.SETTLEMENT); assertThat(s).hasSize(3);
        var byUser=new HashMap<Long,JsonNode>(); s.forEach(n->byUser.put(n.path("userId").asLong(),n));
        assertThat(byUser.get(1L).path("result").asText()).isEqualTo("ATTENDED");
        assertThat(byUser.get(2L).path("result").asText()).isEqualTo("ATTENDED");
        assertThat(byUser.get(3L).path("result").asText()).isEqualTo("NO_SHOW");
        var first=byUser.get(3L);
        assertThat(first.path("requestId").asText()).isEqualTo(id(m,3));
        assertThat(first.path("matchId").asLong()).isEqualTo(m);
        assertThat(UUID.fromString(first.path("eventId").asText()).toString()).isNotEqualTo(id(m,3));
        assertThat(OffsetDateTime.parse(first.path("occurredAt").asText())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT aggregate_id FROM outbox_event WHERE event_id=?",String.class,
            first.path("eventId").asText())).isEqualTo("3");
    }
    @Test void matchPastStartAndEndGoesThroughInProgressToCompleted() {
        long m=match(3,now.minusHours(3),now.minusHours(1),2L);
        clearOutbox();
        lifecycle.advance(m);
        assertThat(state(m)).isEqualTo(MatchStatus.COMPLETED);
        assertThat(statusChanges()).extracting(n->n.path("from").asText()+">"+n.path("to").asText())
            .containsExactly("RECRUITING>IN_PROGRESS","IN_PROGRESS>COMPLETED");
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).hasSize(2);
    }
    @Test void closedMatchAlsoStarts() {
        long m=match(2,now.minusMinutes(1),now.plusHours(1),2L);
        assertThat(state(m)).isEqualTo(MatchStatus.CLOSED);
        clearOutbox(); lifecycle.advance(m);
        assertThat(statusChanges().get(0).path("from").asText()).isEqualTo("CLOSED");
    }
    @Test void underTwoConfirmedParticipantsSettlesAsUnderMinimum() {
        long m=match(3,now.minusHours(3),now.minusHours(1));
        lifecycle.advance(m);
        var s=outbox(MatchLifecycle.SETTLEMENT); assertThat(s).hasSize(1);
        assertThat(s.get(0).path("userId").asLong()).isEqualTo(1);
        assertThat(s.get(0).path("result").asText()).isEqualTo("UNDER_MIN_PARTICIPANTS");
    }
    @Test void repeatedRunsAndRepeatedSettlementDoNotDuplicate() {
        long m=match(3,now.minusHours(3),now.minusHours(1),2L);
        lifecycle.advance(m); lifecycle.advance(m);
        assertThat(statusChanges()).filteredOn(n->n.path("to").asText().equals("COMPLETED")).hasSize(1);
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).hasSize(2);
        assertThat(lifecycle.candidates(0)).doesNotContain(m);
        // Even a forced second completion pass cannot insert another request for the same participant.
        jdbc.update("UPDATE matches SET status='IN_PROGRESS' WHERE id=?",m);
        lifecycle.advance(m);
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).hasSize(2);
    }
    @Test void depositDisabledSkipsSettlement() {
        long m=match(3,now.minusHours(3),now.minusHours(1),2L);
        var off=new MatchLifecycle(matches,participants,events,jdbc,mapper,false);
        tx.executeWithoutResult(s->off.advance(m));
        assertThat(state(m)).isEqualTo(MatchStatus.COMPLETED);
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).isEmpty();
    }
    @Test void pendingCreationCancelledAndFutureMatchesAreExcluded() {
        var s=now.plusDays(2);
        long pending=crud.create(1L,new MatchDtos.Create(12L,"Pending","D",s,s.plusHours(1),3,
            SkillLevel.BEGINNER,SportType.TENNIS,GenderGroup.MIXED));
        jdbc.update("UPDATE matches SET start_at=?,end_at=? WHERE id=?",now.minusHours(3),now.minusHours(1),pending);
        long cancelled=match(3,now.plusHours(1),now.plusHours(2),2L);
        crud.cancel(cancelled,1L);
        jdbc.update("UPDATE matches SET start_at=?,end_at=? WHERE id=?",now.minusHours(3),now.minusHours(1),cancelled);
        long future=match(3,now.plusHours(1),now.plusHours(2),2L);
        clearOutbox();
        assertThat(lifecycle.candidates(0)).isEmpty();
        lifecycle.advance(pending); lifecycle.advance(cancelled); lifecycle.advance(future);
        assertThat(state(pending)).isEqualTo(MatchStatus.CREATION_PENDING);
        assertThat(state(cancelled)).isEqualTo(MatchStatus.CANCELLED);
        assertThat(state(future)).isEqualTo(MatchStatus.RECRUITING);
        assertThat(statusChanges()).isEmpty();
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).isEmpty();
    }
    @Test void concurrentRunsTransitionOnce() throws Exception {
        long m=match(3,now.minusHours(3),now.minusHours(1),2L);
        clearOutbox();
        var executor=Executors.newFixedThreadPool(2); var barrier=new CyclicBarrier(2);
        try {
            var a=executor.submit(()->{ barrier.await(); lifecycle.advance(m); return null; });
            var b=executor.submit(()->{ barrier.await(); lifecycle.advance(m); return null; });
            a.get(20,TimeUnit.SECONDS); b.get(20,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(statusChanges()).hasSize(2);
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).hasSize(2);
    }

    @Test void ownerEditsAttendanceDuringMatch() throws Exception {
        long m=match(3,now.minusMinutes(10),now.plusHours(1),2L);
        long p=participant(m,2);
        mvc.perform(patch("/api/matches/"+m+"/participants/"+p+"/attendance").header("X-User-Id","1")
                .contentType("application/json").content("{\"attendance\":\"NO_SHOW\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.participantId").value(p))
            .andExpect(jsonPath("$.userId").value(2)).andExpect(jsonPath("$.attendance").value("NO_SHOW"));
        mvc.perform(get("/api/matches/"+m).header("X-User-Id","1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.participants[1].participantId").value(p))
            .andExpect(jsonPath("$.participants[1].attendance").value("NO_SHOW"))
            .andExpect(jsonPath("$.participants[0].attendance").value("ATTENDED"))
            .andExpect(jsonPath("$.participants[0].role").value("OWNER"));
        for(String viewer:new String[]{null,"2","99"}) {
            var req=get("/api/matches/"+m); if(viewer!=null) req=req.header("X-User-Id",viewer);
            mvc.perform(req).andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[1].participantId").value(p))
                .andExpect(jsonPath("$.participants[1].attendance").doesNotExist())
                .andExpect(jsonPath("$.participants[0].attendance").doesNotExist());
        }
        jdbc.update("UPDATE matches SET end_at=? WHERE id=?",now.minusMinutes(1),m);
        lifecycle.advance(m);
        assertThat(outbox(MatchLifecycle.SETTLEMENT)).filteredOn(n->n.path("userId").asLong()==2)
            .extracting(n->n.path("result").asText()).containsExactly("NO_SHOW");
    }
    @Test void attendanceRejectsNonOwnerWrongTimeAndWrongTarget() throws Exception {
        long m=match(4,now.minusMinutes(10),now.plusHours(1),2L);
        long p=participant(m,2);
        joinPending(m,3L); long pending=participant(m,3);
        long other=match(3,now.minusMinutes(10),now.plusHours(1),4L); long foreign=participant(other,4);
        String body="{\"attendance\":\"NO_SHOW\"}";
        mvc.perform(patch("/api/matches/"+m+"/participants/"+p+"/attendance").header("X-User-Id","2")
                .contentType("application/json").content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(patch("/api/matches/"+m+"/participants/"+p+"/attendance")
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/matches/"+m+"/participants/"+p+"/attendance").header("X-User-Id","1")
                .contentType("application/json").content("{\"attendance\":\"LATE\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        for(long target:new long[]{pending,foreign,999999})
            mvc.perform(patch("/api/matches/"+m+"/participants/"+target+"/attendance").header("X-User-Id","1")
                    .contentType("application/json").content(body))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_FOUND"));
        // before start
        jdbc.update("UPDATE matches SET start_at=?,end_at=? WHERE id=?",now.plusMinutes(10),now.plusHours(1),m);
        mvc.perform(patch("/api/matches/"+m+"/participants/"+p+"/attendance").header("X-User-Id","1")
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_EDITABLE"));
        // after end, even while the scheduler has not completed it yet
        jdbc.update("UPDATE matches SET start_at=?,end_at=?,status='IN_PROGRESS' WHERE id=?",now.minusHours(2),now.minusMinutes(1),m);
        mvc.perform(patch("/api/matches/"+m+"/participants/"+p+"/attendance").header("X-User-Id","1")
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_EDITABLE"));
        // completed or cancelled inside the time window
        for(String state:new String[]{"COMPLETED","CANCELLED"}) {
            jdbc.update("UPDATE matches SET start_at=?,end_at=?,status=?,cancelled_at=? WHERE id=?",
                now.minusMinutes(10),now.plusHours(1),state,now,m);
            mvc.perform(patch("/api/matches/"+m+"/participants/"+p+"/attendance").header("X-User-Id","1")
                    .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_EDITABLE"));
        }
        assertThat(participants.findById(p).orElseThrow().getMatchParticipantStatus()).isEqualTo("ATTENDED");
    }
}
