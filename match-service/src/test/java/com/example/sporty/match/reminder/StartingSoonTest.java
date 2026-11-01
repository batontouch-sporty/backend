package com.example.sporty.match.reminder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.sporty.match.withdrawal.WithdrawalOutboxRelay;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class StartingSoonTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    ObjectMapper mapper;
    StartingSoonStore store;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);
    final LocalDateTime now = LocalDateTime.of(2026, 11, 1, 21, 0);

    @BeforeEach
    void prepare() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:soon-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
                        "sa",
                        "");
        var schema =
                new ClassPathResource("withdrawal-r7.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("payload CLOB", "payload VARCHAR(10000)");
        new ResourceDatabasePopulator(
                        new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        mapper = new ObjectMapper();
        store = new StartingSoonStore(jdbc, mapper, manager, clock);
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    void match(long id, LocalDateTime start, String status) {
        jdbc.update(
                "INSERT INTO"
                    + " matches(id,title,start_at,end_at,max_participant,status,cancelled_at,sport_type,gender_group,facility_id,facility_name,location_name,region)"
                    + " VALUES(?,?,?,?,?,?,?,'TENNIS','MIXED',12,'Court','Location','송파구')",
                id,
                "주말 풋살",
                start,
                start.plusHours(1),
                3,
                status,
                "CANCELLED".equals(status) ? now : null);
        jdbc.update(
                "INSERT INTO match_participants(match_id,user_id,role)"
                        + " VALUES(?,101,'OWNER'),(?,102,'PARTICIPANT')",
                id,
                id);
    }

    int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
    }

    @Test
    void issuedMultiDigitMatchIsExcludedFromNextCandidateScan() {
        match(57123, now.plusHours(1), "CLOSED");
        assertThat(store.candidates()).contains(57123L);
        assertThat(store.enqueue(57123)).isTrue();
        assertThat(store.candidates()).isEmpty();
    }

    @Test
    void selectsDueWindowIncludingUnder24HoursButNotCancelledOrStarted() {
        match(1, now.plusHours(24), "RECRUITING");
        match(2, now.plusMinutes(5), "CLOSED");
        match(3, now.plusHours(24).plusNanos(1000), "RECRUITING");
        match(4, now, "CLOSED");
        match(5, now.plusHours(1), "CANCELLED");
        match(6, now.plusHours(1), "IN_PROGRESS");
        match(7, now.plusHours(1), "COMPLETED");
        assertThat(store.candidates()).containsExactly(2L, 1L);
        for (long id = 1; id <= 7; id++) store.enqueue(id);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void exactPayloadContainsOffsetAndConfirmedUsersIncludingOwnerNotWaiters() throws Exception {
        match(1, now.plusHours(22), "CLOSED");
        jdbc.update("INSERT INTO match_waitlist(match_id,user_id) VALUES(1,999)");
        assertThat(store.enqueue(1)).isTrue();
        var row = jdbc.queryForMap("SELECT aggregate_id,event_type,payload FROM outbox_event");
        assertThat(row.get("aggregate_id")).isEqualTo("1");
        assertThat(row.get("event_type")).isEqualTo(StartingSoonStore.TOPIC);
        var event = mapper.readTree((String) row.get("payload"));
        assertThat(event.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "eventId", "matchId", "title", "startAt", "participantIds");
        assertThat(UUID.fromString(event.path("eventId").asText())).isNotNull();
        assertThat(event.path("startAt").asText()).isEqualTo("2026-11-02T19:00:00+09:00");
        assertThat(event.path("participantIds").toString()).isEqualTo("[101,102]");
    }

    @Test
    void repeatsReplicaRestartAndScheduleChangeKeepOneEventEvenAfterPublished() {
        match(1, now.plusHours(24), "RECRUITING");
        assertThat(store.enqueue(1)).isTrue();
        String original = jdbc.queryForObject("SELECT payload FROM outbox_event", String.class);
        assertThat(store.enqueue(1)).isFalse();
        jdbc.update("UPDATE outbox_event SET published_at=CURRENT_TIMESTAMP");
        jdbc.update(
                "UPDATE matches SET start_at=?,end_at=? WHERE id=1",
                now.plusHours(10),
                now.plusHours(11));
        var restarted = new StartingSoonStore(jdbc, mapper, manager, clock);
        assertThat(restarted.candidates()).isEmpty();
        assertThat(restarted.enqueue(1)).isFalse();
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_event", String.class))
                .isEqualTo(original);
    }

    @Test
    void concurrentReplicasCreateExactlyOneOutbox() throws Exception {
        match(1, now.plusHours(1), "CLOSED");
        var executor = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 20; i++)
                tasks.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return new StartingSoonStore(jdbc, mapper, manager, clock)
                                            .enqueue(1);
                                }));
            gate.countDown();
            int success = 0;
            for (var task : tasks) if (task.get(25, TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(1);
            assertThat(count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationRescheduleStartAndDeletionAreRecheckedAfterCandidateSelection() {
        match(1, now.plusHours(1), "RECRUITING");
        assertThat(store.candidates()).contains(1L);
        jdbc.update("UPDATE matches SET status='CANCELLED',cancelled_at=? WHERE id=1", now);
        assertThat(store.enqueue(1)).isFalse();
        match(2, now.plusHours(1), "RECRUITING");
        jdbc.update(
                "UPDATE matches SET start_at=?,end_at=? WHERE id=2",
                now.plusDays(3),
                now.plusDays(3).plusHours(1));
        assertThat(store.enqueue(2)).isFalse();
        match(3, now.plusHours(1), "RECRUITING");
        jdbc.update("UPDATE matches SET start_at=? WHERE id=3", now.minusMinutes(1));
        assertThat(store.enqueue(3)).isFalse();
        assertThat(store.enqueue(999)).isFalse();
        assertThat(count()).isZero();
    }

    @Test
    void recoveryCatchesMissedWindowOnlyBeforeStartAndNoRecipientsAreSkipped() {
        match(1, now.plusHours(2), "CLOSED");
        var recovery =
                new StartingSoonStore(
                        jdbc, mapper, manager, Clock.offset(clock, Duration.ofHours(1)));
        assertThat(recovery.enqueue(1)).isTrue();
        match(2, now.plusHours(2), "RECRUITING");
        var late =
                new StartingSoonStore(
                        jdbc, mapper, manager, Clock.offset(clock, Duration.ofHours(3)));
        assertThat(late.enqueue(2)).isFalse();
        match(3, now.plusHours(1), "CLOSED");
        jdbc.update("DELETE FROM match_participants WHERE match_id=3");
        assertThat(store.enqueue(3)).isFalse();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void serializationFailureRollsBackAndNextScanCanRetry() throws Exception {
        match(1, now.plusHours(1), "RECRUITING");
        var broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("failure") {});
        assertThatThrownBy(() -> new StartingSoonStore(jdbc, broken, manager, clock).enqueue(1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
        assertThat(store.enqueue(1)).isTrue();
    }

    @Test
    void schedulerContinuesAfterOneMatchFails() {
        var mock = mock(StartingSoonStore.class);
        when(mock.candidates()).thenReturn(List.of(1L, 2L));
        when(mock.enqueue(1)).thenThrow(new IllegalStateException("failure"));
        new StartingSoonScheduler(mock).scan();
        verify(mock).enqueue(2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sharedRelayRetriesSameEventAndMatchKeyAfterKafkaFailure() {
        match(1, now.plusHours(1), "CLOSED");
        store.enqueue(1);
        String original = jdbc.queryForObject("SELECT payload FROM outbox_event", String.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        var relay = new WithdrawalOutboxRelay(jdbc, kafka, manager);
        relay.publish();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event", Object.class))
                .isNull();
        relay.publish();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event", Object.class))
                .isNotNull();
        verify(kafka, times(2)).send(StartingSoonStore.TOPIC, "1", original);
        assertThat(count()).isEqualTo(1);
    }
}
