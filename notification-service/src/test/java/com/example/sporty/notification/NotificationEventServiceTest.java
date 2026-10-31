package com.example.sporty.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.sporty.notification.events.dto.MatchJoinedEvent;
import com.example.sporty.notification.events.dto.MatchStatusChangedEvent;
import com.example.sporty.notification.events.dto.WithdrawalRejectedEvent;
import com.example.sporty.notification.events.repository.ProcessedEventRepository;
import com.example.sporty.notification.events.service.NotificationEventService;
import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import com.example.sporty.notification.notifications.domain.entity.NotificationType;
import com.example.sporty.notification.notifications.repository.NotificationRepository;

/**
 * 매치 이벤트 → 알림 저장 규칙(누구에게, 어떤 문구로, 한 번만)을 확인한다.
 * Kafka 없이 서비스를 바로 부른다. 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class NotificationEventServiceTest {
    static final long USER = 5L;
    static final long OWNER = 7L;
    @Autowired NotificationEventService service;
    @Autowired NotificationRepository notifications;
    @Autowired ProcessedEventRepository processedEvents;

    // joined → 참가자·방장 둘 다
    @Test
    void joinedNotifiesParticipantAndOwner() {
        String eventId = newEventId();
        service.handleMatchJoined(new MatchJoinedEvent(eventId, 57L, "주말 풋살", USER, OWNER, 3, 10));

        NotificationEntity mine = only(USER);
        assertThat(mine.getType()).isEqualTo(NotificationType.JOINED);
        assertThat(mine.getBody()).isEqualTo("'주말 풋살' 모임 참가가 확정됐어요");
        assertThat(mine.getTitle()).isEqualTo("주말 풋살");
        assertThat(mine.getTargetMatchId()).isEqualTo(57L);
        assertThat(mine.getEventId()).isEqualTo(eventId);
        assertThat(mine.isRead()).isFalse();

        assertThat(only(OWNER).getBody()).isEqualTo("'주말 풋살' 모임에 새 참가자가 들어왔어요 (3/10)");
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    // 방장 본인이 참가한 이벤트면 한 번만
    @Test
    void joinedByOwnerNotifiesOnce() {
        service.handleMatchJoined(new MatchJoinedEvent(newEventId(), 57L, "주말 풋살", OWNER, OWNER, 1, 10));

        assertThat(notifications.findAll()).hasSize(1);
    }

    // 같은 eventId를 두 번 받아도 한 번만 저장
    @Test
    void sameEventIdIsSavedOnce() {
        MatchJoinedEvent event = new MatchJoinedEvent(newEventId(), 57L, "주말 풋살", USER, OWNER, 3, 10);

        service.handleMatchJoined(event);
        service.handleMatchJoined(event);

        assertThat(notifications.findAll()).hasSize(2);
    }

    // CANCELLED → 참가자 전원, 사유 포함. 같은 ID가 두 번 들어 있어도 한 번
    @Test
    void cancelledNotifiesEveryParticipantWithReason() {
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 57L, "주말 풋살",
                "RECRUITING", "CANCELLED", Arrays.asList(OWNER, USER, USER, null), "우천"));

        assertThat(notifications.findAll()).hasSize(2);
        assertThat(only(USER).getType()).isEqualTo(NotificationType.STATUS_CHANGED);
        assertThat(only(USER).getBody()).isEqualTo("'주말 풋살' 모임이 취소됐어요 (사유: 우천)");
        assertThat(only(OWNER).getTargetMatchId()).isEqualTo(57L);
    }

    // CLOSED → 마감 알림
    @Test
    void closedNotifiesParticipants() {
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 57L, "주말 풋살",
                "RECRUITING", "CLOSED", List.of(OWNER, USER), null));

        assertThat(only(USER).getBody()).isEqualTo("'주말 풋살' 모임 모집이 마감됐어요");
    }

    // 마감·취소가 아닌 상태 변경은 알림 없이 처리 기록만
    @Test
    void otherStatusChangesAreRecordedWithoutNotification() {
        String eventId = newEventId();
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(eventId, 57L, "주말 풋살",
                "CLOSED", "IN_PROGRESS", List.of(OWNER, USER), null));

        assertThat(notifications.findAll()).isEmpty();
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    // 탈퇴 거절 → 요청한 회원에게, 매치 없음
    @Test
    void withdrawalRejectedNotifiesRequester() {
        service.handleWithdrawalRejected(new WithdrawalRejectedEvent(newEventId(), UUID.randomUUID().toString(),
                USER, "IN_PROGRESS_OWNER", List.of(57L)));

        NotificationEntity mine = only(USER);
        assertThat(mine.getType()).isEqualTo(NotificationType.WITHDRAWAL_REJECTED);
        assertThat(mine.getBody()).isEqualTo("진행 중인 모임이 있어 탈퇴할 수 없어요");
        assertThat(mine.getTitle()).isEqualTo("회원 탈퇴");
        assertThat(mine.getTargetMatchId()).isNull();
    }

    // 제목이 없거나 길어도 컬럼 길이 안에서 저장
    @Test
    void missingOrLongTitleStillFits() {
        service.handleMatchJoined(new MatchJoinedEvent(newEventId(), 57L, null, USER, null, null, null));
        assertThat(only(USER).getTitle()).isEqualTo("참가 확정");
        assertThat(only(USER).getBody()).isEqualTo("모임 참가가 확정됐어요");

        String longTitle = "가".repeat(600);
        service.handleMatchJoined(new MatchJoinedEvent(newEventId(), 58L, longTitle, OWNER, null, null, null));
        assertThat(only(OWNER).getTitle()).hasSize(100);
        assertThat(only(OWNER).getBody()).hasSize(500);
    }

    // eventId가 없으면 중복을 가릴 수 없어 거절
    @Test
    void eventWithoutIdIsRejected() {
        assertThatThrownBy(() -> service.handleMatchJoined(new MatchJoinedEvent(" ", 57L, "주말 풋살", USER, OWNER, 3, 10)))
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
