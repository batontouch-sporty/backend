package com.example.sporty.notification.events.service;

import com.example.sporty.notification.events.KafkaTopics;
import com.example.sporty.notification.events.domain.entity.ProcessedEventEntity;
import com.example.sporty.notification.events.dto.MatchJoinedEvent;
import com.example.sporty.notification.events.dto.MatchStatusChangedEvent;
import com.example.sporty.notification.events.dto.StartingSoonEvent;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    // 취소 알림 본문에 들어가는 문구. 시작 전 알림을 건너뛸 때 이 문구로 취소 알림을 찾으므로 바꿀 때 함께 확인한다.
    private static final String CANCELLED_PHRASE = "모임이 취소됐어요";
    // 모임 시각은 서울 기준으로 보여 준다(매칭 서비스 start_at도 서울 기준).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M월 d일");

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

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
            case "CANCELLED" -> quote(event.title()) + CANCELLED_PHRASE + reasonSuffix(event.reason());
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

    // match.starting-soon.v1 → 확정 참가자 전원(방장 포함)에게 시작 전 알림.
    // 같은 모임 알림은 회원당 한 번만, 취소 알림을 이미 받은 모임은 건너뛴다.
    @Transactional
    public void handleStartingSoon(StartingSoonEvent event) {
        requireEventId(event.eventId());
        if (alreadyProcessed(event.eventId())) {
            return;
        }

        // 모임·시각을 알 수 없거나 이미 시작한 모임이면 다시 받아도 같으므로 재시도하지 않고 기록만 남긴다.
        List<NotificationEntity> notifications = new ArrayList<>();
        ZonedDateTime startAt = parseStartAt(event.startAt());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(SEOUL);
        if (!isPositive(event.matchId()) || startAt == null) {
            log.warn("matchId·startAt이 올바르지 않은 시작 전 알림 이벤트라 알림을 보내지 않음: eventId={}, matchId={}, startAt={}",
                    event.eventId(), event.matchId(), event.startAt());
        } else if (!startAt.isAfter(now)) {
            log.info("이미 시작한 모임이라 시작 전 알림을 보내지 않음: eventId={}, matchId={}, startAt={}",
                    event.eventId(), event.matchId(), event.startAt());
        } else {
            String body = quote(event.title()) + "모임이 " + dayLabel(startAt.toLocalDate(), now.toLocalDate())
                    + " " + startAt.format(HOUR_MINUTE) + "에 시작해요";
            List<Long> recipients = event.participantIds() == null ? List.of() : event.participantIds();
            for (Long recipient : new LinkedHashSet<>(recipients)) {
                if (recipient != null && shouldRemind(recipient, event.matchId())) {
                    notifications.add(build(event.eventId(), recipient, NotificationType.STARTING_SOON,
                            event.title(), body, event.matchId()));
                }
            }
        }

        saveAll(event.eventId(), KafkaTopics.MATCH_STARTING_SOON, notifications);
    }

    // 이미 같은 모임 시작 전 알림을 받았거나, 그 모임 취소 알림을 받았으면 보내지 않는다.
    // 매칭 서비스는 이벤트를 저장한 뒤 모임이 취소돼도 이벤트를 거둬들이지 않는다.
    private boolean shouldRemind(Long recipient, Long matchId) {
        if (notificationRepository.existsByRecipientUserIdAndTargetMatchIdAndType(
                recipient, matchId, NotificationType.STARTING_SOON)) {
            return false;
        }
        if (notificationRepository.existsByRecipientUserIdAndTargetMatchIdAndTypeAndBodyContaining(
                recipient, matchId, NotificationType.STATUS_CHANGED, CANCELLED_PHRASE)) {
            log.info("취소된 모임이라 시작 전 알림을 보내지 않음: userId={}, matchId={}", recipient, matchId);
            return false;
        }
        return true;
    }

    // 오프셋이 있는 시각만 받는다(계약). 서울 시각으로 바꿔 날짜·시간을 보여 준다.
    private static ZonedDateTime parseStartAt(String startAt) {
        if (startAt == null || startAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(startAt.strip()).atZoneSameInstant(SEOUL);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // 24시간 이내 모임은 보통 오늘·내일이다. 늦게 도착하는 등 그 밖이면 날짜를 그대로 쓴다.
    private static String dayLabel(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return "오늘";
        }
        if (day.equals(today.plusDays(1))) {
            return "내일";
        }
        return day.format(MONTH_DAY);
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
