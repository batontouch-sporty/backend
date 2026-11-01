package com.example.sporty.user.withdrawal.events;

// 노션 「API · 이벤트 계약」 Kafka 토픽 이름
public final class KafkaTopics {
    private KafkaTopics() {}

    // 발행: key = userId
    public static final String WITHDRAWAL_REQUESTED = "user.withdrawal-requested.v1";

    // 구독
    public static final String WITHDRAWAL_CLEANED = "match.withdrawal-cleaned.v1";
    public static final String WITHDRAWAL_REJECTED = "match.withdrawal-rejected.v1";

    // 계속 처리하지 못한 메시지는 "<원본 토픽>.DLT"로 보낸다.
    public static final String DLT_SUFFIX = ".DLT";

    // 결과 구독 그룹. notification-service도 rejected를 구독하므로 그룹을 나눈다.
    public static final String RESULT_GROUP = "user-service-withdrawal-v1";
}
