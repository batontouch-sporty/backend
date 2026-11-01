package com.example.sporty.user.withdrawal.consumer;

import com.example.sporty.user.withdrawal.events.KafkaTopics;
import com.example.sporty.user.withdrawal.events.dto.WithdrawalResultEvent;
import com.example.sporty.user.withdrawal.service.WithdrawalSagaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 탈퇴 결과 구독. payload는 JSON 문자열로 받아 직접 읽는다(발행 쪽 타입 헤더와 무관, 모르는 필드 무시).
@Component
@RequiredArgsConstructor
public class WithdrawalResultConsumer {

    private final ObjectMapper objectMapper;
    private final WithdrawalSagaService withdrawalSagaService;

    @KafkaListener(topics = KafkaTopics.WITHDRAWAL_CLEANED, groupId = KafkaTopics.RESULT_GROUP)
    public void onCleaned(String payload) throws JsonProcessingException {
        withdrawalSagaService.handleCleaned(objectMapper.readValue(payload, WithdrawalResultEvent.class));
    }

    @KafkaListener(topics = KafkaTopics.WITHDRAWAL_REJECTED, groupId = KafkaTopics.RESULT_GROUP)
    public void onRejected(String payload) throws JsonProcessingException {
        withdrawalSagaService.handleRejected(objectMapper.readValue(payload, WithdrawalResultEvent.class));
    }

}
