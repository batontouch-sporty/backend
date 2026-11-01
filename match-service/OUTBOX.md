# 참가 이벤트와 공용 Outbox (#33)

참가·모집 상태 변경과 `outbox_event` 저장은 하나의 match_db 트랜잭션이다.
참가 트랜잭션은 Kafka에 직접 요청하지 않는다. Outbox 저장에 실패하면 참가 기록과
모집 상태도 롤백하며, Kafka 장애 중에는 업무를 커밋하고 발행을 재시도한다.

## 이벤트 계약

- `match.joined.v1`: `eventId`, `matchId`, `title`, `userId`, `ownerId`, `current`, `max`.
  OWNER를 포함한 현재 인원이며 참가자와 방장에게 알림을 만든다.
- `match.status-changed.v1`: `eventId`, `matchId`, `title`, `from`, `to`, `ownerId`,
  `participantIds`, `reason`. 정원 도달은 RECRUITING → CLOSED, 시작 전 빈자리 발생은
  CLOSED → RECRUITING이다. 알림 서비스는 CLOSED를 방장에게 알리고 RECRUITING은
  처리 기록만 남긴다. 참가 취소 자체의 알림은 발행하지 않는다.
- 일반 참가 이벤트의 Kafka key와 aggregate_id는 matchId다. 기존 Saga 결과의 key는
  userId를 유지한다. 기존 Saga 상태 이벤트의 sagaId 필드도 유지한다.

## 발행과 재시도

기존 `WithdrawalOutboxRelay`를 공용 발행기로 확장했다. 호환성을 위해 클래스명과
`withdrawalKafkaTemplate` Bean 이름은 유지한다. 별도 참가 발행기를 만들지 않는다.
공용 producer·스케줄링은 `MatchOutboxConfiguration`이 제공하며 Saga 수신기는 기존
`match.withdrawal.enabled`로 별도 제어한다.

- `match.outbox.enabled`: 기본 true. false여도 업무 이벤트 저장은 계속하고 발행만 중지한다.
- `match.outbox.delay-ms`: 기본 1초. 미설정 시 기존 `match.withdrawal.outbox-delay-ms`를 사용한다.
- 지원 토픽: joined, status-changed, withdrawal-cleaned, withdrawal-rejected의 v1.
- 미발행 행을 잠그고 Kafka ACK 후에만 published_at을 기록한다.
- Kafka 발행 실패 시 미발행 상태를 유지하며 동일 eventId·payload로 재시도한다.
- Kafka 전송 성공 뒤 DB 표시 실패가 가능하므로 전달은 at-least-once다. 알림 서비스의
  processed_events 및 (event_id, recipient_user_id, type) 고유 제약으로 중복을 막는다.

R7의 기존 outbox_event 테이블을 사용하며 DDL·권한·다른 서비스 코드는 변경하지 않는다.
#10이 같은 토픽을 저장할 때도 이 발행기를 사용한다. 대기열 승급 토픽은 #35에서 추가한다.

## 검증

회귀 테스트에서 joined·CLOSED 필드, 모집 재개, 일반 참가 취소의 불필요한 이벤트 방지,
상위 트랜잭션 롤백 및 Outbox 저장 실패 시 업무 롤백을 확인한다. 기존 발행기의 ACK 순서,
발행 실패·표시 실패 재시도 테스트도 함께 실행한다.

테스트의 match.outbox.enabled=false는 외부 브로커를 쓰지 않는 기본 테스트용이다.
실제 전달과 알림 중복 방지는 전용 Compose의 실제 회원·매칭·알림·MariaDB·Kafka로 확인한다.

Saga 비활성 상태에서 Kafka 중단 중 참가 201·CLOSED 커밋과 미발행 2건 보존을 확인했다.
재시도 중 eventId·payload가 유지됐고, 브로커 복구 후 참가자·방장 JOINED 2건과
방장 모집 마감 알림 1건이 생성됐다. 두 이벤트 재전송 후에도 알림은 총 3건이었다.
참가 취소의 모집 재개 이벤트는 수신 처리되지만 추가 알림을 만들지 않는 것도 확인했다.
Saga를 다시 활성화한 뒤 실제 Kafka 요청을 수신하고 요청 eventId·sagaId가 일치하는
CLEANED 결과가 같은 공용 발행기로 ACK·발행 완료 처리되는 것도 확인했다.
