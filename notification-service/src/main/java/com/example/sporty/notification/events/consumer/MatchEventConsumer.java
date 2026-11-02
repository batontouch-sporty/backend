package com.example.sporty.notification.events.consumer;

import com.example.sporty.notification.events.KafkaTopics;
import com.example.sporty.notification.events.dto.MatchJoinedEvent;
import com.example.sporty.notification.events.dto.MatchStatusChangedEvent;
import com.example.sporty.notification.events.dto.StartingSoonEvent;
import com.example.sporty.notification.events.dto.WaitlistPromotedEvent;
import com.example.sporty.notification.events.dto.WithdrawalRejectedEvent;
import com.example.sporty.notification.events.service.NotificationEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// payload는 JSON 문자열로 받아 직접 읽는다. 발행 쪽 클래스 이름(타입 헤더)에 묶이지 않기 위해서다.
@Component
@RequiredArgsConstructor
public class MatchEventConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationEventService notificationEventService;

    @KafkaListener(topics = KafkaTopics.MATCH_JOINED)
    public void onMatchJoined(String payload) throws JsonProcessingException {
        notificationEventService.handleMatchJoined(objectMapper.readValue(payload, MatchJoinedEvent.class));
    }

    @KafkaListener(topics = KafkaTopics.MATCH_STATUS_CHANGED)
    public void onMatchStatusChanged(String payload) throws JsonProcessingException {
        notificationEventService.handleMatchStatusChanged(objectMapper.readValue(payload, MatchStatusChangedEvent.class));
    }

    @KafkaListener(topics = KafkaTopics.MATCH_WITHDRAWAL_REJECTED)
    public void onWithdrawalRejected(String payload) throws JsonProcessingException {
        notificationEventService.handleWithdrawalRejected(objectMapper.readValue(payload, WithdrawalRejectedEvent.class));
    }

    @KafkaListener(topics = KafkaTopics.MATCH_WAITLIST_PROMOTED)
    public void onWaitlistPromoted(String payload) throws JsonProcessingException {
        notificationEventService.handleWaitlistPromoted(objectMapper.readValue(payload, WaitlistPromotedEvent.class));
    }

    @KafkaListener(topics = KafkaTopics.MATCH_STARTING_SOON)
    public void onStartingSoon(String payload) throws JsonProcessingException {
        notificationEventService.handleStartingSoon(objectMapper.readValue(payload, StartingSoonEvent.class));
    }

}
