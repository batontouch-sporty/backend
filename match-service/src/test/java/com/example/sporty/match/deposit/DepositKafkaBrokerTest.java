package com.example.sporty.match.deposit;

import com.example.sporty.match.crud.*;
import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.*;
import com.example.sporty.match.participation.*;
import com.example.sporty.match.repository.MatchParticipantRepository;
import com.example.sporty.match.withdrawal.MemberLocks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="DEPOSIT_TEST_BROKER",matches="localhost:39485")
@SpringBootTest(properties={"spring.cloud.config.enabled=false","spring.config.import=","eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:deposit-broker;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","match.deposit.enabled=true","match.withdrawal.enabled=false",
    "match.outbox.enabled=true","match.outbox.delay-ms=100","match.deposit.maintenance-delay-ms=86400000",
    "spring.kafka.listener.auto-startup=true"})
class DepositKafkaBrokerTest {
    @DynamicPropertySource static void broker(DynamicPropertyRegistry p) { p.add("spring.kafka.bootstrap-servers",()->System.getenv("DEPOSIT_TEST_BROKER")); }
    @Autowired MatchCrudService crud;
    @Autowired ParticipationService participation;
    @Autowired MatchParticipantRepository participants;
    @Autowired DepositSaga saga;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("withdrawalKafkaTemplate") KafkaTemplate<String,String> kafka;
    @MockitoBean MatchRemoteChecks checks;
    @MockitoBean(name="participationMemberLocks") MemberLocks locks;

    @Test void actualOutboxChargeResultsRefundAndMalformedEventDlt() throws Exception {
        jdbc.execute("CREATE TABLE outbox_event(event_id VARCHAR(64) PRIMARY KEY,aggregate_id VARCHAR(64),event_type VARCHAR(80),payload CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE processed_events(event_id VARCHAR(64) PRIMARY KEY,event_type VARCHAR(80),processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        when(checks.checkCreation(anyLong(),anyLong(),any(),any())).thenReturn(new MatchRemoteChecks.CreationContext(
            new MemberClient.Member(1L,"MALE","ACTIVE",null,List.of()),
            new MatchRemoteChecks.FacilitySnapshot(12L,"Court","Place","Seoul",true,null)));
        when(checks.activeMember(anyLong())).thenAnswer(i->new MemberClient.Member(i.getArgument(0),"MALE","ACTIVE",null,List.of()));
        try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",System.getenv("DEPOSIT_TEST_BROKER"),
                "group.id","deposit-review-"+UUID.randomUUID(),"key.deserializer",StringDeserializer.class,
                "value.deserializer",StringDeserializer.class,"auto.offset.reset","earliest","enable.auto.commit",false))) {
            consumer.subscribe(List.of(DepositStore.CHARGE,DepositStore.REFUND,"user.deposit-charged.v1.DLT"));
            var start=LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(2);
            long m=crud.create(1L,new MatchDtos.Create(12L,"Broker fixture",null,start,start.plusHours(1),3,SkillLevel.BEGINNER,SportType.TENNIS,GenderGroup.MIXED));
            String owner=participants.findByMatch_IdAndUserId(m,1L).orElseThrow().getRequestId();
            var charge=find(consumer,DepositStore.CHARGE,owner);
            assertThat(charge.key()).isEqualTo("1"); assertThat(mapper.readTree(charge.value()).path("requestType").asText()).isEqualTo("CREATE");
            send("user.deposit-charged.v1",owner,m,1,Map.of()); await(owner,1,"CONFIRMED",false);
            participation.join(m,2L); String join=participants.findByMatch_IdAndUserId(m,2L).orElseThrow().getRequestId();
            assertThat(find(consumer,DepositStore.CHARGE,join).key()).isEqualTo("2");
            send("user.deposit-charged.v1",join,m,2,Map.of()); await(join,2,"CONFIRMED",false);
            participation.leave(m,2L);
            assertThat(mapper.readTree(find(consumer,DepositStore.REFUND,join).value()).path("reason").asText()).isEqualTo("PARTICIPANT_CANCELLED");
            send("user.deposit-refund-completed.v1",join,m,2,Map.of("outcome","RETURNED","creditedPoints",10));
            await(join,2,"COMPLETED",true);
            String malformed="{invalid-"+UUID.randomUUID();
            kafka.send("user.deposit-charged.v1","2",malformed).get(10,TimeUnit.SECONDS);
            var dlt=find(consumer,"user.deposit-charged.v1.DLT",malformed);
            assertThat(dlt.key()).isEqualTo("2"); assertThat(dlt.value()).isEqualTo(malformed);
        }
    }
    void send(String topic,String request,long match,long user,Map<String,Object> extra) throws Exception {
        var e=new HashMap<String,Object>(extra); e.put("eventId",UUID.randomUUID().toString()); e.put("requestId",request);
        e.put("matchId",match); e.put("userId",user); e.put("occurredAt",OffsetDateTime.now().toString());
        kafka.send(topic,Long.toString(user),mapper.writeValueAsString(e)).get(10,TimeUnit.SECONDS);
    }
    ConsumerRecord<String,String> find(KafkaConsumer<String,String> c,String topic,String marker) {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<deadline) for(var r:c.poll(Duration.ofMillis(200)))
            if(r.topic().equals(topic)&&r.value().contains(marker)) return r;
        throw new AssertionError("Missing Kafka event: "+topic+" / "+marker);
    }
    void await(String request,long user,String expected,boolean refund) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline) {
            var state=saga.status(request,user);
            if(expected.equals(refund?state.refundStatus():state.status())) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Deposit result was not consumed: "+expected);
    }
}
