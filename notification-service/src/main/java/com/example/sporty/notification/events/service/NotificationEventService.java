package com.example.sporty.notification.events.service;

import com.example.sporty.notification.events.KafkaTopics;
import com.example.sporty.notification.events.domain.entity.ProcessedEventEntity;
import com.example.sporty.notification.events.dto.MatchJoinedEvent;
import com.example.sporty.notification.events.dto.MatchStatusChangedEvent;
import com.example.sporty.notification.events.dto.WaitlistPromotedEvent;
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
import java.util.regex.Pattern;

// 매치 이벤트를 받아 알림을 저장한다. 알림 대상은 이벤트에 담겨 오므로 다른 서비스를 부르지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventService {

    private static final int TITLE_MAX = 100;
    private static final int BODY_MAX = 500;
    private static final String WITHDRAWAL_TITLE = "회원 탈퇴";
    private static final Pattern CODE_LIKE = Pattern.compile("[A-Z0-9_]+");

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

    // match.status-changed.v1 → 마감(CLOSED)은 방장에게, 취소(CANCELLED)는 참가자에게 알림. 그 외 상태는 처리 기록만 남긴다.
    @Transactional
    public void handleMatchStatusChanged(MatchStatusChangedEvent event) {
        requireEventId(event.eventId());
        if (alreadyProcessed(event.eventId())) {
            return;
        }

        String to = Objects.requireNonNullElse(event.to(), "");
        List<Long> recipients = switch (to) {
            case "CLOSED" -> ownerOnly(event);
            case "CANCELLED" -> event.participantIds() == null ? List.of() : event.participantIds();
            default -> List.of();
        };
        String message = switch (to) {
            case "CLOSED" -> quote(event.title()) + "모임 모집이 마감됐어요";
            case "CANCELLED" -> quote(event.title()) + "모임이 취소됐어요" + reasonSuffix(event.reason());
            default -> null;
        };

        List<NotificationEntity> notifications = new ArrayList<>();
        for (Long recipient : new LinkedHashSet<>(recipients)) {
            if (recipient != null) {
                notifications.add(build(event.eventId(), recipient, NotificationType.STATUS_CHANGED,
                        event.title(), message, event.matchId()));
            }
        }

        saveAll(event.eventId(), KafkaTopics.MATCH_STATUS_CHANGED, notifications);
    }

    // match.withdrawal-rejected.v1 → 탈퇴를 요청한 회원에게 거절 사유별 알림
    @Transactional
    public void handleWithdrawalRejected(WithdrawalRejectedEvent event) {
        requireEventId(event.eventId());
        if (alreadyProcessed(event.eventId())) {
            return;
        }

        List<NotificationEntity> notifications = new ArrayList<>();
        if (event.userId() != null) {
            notifications.add(build(event.eventId(), event.userId(), NotificationType.WITHDRAWAL_REJECTED,
                    WITHDRAWAL_TITLE, withdrawalRejectedMessage(event.reason()), null));
        }

        saveAll(event.eventId(), KafkaTopics.MATCH_WITHDRAWAL_REJECTED, notifications);
    }

    // match.waitlist-promoted.v1 → 승급된 회원에게 참가 확정 알림
    @Transactional
    public void handleWaitlistPromoted(WaitlistPromotedEvent event) {
        requireEventId(event.eventId());
        if (alreadyProcessed(event.eventId())) {
            return;
        }

        // 받는 사람·모임을 알 수 없는 이벤트는 다시 받아도 같으므로 재시도하지 않고 기록만 남긴다.
        List<NotificationEntity> notifications = new ArrayList<>();
        if (isPositive(event.userId()) && isPositive(event.matchId())) {
            notifications.add(build(event.eventId(), event.userId(), NotificationType.WAITLIST_PROMOTED,
                    event.title(), quote(event.title()) + "대기가 풀려 참가가 확정됐어요", event.matchId()));
        } else {
            log.warn("userId·matchId가 올바르지 않은 승급 이벤트라 알림을 보내지 않음: eventId={}, userId={}, matchId={}",
                    event.eventId(), event.userId(), event.matchId());
        }

        saveAll(event.eventId(), KafkaTopics.MATCH_WAITLIST_PROMOTED, notifications);
    }

    private static boolean isPositive(Long id) {
        return id != null && id > 0;
    }

    // 마감 알림은 방장 한 명에게만 보낸다. ownerId가 없으면 보낼 대상을 알 수 없어 기록만 남긴다.
    private List<Long> ownerOnly(MatchStatusChangedEvent event) {
        if (event.ownerId() == null) {
            log.warn("ownerId가 없는 마감 이벤트라 알림을 보내지 않음: eventId={}, matchId={}",
                    event.eventId(), event.matchId());
            return List.of();
        }
        return List.of(event.ownerId());
    }

    // 거절 코드는 회원에게 그대로 보이지 않게 문구로 바꾼다. 모르는 코드는 일반 문구로 보낸다.
    private static String withdrawalRejectedMessage(String reason) {
        return switch (Objects.requireNonNullElse(reason, "")) {
            case "OWNER_OF_IN_PROGRESS_MATCH" -> "진행 중인 모임이 있어 탈퇴할 수 없어요";
            case "DEADLINE_EXCEEDED" -> "탈퇴 처리 시간이 지나 탈퇴가 취소됐어요. 잠시 후 다시 시도해 주세요";
            default -> "탈퇴 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요";
        };
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

    // 사유는 매치 서비스가 보낸 한국어 문구만 붙인다. USER_WITHDRAWAL 같은 코드 값은 회원에게 보이지 않게 뺀다.
    private static String reasonSuffix(String reason) {
        if (reason == null || reason.isBlank() || CODE_LIKE.matcher(reason.strip()).matches()) {
            return "";
        }
        return " (사유: " + reason.strip() + ")";
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

}
