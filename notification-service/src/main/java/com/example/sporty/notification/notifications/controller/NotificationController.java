package com.example.sporty.notification.notifications.controller;

import com.example.sporty.notification.commons.config.AuthHeaders;
import com.example.sporty.notification.notifications.domain.dto.NotificationReadResponseDto;
import com.example.sporty.notification.notifications.domain.dto.NotificationResponseDto;
import com.example.sporty.notification.notifications.domain.dto.UnreadCountResponseDto;
import com.example.sporty.notification.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    // [A25] 내 알림 목록
    @GetMapping("/me")
    public ResponseEntity<List<NotificationResponseDto>> getMyNotifications(
            @RequestHeader(AuthHeaders.USER_ID) Long userId) {
        return ResponseEntity.ok(notificationService.getMyNotifications(userId));
    }

    // [A26] 알림 읽음 처리
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<NotificationReadResponseDto> markRead(
            @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("notificationId") Long notificationId) {
        return ResponseEntity.ok(notificationService.markRead(userId, notificationId));
    }

    // [A27] 안 읽은 알림 개수
    @GetMapping("/me/unread-count")
    public ResponseEntity<UnreadCountResponseDto> getUnreadCount(
            @RequestHeader(AuthHeaders.USER_ID) Long userId) {
        return ResponseEntity.ok(notificationService.getUnreadCount(userId));
    }

}
