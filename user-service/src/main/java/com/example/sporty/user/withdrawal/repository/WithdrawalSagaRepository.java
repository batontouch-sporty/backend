package com.example.sporty.user.withdrawal.repository;

import com.example.sporty.user.withdrawal.domain.entity.SagaStatus;
import com.example.sporty.user.withdrawal.domain.entity.WithdrawalSagaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WithdrawalSagaRepository extends JpaRepository<WithdrawalSagaEntity, String> {

    // 결과 처리와 시간 초과 처리가 같은 Saga를 동시에 바꾸지 않도록 행을 잠그고 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WithdrawalSagaEntity s where s.sagaId = :sagaId")
    Optional<WithdrawalSagaEntity> findForUpdate(String sagaId);

    // 마감(+유예)이 지난 PROCESSING Saga ID. 처리할 때 한 건씩 다시 잠근다.
    @Query("select s.sagaId from WithdrawalSagaEntity s where s.status = :status and s.deadlineAt < :before order by s.deadlineAt")
    List<String> findIdsByStatusAndDeadlineBefore(SagaStatus status, LocalDateTime before, Limit limit);

    // [A28] 상태별 Saga 수 [status, count]
    @Query("select s.status, count(s) from WithdrawalSagaEntity s group by s.status")
    List<Object[]> countGroupByStatus();

}
