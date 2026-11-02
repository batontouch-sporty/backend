package com.example.sporty.match.deposit;

import com.example.sporty.match.crud.*;
import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.participation.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.waitlist.*;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.cloud.config.enabled=false","spring.config.import=",
        "eureka.client.enabled=false","spring.datasource.url=jdbc:h2:mem:deposit;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop","match.outbox.enabled=false","match.withdrawal.enabled=false",
        "match.deposit.enabled=true","spring.kafka.listener.auto-startup=false","match.deposit.maintenance-delay-ms=86400000"})
@AutoConfigureMockMvc
class DepositSagaTest {
    @org.springframework.test.context.DynamicPropertySource
    static void maria(org.springframework.test.context.DynamicPropertyRegistry properties) {
        String url=System.getenv("DEPOSIT_TEST_JDBC");
        if(url==null) return;
        if(!url.equals("jdbc:mariadb://127.0.0.1:33485/match_db"))
            throw new IllegalArgumentException("Only the dedicated disposable deposit DB is allowed");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.driver-class-name",()->"org.mariadb.jdbc.Driver");
        properties.add("spring.datasource.username",()->"match_app");
        properties.add("spring.datasource.password",()->System.getenv("DEPOSIT_TEST_PASSWORD"));
        properties.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
    @Autowired DepositSaga saga;
    @Autowired DepositStore store;
    @Autowired DepositPromotion promotion;
    @Autowired MatchCrudService crud;
    @Autowired ParticipationService participation;
    @Autowired WaitlistService waiting;
    @Autowired MatchRepository matches;
    @Autowired MatchParticipantRepository participants;
    @Autowired MatchWaitlistRepository waitlist;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @MockitoBean MatchRemoteChecks checks;
    @MockitoBean(name="participationMemberLocks") MemberLocks locks;
    TransactionTemplate tx;
    LocalDateTime start;

    @BeforeEach void setup() {
        tx=new TransactionTemplate(manager);
        if(System.getenv("DEPOSIT_TEST_JDBC")!=null) {
            var actualLocks=new MemberLocks(jdbc.getDataSource());
            doAnswer(i->{ actualLocks.lock(i.getArgument(0)); return null; }).when(locks).lock(anyLong());
            doAnswer(i->{ actualLocks.lockImmediately(i.getArgument(0)); return null; }).when(locks).lockImmediately(anyLong());
        }
        if(System.getenv("DEPOSIT_TEST_JDBC")==null) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS outbox_event(event_id VARCHAR(64) PRIMARY KEY,aggregate_id VARCHAR(64),event_type VARCHAR(80),payload CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMP)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS processed_events(event_id VARCHAR(64) PRIMARY KEY,event_type VARCHAR(80),processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_events");
        waitlist.deleteAll(); participants.deleteAll(); matches.deleteAll();
        start=LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(2);
        when(checks.checkCreation(anyLong(),anyLong(),any(),any())).thenReturn(new MatchRemoteChecks.CreationContext(
            new MemberClient.Member(1L,"MALE","ACTIVE",null,List.of()),
            new MatchRemoteChecks.FacilitySnapshot(12L,"Court","Location","송파구",true,"https://example.org")));
        when(checks.activeMember(anyLong())).thenAnswer(i->new MemberClient.Member(i.getArgument(0),"MALE","ACTIVE",null,List.of()));
        when(checks.profiles(anyList())).thenReturn(Map.of());
    }
    MatchDtos.Create request(int capacity) { return new MatchDtos.Create(12L,"Deposit match","Description",start,start.plusHours(1),capacity,SkillLevel.BEGINNER,SportType.TENNIS,GenderGroup.MIXED); }
    long create(int capacity) { return crud.create(1L,request(capacity)); }
    String id(long match,long user) { return participants.findByMatch_IdAndUserId(match,user).orElseThrow().getRequestId(); }
    void result(String topic,String id,Map<String,Object> extra) {
        var r=tx.execute(s->store.get(id)); var event=new HashMap<String,Object>(extra);
        event.put("eventId",UUID.randomUUID().toString()); event.put("requestId",id);
        event.put("userId",r.userId); event.put("matchId",r.matchId); event.put("occurredAt",OffsetDateTime.now().toString());
        saga.handle(topic,mapper.valueToTree(event));
    }
    void charged(String id) { result("user.deposit-charged.v1",id,Map.of()); }
    long ready(int capacity) { long m=create(capacity); charged(id(m,1)); return m; }
    long outbox(String topic) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=?",Long.class,topic); }
    void timeout(String id) {
        tx.executeWithoutResult(s->{ var r=store.locked(id); r.deadlineAt=OffsetDateTime.now().minusMinutes(1); store.save(r); });
        saga.expire(id);
    }

    @Test void createReturns202AndHidesPendingMatchThenConfirmsOwner() throws Exception {
        var response=mvc.perform(post("/api/matches").header("X-User-Id","1").contentType("application/json")
            .content(mapper.writeValueAsString(request(2)))).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        var body=mapper.readTree(response.getResponse().getContentAsString()); long m=body.path("matchId").asLong();
        String id=body.path("requestId").asText();
        assertThat(matches.findById(m).orElseThrow().getCreationRequestId()).isEqualTo(id);
        assertThat(participants.countByMatch_Id(m)).isZero();
        mvc.perform(get("/api/matches/"+m)).andExpect(status().isNotFound());
        assertThat(crud.mine(1L)).isEmpty();
        charged(id);
        assertThat(saga.status(id,1).status()).isEqualTo("CONFIRMED");
        assertThat(participants.countByMatch_Id(m)).isOne();
        mvc.perform(get("/api/matches/"+m)).andExpect(status().isOk());
    }
    @Test void joinReturns202AndOnlyEmitsJoinedAfterSuccessfulCharge() throws Exception {
        long m=ready(2);
        mvc.perform(post("/api/matches/"+m+"/participants").header("X-User-Id","2"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(outbox("match.joined.v1")).isZero(); assertThat(participants.countByMatch_Id(m)).isOne();
        String request=id(m,2); charged(request); charged(request);
        assertThat(outbox("match.joined.v1")).isOne();
        assertThat(matches.findById(m).orElseThrow().getStatus()).isEqualTo(MatchStatus.CLOSED);
    }
    @Test void chargeRejectionFailsWithoutRefundAndCreationStaysHidden() {
        long m=create(2); String id=id(m,1);
        result("user.deposit-charge-rejected.v1",id,Map.of("reason","INSUFFICIENT_POINTS"));
        assertThat(saga.status(id,1).status()).isEqualTo("FAILED");
        assertThat(matches.findById(m).orElseThrow().getStatus()).isEqualTo(MatchStatus.CREATION_FAILED);
        assertThat(outbox(DepositStore.REFUND)).isZero();
    }
    @Test void twoPendingRequestsCompeteForLastSlotWithoutOverbooking() {
        long m=ready(2); participation.join(m,2L); participation.join(m,3L);
        charged(id(m,2)); charged(id(m,3));
        assertThat(participants.countByMatch_Id(m)).isEqualTo(2);
        assertThat(saga.status(id(m,3),3).status()).isEqualTo("CANCELLED");
        assertThat(outbox(DepositStore.REFUND)).isOne();
    }
    @Test void concurrentChargeResultsCannotBothTakeLastSlot() throws Exception {
        long m=ready(2); participation.join(m,2L); participation.join(m,3L);
        String first=id(m,2),second=id(m,3);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var a=executor.submit(()->{ try { barrier.await(); charged(first); } catch(Exception e) { throw new RuntimeException(e); } });
            var b=executor.submit(()->{ try { barrier.await(); charged(second); } catch(Exception e) { throw new RuntimeException(e); } });
            a.get(20,java.util.concurrent.TimeUnit.SECONDS); b.get(20,java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(participants.countByMatch_Id(m)).isEqualTo(2);
        assertThat(outbox(DepositStore.REFUND)).isOne();
    }
    @Test void timeoutCancelsBeforeLateSuccessAndPreservesSingleRefund() {
        long m=ready(3); participation.join(m,2L); String id=id(m,2);
        timeout(id); charged(id); charged(id);
        assertThat(saga.status(id,2).status()).isEqualTo("CANCELLED");
        assertThat(outbox(DepositStore.REFUND)).isOne();
        assertThat(participants.countByMatch_Id(m)).isOne();
    }
    @Test void cancellationBeforeChargeRefundsOnceAndLateSuccessCannotReconfirm() {
        long m=ready(3); participation.join(m,2L); String id=id(m,2);
        participation.leave(m,2L);
        result("user.deposit-refund-completed.v1",id,Map.of("outcome","CANCELLED_BEFORE_CHARGE","creditedPoints",0));
        charged(id);
        assertThat(saga.status(id,2).refundStatus()).isEqualTo("COMPLETED");
        assertThat(saga.status(id,2).status()).isEqualTo("CANCELLED");
        assertThat(outbox(DepositStore.REFUND)).isOne();
    }
    @Test void rejoinReusesRowButOldSuccessOnlyRefundsOldRequest() {
        long m=ready(3); participation.join(m,2L); var old=participants.findByMatch_IdAndUserId(m,2L).orElseThrow();
        String first=old.getRequestId(); participation.leave(m,2L); participation.join(m,2L);
        var current=participants.findByMatch_IdAndUserId(m,2L).orElseThrow();
        assertThat(current.getId()).isEqualTo(old.getId()); assertThat(current.getRequestId()).isNotEqualTo(first);
        charged(first);
        assertThat(saga.status(current.getRequestId(),2).status()).isEqualTo("PENDING");
        charged(current.getRequestId());
        assertThat(saga.status(first,2).status()).isEqualTo("CANCELLED");
        assertThat(participants.countByMatch_Id(m)).isEqualTo(2);
    }
    @Test void matchCancellationRefundsEveryOriginalRequestIncludingPending() {
        long m=ready(4); participation.join(m,2L); charged(id(m,2)); participation.join(m,3L);
        crud.cancel(m,1L);
        assertThat(outbox(DepositStore.REFUND)).isEqualTo(3);
        charged(id(m,3));
        assertThat(outbox(DepositStore.REFUND)).isEqualTo(3);
        assertThat(outbox("match.joined.v1")).isOne();
    }
    @Test void duplicateEventIdProducesNoDuplicateMutation() {
        long m=ready(3); participation.join(m,2L); String id=id(m,2);
        var event=mapper.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("requestId",id)
            .put("userId",2).put("matchId",m).put("occurredAt",OffsetDateTime.now().toString());
        saga.handle("user.deposit-charged.v1",event); saga.handle("user.deposit-charged.v1",event);
        assertThat(outbox("match.joined.v1")).isOne();
    }
    @Test void promotionChargesOnlyAtActualPromotionAndNotificationWaitsForConfirmation() {
        long m=ready(2); participation.join(m,2L); charged(id(m,2));
        waiting.register(m,3L); assertThat(outbox(DepositStore.CHARGE)).isEqualTo(2);
        participation.leave(m,2L);
        String promoted=id(m,3); assertThat(saga.status(promoted,3).requestType()).isEqualTo("PROMOTION");
        assertThat(outbox("match.waitlist-promoted.v1")).isZero();
        charged(promoted); assertThat(outbox("match.waitlist-promoted.v1")).isOne();
    }
    @Test void failedPromotionLetsNextCandidateTryOnMaintenanceRetry() {
        long m=ready(2); participation.join(m,2L); charged(id(m,2)); waiting.register(m,3L); waiting.register(m,4L);
        participation.leave(m,2L);
        result("user.deposit-charge-rejected.v1",id(m,3),Map.of("reason","INSUFFICIENT_POINTS"));
        promotion.promote(m);
        assertThat(saga.status(id(m,4),4).status()).isEqualTo("PENDING");
    }
    @Test void promotionMemberFailureDoesNotFailCancellationAndKeepsCandidateForRetry() {
        long m=ready(2); participation.join(m,2L); charged(id(m,2)); waiting.register(m,3L);
        when(checks.activeMember(3L)).thenThrow(new MatchDependencyException(503,"USER_SERVICE_UNAVAILABLE","unavailable"));
        participation.leave(m,2L);
        assertThat(saga.status(id(m,2),2).status()).isEqualTo("CANCELLED");
        assertThat(waitlist.existsByMatch_IdAndUserId(m,3L)).isTrue();
        assertThat(outbox(DepositStore.REFUND)).isOne();
    }
    @Test void suspensionEventClearsWaitingButKeepsConfirmedMatches() {
        long m=ready(2); participation.join(m,2L); charged(id(m,2)); waiting.register(m,3L);
        var event=mapper.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("requestId",UUID.randomUUID().toString())
            .put("userId",3).put("matchId",m).put("occurredAt",OffsetDateTime.now().toString())
            .put("suspendedUntil",OffsetDateTime.now().plusDays(7).toString());
        saga.handle("user.participation-suspended.v1",event);
        assertThat(waitlist.findAllByUserId(3L)).isEmpty(); assertThat(participants.countByMatch_Id(m)).isEqualTo(2);
    }
    @Test void requestStatusIsPrivateAndUnauthenticatedRequestsAreRejected() throws Exception {
        long m=create(2); String id=id(m,1);
        mvc.perform(get("/api/matches/requests/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/matches/requests/"+id).header("X-User-Id","2")).andExpect(status().isNotFound());
        mvc.perform(get("/api/matches/requests/"+id).header("X-User-Id","1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.requestType").value("CREATE"));
    }
    @Test void pendingDuplicateIs409AndLeaveAfterStartIsBlocked() throws Exception {
        long m=ready(3); participation.join(m,2L);
        mvc.perform(post("/api/matches/"+m+"/participants").header("X-User-Id","2"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARTICIPATION_IN_PROGRESS"));
        charged(id(m,2)); jdbc.update("UPDATE matches SET start_at=? WHERE id=?",start.minusDays(3),m);
        mvc.perform(delete("/api/matches/"+m+"/participants/me").header("X-User-Id","2"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MATCH_ALREADY_STARTED"));
    }
    void withdraw(long user) {
        var store=new com.example.sporty.match.withdrawal.WithdrawalStore(jdbc,mapper); store.setDeposits(saga);
        var processor=new com.example.sporty.match.withdrawal.WithdrawalProcessor(store,locks,manager,Clock.systemUTC());
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        processor.handle(new com.example.sporty.match.withdrawal.WithdrawalRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),user,now,now.plusMinutes(5)));
    }
    @Test void withdrawalCancelsPendingRequestWithoutRefundOrLateConfirmation() {
        long m=ready(3); participation.join(m,2L); String id=id(m,2);
        withdraw(2); charged(id);
        assertThat(saga.status(id,2).status()).isEqualTo("CANCELLED");
        assertThat(outbox(DepositStore.REFUND)).isZero();
        assertThat(participants.findByMatch_IdAndUserId(m,2L)).isPresent();
        assertThat(outbox("match.withdrawal-cleaned.v1")).isOne();
    }
    @Test void ownerWithdrawalRefundsOtherParticipantsButNotWithdrawingOwner() {
        long m=ready(3); participation.join(m,2L); charged(id(m,2));
        withdraw(1);
        assertThat(matches.findById(m).orElseThrow().getStatus()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(outbox(DepositStore.REFUND)).isOne();
        assertThat(saga.status(id(m,1),1).refundStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(saga.status(id(m,2),2).refundStatus()).isEqualTo("PENDING");
    }
    @Test void concurrentWithdrawalAndChargeCannotLeaveConfirmedParticipation() throws Exception {
        long m=ready(3); participation.join(m,2L); String request=id(m,2);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var a=executor.submit(()->{ try { barrier.await(); charged(request); } catch(Exception e) { throw new RuntimeException(e); } });
            var b=executor.submit(()->{ try { barrier.await(); withdraw(2); } catch(Exception e) { throw new RuntimeException(e); } });
            a.get(20,java.util.concurrent.TimeUnit.SECONDS); b.get(20,java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(saga.status(request,2).status()).isEqualTo("CANCELLED");
        assertThat(participants.countByMatch_Id(m)).isOne();
        assertThat(outbox(DepositStore.REFUND)).isZero();
    }
}
