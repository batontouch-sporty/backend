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
import com.example.sporty.notification.events.dto.WaitlistPromotedEvent;
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
                "RECRUITING", "CANCELLED", OWNER, Arrays.asList(OWNER, USER, USER, null), "모임장 회원 탈퇴로 인한 모임 취소"));

        assertThat(notifications.findAll()).hasSize(2);
        assertThat(only(USER).getType()).isEqualTo(NotificationType.STATUS_CHANGED);
        assertThat(only(USER).getBody()).isEqualTo("'주말 풋살' 모임이 취소됐어요 (사유: 모임장 회원 탈퇴로 인한 모임 취소)");
        assertThat(only(OWNER).getTargetMatchId()).isEqualTo(57L);
    }

    // 사유가 코드 값이면 회원에게 보이지 않게 빼고, 없으면 사유 없이 보낸다
    @Test
    void cancelledReasonThatLooksLikeCodeIsHidden() {
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 57L, "주말 풋살",
                "RECRUITING", "CANCELLED", OWNER, List.of(USER), "USER_WITHDRAWAL"));
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 58L, "평일 농구",
                "CLOSED", "CANCELLED", OWNER, List.of(OWNER), null));

        assertThat(only(USER).getBody()).isEqualTo("'주말 풋살' 모임이 취소됐어요");
        assertThat(only(OWNER).getBody()).isEqualTo("'평일 농구' 모임이 취소됐어요");
    }

    // CLOSED → 방장에게만 마감 알림(참가자에게는 보내지 않음)
    @Test
    void closedNotifiesOnlyOwner() {
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(newEventId(), 57L, "주말 풋살",
                "RECRUITING", "CLOSED", OWNER, List.of(OWNER, USER), null));

        assertThat(notifications.findAll()).hasSize(1);
        assertThat(only(OWNER).getBody()).isEqualTo("'주말 풋살' 모임 모집이 마감됐어요");
        assertThat(only(OWNER).getTargetMatchId()).isEqualTo(57L);
    }

    // ownerId가 없는 CLOSED는 보낼 사람을 알 수 없어 알림 없이 처리 기록만
    @Test
    void closedWithoutOwnerIsRecordedWithoutNotification() {
        String eventId = newEventId();
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(eventId, 57L, "주말 풋살",
                "RECRUITING", "CLOSED", null, List.of(OWNER, USER), null));

        assertThat(notifications.findAll()).isEmpty();
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    // 마감·취소가 아닌 상태 변경(탈퇴로 인한 모집 재개 포함)은 알림 없이 처리 기록만
    @Test
    void otherStatusChangesAreRecordedWithoutNotification() {
        String inProgress = newEventId();
        String reopened = newEventId();
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(inProgress, 57L, "주말 풋살",
                "CLOSED", "IN_PROGRESS", OWNER, List.of(OWNER, USER), null));
        service.handleMatchStatusChanged(new MatchStatusChangedEvent(reopened, 57L, "주말 풋살",
                "CLOSED", "RECRUITING", OWNER, List.of(), "참가자 회원 탈퇴로 인한 모집 재개"));

        assertThat(notifications.findAll()).isEmpty();
        assertThat(processedEvents.existsById(inProgress)).isTrue();
        assertThat(processedEvents.existsById(reopened)).isTrue();
    }

    // 탈퇴 거절(진행 중인 모임의 방장) → 요청한 회원에게, 매치 없음
    @Test
    void withdrawalRejectedNotifiesRequester() {
        service.handleWithdrawalRejected(rejected(USER, "OWNER_OF_IN_PROGRESS_MATCH"));

        NotificationEntity mine = only(USER);
        assertThat(mine.getType()).isEqualTo(NotificationType.WITHDRAWAL_REJECTED);
        assertThat(mine.getBody()).isEqualTo("진행 중인 모임이 있어 탈퇴할 수 없어요");
        assertThat(mine.getTitle()).isEqualTo("회원 탈퇴");
        assertThat(mine.getTargetMatchId()).isNull();
    }

    // 거절 사유별로 문구가 다르고, 코드 값은 본문에 드러나지 않는다
    @Test
    void withdrawalRejectedMessageDependsOnReason() {
        service.handleWithdrawalRejected(rejected(USER, "DEADLINE_EXCEEDED"));
        service.handleWithdrawalRejected(rejected(OWNER, "SOMETHING_NEW"));

        assertThat(only(USER).getBody()).isEqualTo("탈퇴 처리 시간이 지나 탈퇴가 취소됐어요. 잠시 후 다시 시도해 주세요");
        assertThat(only(OWNER).getBody()).isEqualTo("탈퇴 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요");
        assertThat(notifications.findAll()).extracting(NotificationEntity::getBody)
                .noneMatch(body -> body.matches(".*[A-Z]{2,}_[A-Z].*"));
    }

    // 대기 승급 → 승급된 회원 한 명에게 참가 확정 알림
    @Test
    void waitlistPromotedNotifiesPromotedUser() {
        String eventId = newEventId();
        service.handleWaitlistPromoted(new WaitlistPromotedEvent(eventId, 57L, "주말 풋살", USER));

        NotificationEntity mine = only(USER);
        assertThat(mine.getType()).isEqualTo(NotificationType.WAITLIST_PROMOTED);
        assertThat(mine.getBody()).isEqualTo("'주말 풋살' 대기가 풀려 참가가 확정됐어요");
        assertThat(mine.getTitle()).isEqualTo("주말 풋살");
        assertThat(mine.getTargetMatchId()).isEqualTo(57L);
        assertThat(notifications.findAll()).hasSize(1);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    // 같은 승급 이벤트를 두 번 받아도 한 번만
    @Test
    void sameWaitlistPromotedEventIsSavedOnce() {
        WaitlistPromotedEvent event = new WaitlistPromotedEvent(newEventId(), 57L, "주말 풋살", USER);

        service.handleWaitlistPromoted(event);
        service.handleWaitlistPromoted(event);

        assertThat(notifications.findAll()).hasSize(1);
    }

    // userId·matchId가 없거나 0 이하면 알림 없이 처리 기록만
    @Test
    void invalidWaitlistPromotedIsRecordedWithoutNotification() {
        List<WaitlistPromotedEvent> events = List.of(
                new WaitlistPromotedEvent(newEventId(), 57L, "주말 풋살", null),
                new WaitlistPromotedEvent(newEventId(), 57L, "주말 풋살", 0L),
                new WaitlistPromotedEvent(newEventId(), null, "주말 풋살", USER),
                new WaitlistPromotedEvent(newEventId(), -1L, "주말 풋살", USER));

        events.forEach(service::handleWaitlistPromoted);

        assertThat(notifications.findAll()).isEmpty();
        assertThat(events).allMatch(e -> processedEvents.existsById(e.eventId()));
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

    private WithdrawalRejectedEvent rejected(long userId, String reason) {
        return new WithdrawalRejectedEvent(newEventId(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                userId, reason, List.of(57L));
    }
}
