# 대기열 및 자동 승급 (#35)

## API와 이벤트

- POST `/api/matches/{matchId}/waitlist`: 201, `matchId`, `userId`, `position`, `registeredAt` 반환. 등록 시점의 순번이며 시간은 서울 기준이다.
- DELETE `/api/matches/{matchId}/waitlist/me`: 204. 본인의 대기 기록만 삭제한다.
- Gateway가 전달한 X-User-Id를 사용한다. 시작 전 실제 정원이 찬 CLOSED 매치만 등록할 수 있다. 회원 활동 상태·성별을 확인하며 방장·기존 참가자·중복 대기는 거절한다.
- `match.waitlist-promoted.v1`: key는 matchId, payload는 `eventId`, `matchId`, `title`, `userId` 네 필드다. 승급은 별도 joined 이벤트를 만들지 않는다.

## 원자성과 잠금

등록·대기 취소는 회원 잠금 → 매치 행 잠금 순서다. 일반 참가 취소와 탈퇴 Saga의 참가 정리는 같은 트랜잭션에서 빈자리를 승급으로 채운다. 순서는 registered_at, id 오름차순이다. 승급 후에도 정원이 차 있으면 CLOSED를 유지하고, 빈자리가 남아야 기존 모집 재개 로직을 실행한다.

승급은 매치 행 잠금 안에서 후보 회원 잠금을 즉시 시도한다. 후보가 다른 작업 중이면 409 MEMBER_OPERATION_IN_PROGRESS로 일반 참가 취소 전체를 롤백한다. 매치 잠금을 가진 채 후보 회원 잠금을 기다리는 교착을 피하고, 앞사람을 건너뛰지 않기 위한 처리다. Saga에서는 예외가 전파되어 기존 소비 재시도 정책을 따른다.

확정적으로 탈퇴·비활성·성별 부적합인 후보는 대기열에서 제거하고 다음 후보를 검사한다. 회원 서비스 장애·타임아웃은 후보를 제거하지 않고 참가 취소·승급·이벤트 저장 전체를 롤백한다. 승급 자격 확인은 원격 호출이므로 매치 잠금 유지 시간에 영향을 준다. Outbox 저장 실패도 전체 롤백한다.

매치의 soft cancel은 대기 기록을 유지하며 CANCELLED·COMPLETED에서는 일반 대기 취소를 400 WAITLIST_CANCEL_NOT_ALLOWED로 거절한다. 매치 hard delete는 공식 FK CASCADE로 대기 기록을 제거한다. 회원 탈퇴 Saga는 진행 중 방장 매치로 인한 거절 여부를 먼저 판단하고, 탈퇴를 수용할 때 해당 회원의 대기 기록도 정리한다.

## DB와 통합 범위

공식 R8 `docker/mariadb/schema.sql`의 match_waitlist를 사용한다. 서비스 간 FK를 추가하지 않는다. DDL·권한·Compose는 변경하지 않는다. 사용자별 중복은 (match_id, user_id) 고유 제약으로도 방지한다.

알림 서비스의 P1 승급 이벤트 수신·알림 저장은 알림 담당자의 후속 작업이다. 이 변경은 매칭 API·자동 승급·Saga 연결·Outbox/Kafka 발행까지 담당한다. 알림함까지의 통합 검증은 수신부 연결 후 진행하므로 이 PR은 Refs #35로 연결한다.

## 검증

H2 테스트로 등록·취소·순번, 동시 중복 등록, 동일 시각 FIFO, 자격 미달 후보 건너뛰기, 의존 서비스 실패·잠금 경합·Outbox 실패 롤백, soft cancel/hard delete, 탈퇴 Saga 정리·승급·중복 이벤트를 확인한다. 매칭 전체 테스트와 bootJar 빌드를 함께 실행한다.

공식 R8 DDL과 DML 전용 앱 계정, ddl-auto=validate를 사용하는 별도 Compose 환경에서 실제 Gateway·회원·매칭·MariaDB·Kafka를 연결해 검증한다. 테스트용 매치는 DB에 준비하며 회원 가입·대기 등록·참가 취소·회원 탈퇴는 실제 API를 사용한다. 알림 서비스 수신은 이 검증 범위에 포함되지 않는다.

2026-11-01 공식 R8 환경의 실서비스 검증 37항목을 통과했다. 실제 탈퇴가 CLEANED로 완료되고 대기 기록 정리 및 다음 대기자 승급이 반영됐으며, Outbox에 저장한 eventId와 같은 이벤트를 Kafka에서 확인했다. 매칭 전체 테스트는 104건 중 92건 성공·명시적 실행 조건이 있는 12건 제외·실패 0건이고 bootJar 빌드도 통과했다.
