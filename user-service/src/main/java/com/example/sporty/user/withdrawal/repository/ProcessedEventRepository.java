package com.example.sporty.user.withdrawal.repository;

import com.example.sporty.user.withdrawal.domain.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
}
