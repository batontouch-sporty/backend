package com.example.sporty.match.withdrawal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WithdrawalProcessorTest {
    JdbcTemplate jdbc;WithdrawalStore store;WithdrawalProcessor processor;ObjectMapper mapper;
    final AtomicLong ids=new AtomicLong();MutableClock clock;
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-31T10:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
    }
    @BeforeEach void setup(){
        String mariaUrl=System.getenv("SAGA_TEST_JDBC");boolean maria=mariaUrl!=null;
        if(maria && !mariaUrl.endsWith("/match_saga_r7_review"))throw new IllegalArgumentException("Only the dedicated disposable Saga review DB is allowed");
        var ds=new DriverManagerDataSource(maria?mariaUrl:"jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",maria?"root":"sa",maria?System.getenv("SAGA_TEST_DB_PASSWORD"):"");
        ds.setDriverClassName(maria?"org.mariadb.jdbc.Driver":"org.h2.Driver");
        if(maria){var cleanup=new JdbcTemplate(ds);cleanup.execute("DROP TABLE IF EXISTS match_participants");cleanup.execute("DROP TABLE IF EXISTS matches");cleanup.execute("DROP TABLE IF EXISTS processed_events");cleanup.execute("DROP TABLE IF EXISTS outbox_event");}
        new ResourceDatabasePopulator(new ClassPathResource(maria?"withdrawal-r7-mariadb.sql":"withdrawal-r7.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);mapper=new ObjectMapper().registerModule(new JavaTimeModule());clock=new MutableClock();
        store=spy(new WithdrawalStore(jdbc,mapper));processor=new WithdrawalProcessor(store,new MemberLocks(ds),new DataSourceTransactionManager(ds),clock);
    }
    long match(long owner,String status,boolean running){
        long id=ids.incrementAndGet();var start=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Seoul")).plusHours(running?-1:24);
        jdbc.update("INSERT INTO matches(id,title,start_at,end_at,max_participant,status,skill_level,sport_type,gender_group,facility_id,facility_name,location_name,region) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,"R7 match",start,start.plusHours(2),2,status,"BEGINNER","BASKETBALL","MIXED",12L,"Court","Location","송파구");participant(id,owner,"OWNER");return id;
    }
    void participant(long match,long user,String role){jdbc.update("INSERT INTO match_participants(match_id,user_id,role) VALUES (?,?,?)",match,user,role);}
    WithdrawalRequest request(long user){var now=clock.instant().atOffset(ZoneOffset.ofHours(9));return new WithdrawalRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),user,now,now.plusMinutes(5));}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    JsonNode result()throws Exception{return mapper.readTree(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type LIKE 'match.withdrawal-%'",String.class));}
    @Test void cleanupUsesOnlyR7TablesAndIncludesOwnerAndCorrelation()throws Exception{
        long owned=match(2,"RECRUITING",false),joined=match(1,"CLOSED",false);participant(joined,2,"PARTICIPANT");var r=request(2);processor.handle(r);
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,owned)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,joined)).isEqualTo("RECRUITING");
        assertThat(count("processed_events")).isEqualTo(1);assertThat(count("outbox_event")).isEqualTo(3);
        var result=result();assertThat(result.path("requestEventId").asText()).isEqualTo(r.eventId());assertThat(result.path("sagaId").asText()).isEqualTo(r.sagaId());
        assertThat(OffsetDateTime.parse(result.path("cleanedAt").asText())).isNotNull();assertThat(result.has("claimToken")).isFalse();
        for(var json:jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type='match.status-changed.v1'",String.class))assertThat(mapper.readTree(json).path("ownerId").asLong()).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=SCHEMA()",Long.class)).isEqualTo(4);
    }
    @Test void runningOwnerRejectsBeforeAnyMutation()throws Exception{
        long future=match(2,"RECRUITING",false),running=match(2,"IN_PROGRESS",true);processor.handle(request(2));
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,future)).isEqualTo("RECRUITING");
        assertThat(result().path("reason").asText()).isEqualTo("OWNER_OF_IN_PROGRESS_MATCH");assertThat(result().path("blockingMatchIds").get(0).asLong()).isEqualTo(running);
        assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void expiredRequestRejectsWithoutCleanup()throws Exception{
        long id=match(2,"RECRUITING",false);var r=request(2);clock.now=clock.now.plusSeconds(301);processor.handle(r);
        assertThat(result().path("reason").asText()).isEqualTo("DEADLINE_EXCEEDED");assertThat(result().path("blockingMatchIds").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,id)).isEqualTo("RECRUITING");
    }
    @Test void commitDeadlineRollsBackChangesAndIntermediateOutbox()throws Exception{
        long id=match(2,"RECRUITING",false);var r=request(2);
        doAnswer(inv->{var answer=inv.callRealMethod();clock.now=clock.now.plusSeconds(301);return answer;}).when(store).emit(eq("match.withdrawal-cleaned.v1"),anyLong(),anyMap(),any());
        processor.handle(r);
        assertThat(count("outbox_event")).isEqualTo(1);assertThat(count("processed_events")).isEqualTo(1);assertThat(result().path("reason").asText()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,id)).isEqualTo("RECRUITING");
    }
    @Test void concurrentDuplicateRequestHasOneResult()throws Exception{
        match(2,"RECRUITING",false);var r=request(2);var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try{List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(pool.submit(()->{try{start.await();processor.handle(r);}catch(InterruptedException ex){throw new RuntimeException(ex);}}));start.countDown();for(var job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(count("processed_events")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='match.withdrawal-cleaned.v1'",Long.class)).isEqualTo(1);
    }
    @Test void offsetAndRequestValidation(){
        var r=request(2);var utcDeadline=r.deadlineAt().withOffsetSameInstant(ZoneOffset.UTC);
        assertThatCode(()->new WithdrawalRequest(r.eventId(),r.sagaId(),2L,r.requestedAt(),utcDeadline).validate()).doesNotThrowAnyException();
        assertThatThrownBy(()->processor.handle(new WithdrawalRequest(r.eventId(),r.sagaId(),2L,r.requestedAt(),utcDeadline.plusSeconds(1)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("processed_events")).isZero();
        assertThatThrownBy(()->mapper.readValue("{\"requestedAt\":\"2026-10-31T10:00:00\"}",WithdrawalRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void completedAndCanceledHistoryRemains(){
        long a=match(2,"COMPLETED",false),b=match(1,"RECRUITING",false);jdbc.update("UPDATE matches SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=?",b);participant(b,2,"PARTICIPANT");processor.handle(request(2));assertThat(count("match_participants")).isEqualTo(3);
    }
    @Test void repeatedSagaIdDoesNotCleanupNewMembership()throws Exception{
        var r=request(2);processor.handle(r);long next=match(1,"RECRUITING",false);participant(next,2,"PARTICIPANT");
        processor.handle(new WithdrawalRequest(UUID.randomUUID().toString(),r.sagaId(),2L,r.requestedAt(),r.deadlineAt()));
        assertThat(count("outbox_event")).isEqualTo(1);assertThat(count("match_participants")).isEqualTo(2);assertThat(count("processed_events")).isEqualTo(2);
    }
    @Test void transactionFailureLeavesNothing(){
        long id=match(2,"RECRUITING",false);doThrow(new IllegalStateException("DB failure")).when(store).mark(anyString());
        assertThatThrownBy(()->processor.handle(request(2))).isInstanceOf(IllegalStateException.class);
        assertThat(count("outbox_event")).isZero();assertThat(count("processed_events")).isZero();assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,id)).isEqualTo("RECRUITING");
    }
    @Test void sagaOutboxLookupHasAggregateIndex()throws Exception{
        try(var connection=jdbc.getDataSource().getConnection();var indexes=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,System.getenv("SAGA_TEST_JDBC")==null?"OUTBOX_EVENT":"outbox_event",false,false)){
            boolean found=false;
            while(indexes.next())if("idx_outbox_event_aggregate_id".equalsIgnoreCase(indexes.getString("INDEX_NAME"))&&"aggregate_id".equalsIgnoreCase(indexes.getString("COLUMN_NAME")))found=true;
            assertThat(found).isTrue();
        }
    }

}
