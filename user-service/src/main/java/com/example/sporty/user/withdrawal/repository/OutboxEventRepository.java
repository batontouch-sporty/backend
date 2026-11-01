package com.example.sporty.user.withdrawal.repository;

import com.example.sporty.user.withdrawal.domain.entity.OutboxEventEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, String> {

    // 아직 안 보낸 이벤트 ID를 오래된 순서로
    @Query("select o.eventId from OutboxEventEntity o where o.publishedAt is null order by o.createdAt, o.eventId")
    List<String> findUnpublishedIds(Limit limit);

    // 여러 인스턴스가 같은 행을 두 번 보내지 않도록 잠그고 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEventEntity o where o.eventId = :eventId and o.publishedAt is null")
    Optional<OutboxEventEntity> findUnpublishedForUpdate(String eventId);

}
