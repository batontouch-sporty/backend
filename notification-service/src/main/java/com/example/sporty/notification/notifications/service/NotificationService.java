package com.example.sporty.notification.notifications.service;

import com.example.sporty.notification.commons.exception.notifications.NotificationForbiddenException;
import com.example.sporty.notification.commons.exception.notifications.NotificationNotFoundException;
import com.example.sporty.notification.notifications.domain.dto.NotificationReadResponseDto;
import com.example.sporty.notification.notifications.domain.dto.NotificationResponseDto;
import com.example.sporty.notification.notifications.domain.dto.UnreadCountResponseDto;
import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import com.example.sporty.notification.notifications.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;

    // [A25] 내 알림 목록
    public List<NotificationResponseDto> getMyNotifications(Long userId) {
        return notificationRepository.findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(userId)
                .stream()
                .map(NotificationResponseDto::fromEntity)
                .toList();
    }

    // [A26] 알림 읽음 처리
    @Transactional
    public NotificationReadResponseDto markRead(Long userId, Long notificationId) {

        // 1. 알림 조회
        NotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(NotificationNotFoundException::new);

        // 2. 내 알림인지 확인
        if (!notification.getRecipientUserId().equals(userId)) {
            throw new NotificationForbiddenException();
        }

        // 3. 읽음 처리(변경 감지)
        notification.markRead();

        return NotificationReadResponseDto.fromEntity(notification);
    }

    // [A27] 안 읽은 알림 개수
    public UnreadCountResponseDto getUnreadCount(Long userId) {
        return new UnreadCountResponseDto(notificationRepository.countByRecipientUserIdAndReadAtIsNull(userId));
    }

}
