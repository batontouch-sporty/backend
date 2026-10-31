package com.example.sporty.notification.events.service;

import com.example.sporty.notification.events.KafkaTopics;
import com.example.sporty.notification.events.domain.entity.ProcessedEventEntity;
import com.example.sporty.notification.events.dto.MatchJoinedEvent;
import com.example.sporty.notification.events.dto.MatchStatusChangedEvent;
import com.example.sporty.notification.events.dto.WithdrawalRejectedEvent;
import com.example.sporty.notification.events.repository.ProcessedEventRepository;
import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import com.example.sporty.notification.notifications.domain.entity.NotificationType;
import com.example.sporty.notification.notifications.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

// 매치 이벤트를 받아 알림을 저장한다. 알림 대상은 이벤트에 담겨 오므로 다른 서비스를 부르지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventService {

    private static final int TITLE_MAX = 100;
    private static final int BODY_MAX = 500;
    private static final String WITHDRAWAL_TITLE = "회원 탈퇴";

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;

    // match.joined.v1 → 참가한 회원과 방장에게 알림
    @Transactional
    public void handleMatchJoined(MatchJoinedEvent event) {
        requireEventId(event.eventId());
        if (alreadyProcessed(event.eventId())) {
            return;
        }

        List<NotificationEntity> notifications = new ArrayList<>();
        if (event.userId() != null) {
            notifications.add(build(event.eventId(), event.userId(), NotificationType.JOINED,
                    event.title(), quote(event.title()) + "모임 참가가 확정됐어요", event.matchId()));
        }
        // 방장이 자기 매치에 참가한 경우에는 한 번만 보낸다.
        if (event.ownerId() != null && !event.ownerId().equals(event.userId())) {
            notifications.add(build(event.eventId(), event.ownerId(), NotificationType.JOINED,
                    event.title(), quote(event.title()) + "모임에 새 참가자가 들어왔어요"
                            + headcount(event.current(), event.max()), event.matchId()));
        }

        saveAll(event.eventId(), KafkaTopics.MATCH_JOINED, notifications);
    }

    // match.status-changed.v1 → 마감(CLOSED)·취소(CANCELLED)일 때만 참가자에게 알림
    @Transactional
    public void handleMatchStatusChanged(MatchStatusChangedEvent event) {
        requireEventId(event.eventId());
        if (alreadyProcessed(event.eventId())) {
            return;
        }

        String message = switch (Objects.requireNonNullElse(event.to(), "")) {
            case "CLOSED" -> quote(event.title()) + "모임 모집이 마감됐어요";
            case "CANCELLED" -> quote(event.title()) + "모임이 취소됐어요" + reasonSuffix(event.reason());
            default -> null;
        };

        List<NotificationEntity> notifications = new ArrayList<>();
        if (message != null && event.participantIds() != null) {
            for (Long recipient : new LinkedHashSet<>(event.participantIds())) {
                if (recipient != null) {
                    notifications.add(build(event.eventId(), recipient, NotificationType.STATUS_CHANGED,
                            event.title(), message, event.matchId()));
                }
            }
        }

        saveAll(event.eventId(), KafkaTopics.MATCH_STATUS_CHANGED, notifications);
    }

    // match.withdrawal-rejected.v1 → 탈퇴를 요청한 회원에게 알림
    @Transactional
    public void handleWithdrawalRejected(WithdrawalRejectedEvent event) {
        requireEventId(event.eventId());
        if (alreadyProcessed(event.eventId())) {
            return;
        }

        List<NotificationEntity> notifications = new ArrayList<>();
        if (event.userId() != null) {
            notifications.add(build(event.eventId(), event.userId(), NotificationType.WITHDRAWAL_REJECTED,
                    WITHDRAWAL_TITLE, "진행 중인 모임이 있어 탈퇴할 수 없어요", null));
        }

        saveAll(event.eventId(), KafkaTopics.MATCH_WITHDRAWAL_REJECTED, notifications);
    }

    // eventId가 없으면 중복을 가릴 수 없어 처리하지 않는다(재시도 없이 DLT로 간다).
    private void requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId가 없는 이벤트입니다.");
        }
    }

    private boolean alreadyProcessed(String eventId) {
        if (processedEventRepository.existsById(eventId)) {
            log.info("이미 처리한 이벤트라 건너뜀: eventId={}", eventId);
            return true;
        }
        return false;
    }

    // 알림과 처리 기록을 한 트랜잭션에 저장한다. 알림 대상이 없어도 처리 기록은 남긴다.
    private void saveAll(String eventId, String topic, List<NotificationEntity> notifications) {
        notificationRepository.saveAll(notifications);
        processedEventRepository.save(new ProcessedEventEntity(eventId, topic));
    }

    private NotificationEntity build(String eventId, Long recipient, NotificationType type,
                                     String title, String body, Long matchId) {
        return NotificationEntity.builder()
                .eventId(eventId)
                .recipientUserId(recipient)
                .type(type)
                .title(cut(title == null || title.isBlank() ? type.getDescription() : title, TITLE_MAX))
                .body(cut(body, BODY_MAX))
                .targetMatchId(matchId)
                .build();
    }

    private static String quote(String title) {
        return title == null || title.isBlank() ? "" : "'" + title + "' ";
    }

    private static String headcount(Integer current, Integer max) {
        return current == null || max == null ? "" : " (" + current + "/" + max + ")";
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : " (사유: " + reason + ")";
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

}
