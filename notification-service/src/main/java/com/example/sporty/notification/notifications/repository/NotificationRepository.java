package com.example.sporty.notification.notifications.repository;

import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import com.example.sporty.notification.notifications.domain.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    // [A25] 최근 50건. 같은 시각이면 나중에 저장된 알림이 먼저
    List<NotificationEntity> findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(Long recipientUserId);

    // [A27] 안 읽은 알림 개수
    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

    // 같은 회원·같은 모임에 이 종류 알림이 이미 있는지(시작 전 알림을 모임당 한 번만 저장)
    boolean existsByRecipientUserIdAndTargetMatchIdAndType(Long recipientUserId, Long targetMatchId, NotificationType type);

    // 같은 회원·같은 모임에 본문이 이 문구를 담은 알림이 있는지(취소 알림을 받은 모임인지 확인)
    boolean existsByRecipientUserIdAndTargetMatchIdAndTypeAndBodyContaining(
            Long recipientUserId, Long targetMatchId, NotificationType type, String phrase);

}
