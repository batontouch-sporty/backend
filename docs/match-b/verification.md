# #34 실제 MariaDB·Kafka 검증 (2026-10-31)

## 환경

- 대상 브랜치 feat/34/match-withdrawal-saga, 코드 커밋 67760c3
- MariaDB 11.4: 전용 sporty-saga-r7-review, 호스트 33346
- Kafka broker 4.3.1: 전용 sporty-saga-r7-kafka, 호스트 39102. 앱 Kafka client 3.9.1
- 참고: 팀 루트 Compose의 Kafka 이미지는 3.9.1이므로 이번 broker 버전은 다름
- 실제 Spring Boot 앱: localhost:18086, Saga 활성화, Eureka/Config는 비활성화
- 공식 R7 match_db 4개 테이블만 사용. URL/대기열/다른 서비스 테이블 없음
- runtime은 match_app, 자기 테스트 DB의 SELECT/INSERT/UPDATE/DELETE만 허용
- 기존 팀/이전 검증 DB·컨테이너는 기동·수정하지 않음

## 결과

1. 전용 MariaDB에서 12개 테스트 통과(실패·오류 0). 공식 R7의 JSON·CHECK·FK·고유 제약 사용. 동시 중복, 진행 중 방장 거절, 마감 초과, 커밋 전 롤백, 완료/취소 이력 유지 포함.
2. 실제 앱의 /actuator/health 200 및 match_app DB 연결 확인.
3. runtime CREATE TABLE 및 다른 DB SELECT 거절 확인.
4. Kafka 요청 → 방장 미래 모임 CANCELLED, 일반 참가 제거, 정원 여유 시 RECRUITING 확인.
5. cleaned Kafka payload의 sagaId/requestEventId/userId 및 cleanedAt 오프셋 확인.
6. 동일 Kafka 요청 재전송 → processed_events와 결과 이벤트 중복 없음.
7. 실제 rejected 토픽에 OWNER_OF_IN_PROGRESS_MATCH, DEADLINE_EXCEEDED 확인.
8. CANCELLED/RECRUITING 상태 이벤트 ownerId 값 확인.
9. invalid-json 실제 수신 → 앱 로그상 3회 재시도 → 원본 payload가 DLT로 전달됨.
10. 전용 Kafka 중단 → 미발행 Outbox 유지 → Kafka 복구 후 자동 발행. 원본 eventId/requestEventId 유지.

## 검증 범위

회원 요청은 테스트 발행기가 보냈으며 결과는 테스트 소비자가 읽었다. 실제 user-service의 WITHDRAWING/WITHDRAWN/ACTIVE 전이 및 5분30초 타임아웃, notification-service의 알림 DB 저장은 아직 검증하지 않았다. 일반 참가 CLOSED ownerId(#33), P1 대기열(#35), 팀 전체 루트 Compose도 별도 검증 대상이다.

## 재현

단위 테스트: `./gradlew :match-service:test :match-service:bootJar`.

MariaDB 테스트는 별도 전용 DB에서만 `SAGA_TEST_JDBC=jdbc:mariadb://localhost:33346/match_saga_r7_review`, `SAGA_TEST_DB_PASSWORD`를 설정한 뒤 `:match-service:test --rerun-tasks`를 실행한다. 이 테스트는 전용 DB의 테이블 4개를 매번 재생성하므로 팀 DB에서 실행하지 않는다.

실제 브로커 검증용 스크립트·상세 로그는 로컬 C:/inspire_6th/work/에 보존했다. 기존 코드 변경 없이 검증 통과했으며 제품 코드 수정은 이번 검증에서 필요하지 않았다.

## CodeRabbit 보완 검증

- 수정 후 실제 MariaDB·Kafka 환경 테스트 17개, 실패 0, 오류 0 및 bootJar 통과
- 원본 ConsumerRecord partition=7을 한 파티션 DLT로 보낼 때 Kafka가 partition=0을 선택하는 실제 전송 확인
- 기본 Saga 비활성화 시 dbHealthContributor 없음, 활성화 시 있음: 실제 application.yml을 읽는 구성 테스트 2개
- H2/MariaDB 테스트 스키마의 aggregate_id 인덱스를 메타데이터로 확인
- 공식 R7 DDL 원본은 덮어쓰지 않음. DBA가 docs/sql/003_match_outbox_aggregate_index.sql을 추가 적용하고 공식 스키마에 병합해야 함
- 일반 CI는 외부 Docker가 필요 없는 테스트를 실행하고, WithdrawalDltBrokerTest는 전용 SAGA_TEST_BROKER 설정이 없으면 생략

## 두 번째 리뷰 재검증

MATCH_WITHDRAWAL_ENABLED=true 환경에서 비활성 테스트는 false를 명시하도록 수정. 실제 MariaDB·Kafka 포함 17개 테스트 실패/오류 0, bootJar 및 diff 검사 통과. 트랜잭션 원자성, 커밋 직전 마감 초과 롤백, 요청 ID·Saga ID 연결, 동시 중복 수신, 이력 보존, Outbox ACK/실패/중복 발행 방지, DLT 파티션 경로를 재검증했다. 추가 재현된 기능 오류는 없으며 서비스 간 통합 한계는 위 기록과 동일하다. DLT 개명 제안은 R7의 명시적 `<토픽>.DLT` 계약에 따라 미반영했다.
