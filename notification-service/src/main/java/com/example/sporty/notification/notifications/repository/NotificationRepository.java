package com.example.sporty.notification.notifications.repository;

import com.example.sporty.notification.notifications.domain.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    // [A25] 최근 50건. 같은 시각이면 나중에 저장된 알림이 먼저
    List<NotificationEntity> findTop50ByRecipientUserIdOrderByCreatedAtDescIdDesc(Long recipientUserId);

    // [A27] 안 읽은 알림 개수
    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

}
