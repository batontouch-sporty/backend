package com.example.sporty.match.deposit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Durable request snapshots reuse the existing Outbox; internal rows are never sent to Kafka.
 * Keep these rows and published business events for late results and old-request refunds.
 * Caller always holds the member lock and the match row lock before changing a snapshot.
 */
@Component
@Transactional(propagation=Propagation.MANDATORY)
public class DepositStore {
    public static final String STATE = "match.deposit-request.state";
    public static final String CHARGE = "match.deposit-charge-requested.v1";
    public static final String REFUND = "match.deposit-refund-requested.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public DepositStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public static class Request {
        public String requestId, requestType, status="PENDING", refundStatus="NOT_REQUIRED";
        public String failureCode, failureMessage;
        /** Member gender checked at JOIN/PROMOTE acceptance; null for CREATE and older rows. */
        public String gender;
        public long userId, matchId;
        public OffsetDateTime deadlineAt;
        public boolean chargeAccepted, refundRequested;
        public Request() {}
    }
    public Request get(String id) {
        return read(id,false);
    }
    public Request locked(String id) { return read(id,true); }
    private Request read(String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_id=? AND event_type=?"+(lock?" FOR UPDATE":""),String.class,id,STATE);
        if(rows.isEmpty()) return null;
        try { return mapper.readValue(rows.get(0),Request.class); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored deposit request",e); }
    }
    public void create(Request r) {
        jdbc.update("INSERT INTO outbox_event(event_id,aggregate_id,event_type,payload,published_at) VALUES (?,?,?,?,NULL)",
                r.requestId,Long.toString(r.userId),STATE,json(r));
        var fields=base(r); fields.put("requestType",r.requestType);
        emit(CHARGE,r,fields);
    }
    public void save(Request r) {
        if(jdbc.update("UPDATE outbox_event SET payload=?,published_at=? WHERE event_id=? AND event_type=?",
                json(r),"PENDING".equals(r.status)?null:java.sql.Timestamp.from(Instant.now()),r.requestId,STATE)!=1)
            throw new IllegalStateException("Missing deposit request");
    }
    public void refund(Request r,String reason) {
        if(r.refundRequested) return;
        r.refundRequested=true; r.refundStatus="PENDING";
        var fields=base(r); fields.put("reason",reason); emit(REFUND,r,fields);
    }
    private Map<String,Object> base(Request r) {
        var m=new LinkedHashMap<String,Object>(); m.put("eventId",UUID.randomUUID().toString());
        m.put("requestId",r.requestId); m.put("userId",r.userId); m.put("matchId",r.matchId);
        m.put("occurredAt",OffsetDateTime.now(ZoneOffset.UTC).toString()); return m;
    }
    private void emit(String topic,Request r,Map<String,Object> fields) {
        jdbc.update("INSERT INTO outbox_event(event_id,aggregate_id,event_type,payload) VALUES (?,?,?,?)",
            fields.get("eventId"),Long.toString(r.userId),topic,json(fields));
    }
    public boolean processed(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?",Long.class,id)>0; }
    public void mark(String id,String topic) { jdbc.update("INSERT INTO processed_events(event_id,event_type) VALUES (?,?)",id,topic); }
    public List<Request> pending() {
        return jdbc.queryForList("SELECT event_id FROM outbox_event WHERE published_at IS NULL AND event_type=? ORDER BY created_at,event_id",String.class,STATE)
            .stream().map(this::get).filter(r->"PENDING".equals(r.status)).toList();
    }
    private String json(Object o) { try { return mapper.writeValueAsString(o); } catch(Exception e) { throw new IllegalStateException(e); } }
}
