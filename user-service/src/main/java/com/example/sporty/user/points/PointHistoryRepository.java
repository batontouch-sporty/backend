package com.example.sporty.user.points;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {
    List<PointHistory> findByRequestId(String requestId);
    Page<PointHistory> findByUserIdOrderByCreatedAtDescIdDesc(long userId, Pageable pageable);
}
