package com.example.sporty.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.sporty.notification.commons.config.AuthHeaders;
import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import com.example.sporty.notification.notifications.domain.entity.NotificationType;
import com.example.sporty.notification.notifications.repository.NotificationRepository;

/**
 * 알림함 API(A25~A27) 계약을 확인한다.
 * Gateway 대신 X-User-Id 헤더를 직접 넣어 부른다. 테스트마다 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationApiTest {
    static final long ME = 1L;
    static final long OTHER = 2L;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired NotificationRepository notifications;

    // A25 내 알림 목록
    @Test
    void listsOnlyMyNotificationsNewestFirstWithContractFields() throws Exception {
        NotificationEntity older = save(ME, NotificationType.JOINED, 57L);
        NotificationEntity newer = save(ME, NotificationType.WITHDRAWAL_REJECTED, null);
        save(OTHER, NotificationType.JOINED, 57L);
        newer.markRead();
        reload();

        mvc.perform(get("/api/notifications/me").header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer.getId()))
                .andExpect(jsonPath("$[0].type").value("WITHDRAWAL_REJECTED"))
                .andExpect(jsonPath("$[0].matchId").doesNotExist())
                .andExpect(jsonPath("$[0].read").value(true))
                .andExpect(jsonPath("$[1].id").value(older.getId()))
                .andExpect(jsonPath("$[1].type").value("JOINED"))
                .andExpect(jsonPath("$[1].message").value("본문 " + older.getEventId()))
                .andExpect(jsonPath("$[1].matchId").value(57))
                .andExpect(jsonPath("$[1].read").value(false))
                // 명세 예시처럼 시간대·소수점 없는 초 단위
                .andExpect(jsonPath("$[1].createdAt").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")))
                // DB 컬럼 이름은 응답에 나오지 않는다
                .andExpect(jsonPath("$[1].body").doesNotExist())
                .andExpect(jsonPath("$[1].readAt").doesNotExist());
    }

    @Test
    void listsAtMostFiftyNotifications() throws Exception {
        for (int i = 0; i < 55; i++) save(ME, NotificationType.STATUS_CHANGED, 1L);
        reload();
        mvc.perform(get("/api/notifications/me").header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50));
    }

    @Test
    void emptyInboxReturnsEmptyList() throws Exception {
        mvc.perform(get("/api/notifications/me").header(AuthHeaders.USER_ID, 999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // A26 읽음 처리
    @Test
    void markReadReturnsReadTrueAndKeepsFirstReadTime() throws Exception {
        NotificationEntity notification = save(ME, NotificationType.JOINED, 3L);
        reload();
        mvc.perform(patch("/api/notifications/{id}/read", notification.getId()).header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notification.getId()))
                .andExpect(jsonPath("$.read").value(true));
        reload();
        var firstReadAt = notifications.findById(notification.getId()).orElseThrow().getReadAt();
        assertThat(firstReadAt).isNotNull();

        // 다시 눌러도 200, 처음 읽은 시각은 그대로
        mvc.perform(patch("/api/notifications/{id}/read", notification.getId()).header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        reload();
        assertThat(notifications.findById(notification.getId()).orElseThrow().getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void cannotReadAnotherUsersNotification() throws Exception {
        NotificationEntity notification = save(OTHER, NotificationType.JOINED, 3L);
        reload();
        mvc.perform(patch("/api/notifications/{id}/read", notification.getId()).header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_FORBIDDEN"));
        reload();
        assertThat(notifications.findById(notification.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void unknownOrMalformedNotificationId() throws Exception {
        mvc.perform(patch("/api/notifications/{id}/read", 999999).header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        mvc.perform(patch("/api/notifications/abc/read").header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // A27 안 읽은 개수
    @Test
    void unreadCountCountsOnlyMyUnreadNotifications() throws Exception {
        save(ME, NotificationType.JOINED, 1L);
        save(ME, NotificationType.STATUS_CHANGED, 1L);
        save(ME, NotificationType.STATUS_CHANGED, 2L).markRead();
        save(OTHER, NotificationType.JOINED, 1L);
        reload();
        mvc.perform(get("/api/notifications/me/unread-count").header(AuthHeaders.USER_ID, ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void requestsWithoutUserIdHeaderAreUnauthorized() throws Exception {
        mvc.perform(get("/api/notifications/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/notifications/me/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/notifications/1/read")).andExpect(status().isUnauthorized());
    }

    private NotificationEntity save(long recipient, NotificationType type, Long matchId) {
        String eventId = UUID.randomUUID().toString();
        return notifications.save(NotificationEntity.builder().eventId(eventId).recipientUserId(recipient)
                .type(type).title("제목").body("본문 " + eventId).targetMatchId(matchId).build());
    }

    private void reload() { em.flush(); em.clear(); }
}
