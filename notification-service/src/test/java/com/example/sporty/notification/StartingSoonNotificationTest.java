package com.example.sporty.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import com.example.sporty.notification.events.dto.MatchStatusChangedEvent;
import com.example.sporty.notification.events.dto.StartingSoonEvent;
import com.example.sporty.notification.events.repository.ProcessedEventRepository;
import com.example.sporty.notification.events.service.NotificationEventService;
import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import com.example.sporty.notification.notifications.domain.entity.NotificationType;
import com.example.sporty.notification.notifications.repository.NotificationRepository;

/**
 * match.starting-soon.v1 → 시작 전 알림 저장 규칙(누구에게, 오늘/내일 문구, 모임당 한 번, 취소된 모임 건너뜀).
 * 현재 시각은 2026-11-02 09:00(서울)으로 고정한다. 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class StartingSoonNotificationTest {
    static final long OWNER = 7L;
    static final long USER = 5L;
    static final long OTHER = 9L;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC); // 서울 09:00
        }
    }

    @Autowired NotificationEventService service;
    @Autowired NotificationRepository notifications;
    @Autowired ProcessedEventRepository processedEvents;

    // 방장 포함 참가자 전원에게, 내일 시작이면 "내일 19:00"
    @Test
    void notifiesEveryParticipantWithTomorrowTime() {
        String eventId = newEventId();
        service.handleStartingSoon(new StartingSoonEvent(eventId, 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", List.of(OWNER, USER)));

        NotificationEntity mine = only(USER);
        assertThat(mine.getType()).isEqualTo(NotificationType.STARTING_SOON);
        assertThat(mine.getBody()).isEqualTo("'주말 풋살' 모임이 내일 19:00에 시작해요");
        assertThat(mine.getTitle()).isEqualTo("주말 풋살");
        assertThat(mine.getTargetMatchId()).isEqualTo(57L);
        assertThat(mine.getEventId()).isEqualTo(eventId);
        assertThat(only(OWNER).getBody()).isEqualTo("'주말 풋살' 모임이 내일 19:00에 시작해요");
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    // 24시간 미만으로 새로 만든 모임처럼 오늘 시작이면 "오늘"
    @Test
    void sameDayMatchSaysToday() {
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "점심 농구",
                "2026-11-02T12:30:00+09:00", List.of(USER)));

        assertThat(only(USER).getBody()).isEqualTo("'점심 농구' 모임이 오늘 12:30에 시작해요");
    }

    // 오프셋이 +09:00이 아니어도 서울 시각으로 바꿔 판단한다(UTC 15:00 = 서울 다음 날 00:00)
    @Test
    void otherOffsetIsConvertedToSeoulTime() {
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "새벽 러닝",
                "2026-11-02T15:00:00Z", List.of(USER)));

        assertThat(only(USER).getBody()).isEqualTo("'새벽 러닝' 모임이 내일 00:00에 시작해요");
    }

    // 늦게 도착해 오늘·내일이 아니면 날짜를 그대로 쓴다
    @Test
    void laterDayShowsDate() {
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "주말 풋살",
                "2026-11-05T19:00:00+09:00", List.of(USER)));

        assertThat(only(USER).getBody()).isEqualTo("'주말 풋살' 모임이 11월 5일 19:00에 시작해요");
    }

    // 같은 이벤트가 다시 와도, 같은 모임으로 다른 eventId가 와도 회원당 한 번만
    @Test
    void savedOncePerMemberAndMatch() {
        StartingSoonEvent event = new StartingSoonEvent(newEventId(), 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", List.of(OWNER, USER));
        service.handleStartingSoon(event);
        service.handleStartingSoon(event);
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", List.of(OWNER, USER, OTHER)));

        assertThat(notifications.findAll()).hasSize(3);
        assertThat(only(OTHER).getType()).isEqualTo(NotificationType.STARTING_SOON);
    }

    // 같은 모임 취소 알림을 이미 받은 회원은 건너뛰고, 받지 않은 회원에게는 보낸다
    @Test
    void skipsMembersWhoAlreadyGotCancelledNotice() {
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 57L, "주말 풋살",
                "RECRUITING", "CANCELLED", OWNER, List.of(USER), "우천으로 취소"));
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", List.of(OWNER, USER)));

        assertThat(only(USER).getType()).isEqualTo(NotificationType.STATUS_CHANGED);
        assertThat(only(OWNER).getType()).isEqualTo(NotificationType.STARTING_SOON);
    }

    // 마감 알림(같은 STATUS_CHANGED 종류)은 취소가 아니므로 시작 전 알림을 막지 않는다
    @Test
    void closedNoticeDoesNotBlockReminder() {
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 57L, "주말 풋살",
                "RECRUITING", "CLOSED", OWNER, List.of(OWNER, USER), null));
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", List.of(OWNER)));

        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(OWNER))
                .extracting(NotificationEntity::getType)
                .containsExactlyInAnyOrder(NotificationType.STATUS_CHANGED, NotificationType.STARTING_SOON);
    }

    // 다른 모임의 취소 알림은 영향이 없다
    @Test
    void cancelOfAnotherMatchDoesNotBlock() {
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 58L, "평일 농구",
                "RECRUITING", "CANCELLED", OWNER, List.of(USER), null));
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", List.of(USER)));

        assertThat(notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(USER)).hasSize(2);
    }

    // null·중복 참가자는 거르고, 참가자가 없으면 기록만 남긴다
    @Test
    void nullAndDuplicateParticipantsAreFiltered() {
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", Arrays.asList(USER, USER, null)));
        String empty = newEventId();
        service.handleStartingSoon(new StartingSoonEvent(empty, 58L, "평일 농구",
                "2026-11-03T19:00:00+09:00", null));

        assertThat(notifications.findAll()).hasSize(1);
        assertThat(processedEvents.existsById(empty)).isTrue();
    }

    // matchId·startAt이 잘못됐거나 이미 시작한 모임이면 알림 없이 처리 기록만
    @Test
    void invalidOrStartedMatchIsRecordedWithoutNotification() {
        List<StartingSoonEvent> events = List.of(
                new StartingSoonEvent(newEventId(), null, "주말 풋살", "2026-11-03T19:00:00+09:00", List.of(USER)),
                new StartingSoonEvent(newEventId(), 0L, "주말 풋살", "2026-11-03T19:00:00+09:00", List.of(USER)),
                new StartingSoonEvent(newEventId(), 57L, "주말 풋살", null, List.of(USER)),
                new StartingSoonEvent(newEventId(), 57L, "주말 풋살", "2026-11-03T19:00:00", List.of(USER)),
                new StartingSoonEvent(newEventId(), 57L, "주말 풋살", "내일 저녁", List.of(USER)),
                new StartingSoonEvent(newEventId(), 57L, "주말 풋살", "2026-11-02T09:00:00+09:00", List.of(USER)));

        events.forEach(service::handleStartingSoon);

        assertThat(notifications.findAll()).isEmpty();
        assertThat(events).allSatisfy(e -> assertThat(processedEvents.existsById(e.eventId())).isTrue());
    }

    // 제목이 없으면 기본 제목, 본문은 제목 없이
    @Test
    void missingTitleUsesDefault() {
        service.handleStartingSoon(new StartingSoonEvent(newEventId(), 57L, null,
                "2026-11-03T19:00:00+09:00", List.of(USER)));

        assertThat(only(USER).getTitle()).isEqualTo("모임 시작 하루 전");
        assertThat(only(USER).getBody()).isEqualTo("모임이 내일 19:00에 시작해요");
    }

    @Test
    void eventWithoutIdIsRejected() {
        assertThatThrownBy(() -> service.handleStartingSoon(new StartingSoonEvent(null, 57L, "주말 풋살",
                "2026-11-03T19:00:00+09:00", List.of(USER))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(notifications.findAll()).isEmpty();
    }

    private NotificationEntity only(long recipient) {
        List<NotificationEntity> found = notifications.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(recipient);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
