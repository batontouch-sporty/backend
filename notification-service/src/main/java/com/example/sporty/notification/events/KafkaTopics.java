package com.example.sporty.notification.events;

// 구독하는 토픽 이름. 노션 「API · 이벤트 계약」 Kafka 이벤트 표와 같아야 한다.
public final class KafkaTopics {
    private KafkaTopics() {}

    public static final String MATCH_JOINED = "match.joined.v1";
    public static final String MATCH_STATUS_CHANGED = "match.status-changed.v1";
    public static final String MATCH_WITHDRAWAL_REJECTED = "match.withdrawal-rejected.v1";

    // 처리에 계속 실패한 메시지는 "<토픽>.DLT"로 보낸다.
    public static final String DLT_SUFFIX = ".DLT";
}
