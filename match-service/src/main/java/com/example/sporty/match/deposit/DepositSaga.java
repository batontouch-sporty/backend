package com.example.sporty.match.deposit;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.participation.*;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class DepositSaga {
    private final DepositStore store;
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final MatchWaitlistRepository waitlist;
    private final MatchRemoteChecks checks;
    private final ParticipationEvents events;
    private final MemberLocks locks;
    private final Duration timeout;
    private final boolean enabled;
    public boolean isEnabled() { return enabled; }

    public DepositSaga(DepositStore store, MatchRepository matches,
            MatchParticipantRepository participants, MatchWaitlistRepository waitlist,
            MatchRemoteChecks checks, ParticipationEvents events,
            @Qualifier("participationMemberLocks") MemberLocks locks,
            @Value("${match.deposit.timeout:5m}") Duration timeout,
            @Value("${match.deposit.enabled:true}") boolean enabled) {
        this.store=store; this.matches=matches; this.participants=participants; this.waitlist=waitlist;
        this.checks=checks; this.events=events; this.locks=locks; this.timeout=timeout; this.enabled=enabled;
        if(timeout.isNegative()||timeout.isZero()) throw new IllegalArgumentException("Deposit timeout must be positive");
    }

    public record Accepted(String requestId,long matchId,String status) {}
    public record Status(String requestId,long matchId,String requestType,String status,
                         String refundStatus,String failureCode,String failureMessage) {}

    /** Caller holds member then match lock; pending requests do not reserve capacity. */
    @Transactional
    public Accepted begin(MatchParticipantEntity p,String type) {
        String requestId="CREATE".equals(type)?p.getMatch().getCreationRequestId():UUID.randomUUID().toString();
        p.pending(requestId);
        participants.saveAndFlush(p);
        var r=new DepositStore.Request(); r.requestId=requestId; r.userId=p.getUserId();
        r.matchId=p.getMatch().getId(); r.requestType=type;
        r.deadlineAt=OffsetDateTime.now(ZoneOffset.UTC).plus(timeout);
        store.create(r);
        return new Accepted(requestId,r.matchId,"PENDING");
    }

    @Transactional(readOnly=true)
    public Status status(String requestId,long userId) {
        var r=store.get(requestId);
        if(r==null || r.userId!=userId) throw new ParticipationProblem(404,"REQUEST_NOT_FOUND","요청을 찾을 수 없습니다.");
        return new Status(r.requestId,r.matchId,"PROMOTE".equals(r.requestType)?"PROMOTION":r.requestType,
                r.status,r.refundStatus,r.failureCode,r.failureMessage);
    }

    @Transactional
    public Accepted acceptedForCreation(long matchId,long userId) {
        var p=participants.findByMatch_IdAndUserId(matchId,userId).orElseThrow();
        return new Accepted(p.getRequestId(),matchId,"PENDING");
    }

    /** A15/A13/A17 call inside their match transaction. Snapshot survives participant-row reuse. */
    @Transactional
    public void cancel(MatchParticipantEntity p,String reason) {
        var r=store.locked(p.getRequestId());
        if(r==null) {
            // R10 creates every deposit through begin(); old migrated fixtures have no deposit.
            p.cancelParticipation(); return;
        }
        if(!Set.of("PENDING","CONFIRMED").contains(r.status)) return;
        r.status="CANCELLED";
        r.failureCode="CONFIRMATION_FAILED".equals(reason)?"CONFIRMATION_FAILED":null;
        store.refund(r,reason); store.save(r); p.cancelParticipation();
        if("CREATE".equals(r.requestType)) p.getMatch().failCreation(now());
    }

    @Transactional
    public void cancelMatch(MatchEntity m) {
        for(var p:participants.findAllByMatch_Id(m.getId())) cancel(p,"MATCH_CANCELLED");
    }

    /** Member-withdrawal does not refund that member's deposit. Other users of an owner's
     * cancelled match do receive MATCH_CANCELLED refunds. Pending requests cannot confirm later. */
    @Transactional
    public void withdrawal(long matchId,long userId,boolean owner) {
        for(var p:participants.findAllByMatch_Id(matchId)) {
            if(p.getUserId()!=userId && !owner) continue;
            var r=store.locked(p.getRequestId());
            if(p.getUserId()==userId) {
                if(r!=null && Set.of("PENDING","CONFIRMED").contains(r.status)) {
                    r.status="CANCELLED"; r.failureCode="USER_WITHDRAWN"; store.save(r);
                }
                p.cancelParticipation();
            } else cancel(p,"MATCH_CANCELLED");
        }
        participants.flush();
    }

    @Transactional
    public void handle(String topic,JsonNode event) {
        String eventId=text(event,"eventId"), requestId=text(event,"requestId");
        UUID.fromString(eventId); UUID.fromString(requestId);
        long userId=positive(event,"userId"), matchId=positive(event,"matchId");
        OffsetDateTime.parse(text(event,"occurredAt"));
        locks.lock(userId);
        if(store.processed(eventId)) return;
        if("user.participation-suspended.v1".equals(topic)) {
            OffsetDateTime.parse(text(event,"suspendedUntil"));
            // Suspension leaves already confirmed matches intact.
            waitlist.deleteAll(waitlist.findAllByUserId(userId)); waitlist.flush();
            store.mark(eventId,topic); return;
        }
        var initial=store.get(requestId);
        if(initial==null || initial.userId!=userId || initial.matchId!=matchId)
            throw new IllegalArgumentException("Unknown or mismatched deposit request");
        // Same member -> match ordering as create/join/withdrawal. Remote checks precede row lock.
        String refusal=null;
        if("user.deposit-charged.v1".equals(topic) && "PENDING".equals(initial.status)) {
            try { checks.activeMember(userId); }
            catch(MatchDependencyException e) {
                if(e.status()>=500) throw e;
                refusal=e.code();
            }
        }
        var match=matches.findByIdForUpdate(matchId).orElseThrow();
        var r=store.locked(requestId);
        var p=participants.findRequestForUpdate(matchId,userId).orElseThrow();
        boolean current=requestId.equals(p.getRequestId());
        switch(topic) {
            case "user.deposit-charged.v1" -> {
                r.chargeAccepted=true;
                if("CONFIRMED".equals(r.status)) break;
                boolean creation="CREATE".equals(r.requestType);
                boolean allowed="PENDING".equals(r.status) && current && refusal==null
                        && OffsetDateTime.now(ZoneOffset.UTC).isBefore(r.deadlineAt)
                        && match.getStartAt().isAfter(now())
                        && (creation ? match.getStatus()==MatchStatus.CREATION_PENDING
                            : match.getStatus()==MatchStatus.RECRUITING || match.getStatus()==MatchStatus.CLOSED)
                        && participants.findConfirmedForUpdate(matchId).size()<match.getMaxParticipant();
                if(allowed) {
                    p.confirm(); participants.flush(); r.status="CONFIRMED";
                    if(creation) match.changeStatus(MatchStatus.RECRUITING,now());
                    else if("PROMOTE".equals(r.requestType)) events.promoted(match,userId);
                    else events.joined(match,userId,participants.findConfirmedForUpdate(matchId).size());
                    if(participants.findConfirmedForUpdate(matchId).size()>=match.getMaxParticipant()
                            && match.getStatus()==MatchStatus.RECRUITING) {
                        match.closeRecruitment(now()); events.statusChanged(match,MatchStatus.RECRUITING,"정원 도달로 모집 마감");
                    }
                } else {
                    r.status="CANCELLED";
                    if(r.failureCode==null) {
                        if(refusal!=null) r.failureCode=refusal;
                        else if(!match.getStartAt().isAfter(now())) r.failureCode="MATCH_ALREADY_STARTED";
                        else if(participants.findConfirmedForUpdate(matchId).size()>=match.getMaxParticipant()) r.failureCode="MATCH_FULL";
                        else r.failureCode="CONFIRMATION_FAILED";
                    }
                    r.failureMessage="참가를 확정할 수 없어 보증금 반환을 요청했습니다.";
                    // Withdrawal itself does not refund points (v4.0 section 8).
                    if(!"USER_WITHDRAWN".equals(r.failureCode)) store.refund(r,"CONFIRMATION_FAILED");
                    if(current) { p.cancelParticipation(); if(creation) match.failCreation(now()); }
                }
            }
            case "user.deposit-charge-rejected.v1" -> {
                String reason=text(event,"reason");
                if(!Set.of("INSUFFICIENT_POINTS","PARTICIPATION_SUSPENDED","REQUEST_CANCELLED","USER_NOT_ACTIVE").contains(reason))
                    throw new IllegalArgumentException("Unknown charge rejection reason");
                if("PENDING".equals(r.status)) {
                    r.status="FAILED"; r.failureCode=reason; r.failureMessage="보증금 차감이 거절되었습니다.";
                    if(current) { p.fail(); if("CREATE".equals(r.requestType)) match.failCreation(now()); }
                }
            }
            case "user.deposit-refund-completed.v1" -> {
                String outcome=text(event,"outcome");
                long credited=event.path("creditedPoints").asLong(-1);
                if(!event.path("creditedPoints").isIntegralNumber() || !r.refundRequested || !Set.of("RETURNED","CANCELLED_BEFORE_CHARGE").contains(outcome)
                        || credited<0 || credited>10 || ("CANCELLED_BEFORE_CHARGE".equals(outcome)&&credited!=0))
                    throw new IllegalArgumentException("Invalid refund result");
                r.refundStatus="COMPLETED";
            }
            default -> throw new IllegalArgumentException("Unknown deposit topic");
        }
        store.save(r); participants.flush(); matches.flush(); store.mark(eventId,topic);
    }

    @Transactional
    public void expire(String requestId) {
        var initial=store.get(requestId); if(initial==null) return;
        locks.lock(initial.userId);
        var match=matches.findByIdForUpdate(initial.matchId).orElseThrow();
        var r=store.locked(requestId);
        if(!"PENDING".equals(r.status) || OffsetDateTime.now(ZoneOffset.UTC).isBefore(r.deadlineAt)) return;
        r.status="CANCELLED"; r.failureCode="REQUEST_TIMED_OUT"; r.failureMessage="보증금 처리 시간이 초과되었습니다.";
        // Publish a cancellation decision even before charging; user records DEPOSIT_CANCELLED.
        store.refund(r,"CONFIRMATION_FAILED"); store.save(r);
        participants.findRequestForUpdate(r.matchId,r.userId).filter(p->requestId.equals(p.getRequestId()))
            .ifPresent(MatchParticipantEntity::cancelParticipation);
        if("CREATE".equals(r.requestType)) match.failCreation(now());
    }
    private static String text(JsonNode n,String key) {
        var v=n.path(key); if(!v.isTextual()||v.asText().isBlank()) throw new IllegalArgumentException("Missing "+key);
        return v.asText();
    }
    private static long positive(JsonNode n,String key) {
        var v=n.path(key); if(!v.isIntegralNumber()||v.asLong()<=0) throw new IllegalArgumentException("Invalid "+key);
        return v.asLong();
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
}
