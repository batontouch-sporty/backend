# 포인트 보증금 Saga (R10)

## 적용 기준

- 포인트 서비스 v4.0, 연결된 포인트 API 명세, 11/02 서우영·최환우 Kafka 계약 기준.
- PR #111의 R10 DDL 및 user-service의 포인트 코드와 함께 반영한다. 이 브랜치만 R8 DB에 배포하지 않는다.
- 포인트 자체의 차감·반환·정산·정지 해제는 user-service 책임이다. 출석 수정·종료 정산 발행은 매칭 A 담당이며 이 변경에서는 구현하지 않는다.
- 외부 설정은 config-repo `match-service.yml`에서 관리한다. `match.deposit.enabled` 기본 true, 요청 만료 기본 5분, 만료·빈자리 재시도 기본 10초. 시간 값은 운영 설정이며 포인트 계약에 새로운 업무 제한을 추가하지 않는다.
- 테스트의 `match.deposit.enabled=false`는 기존 API 회귀 검증용이다. R10 운영에서는 true를 사용한다. false로 해도 R10 컬럼 매핑이 있으므로 R8 호환 배포 모드는 아니다.

## API 변경

- A9 생성, A14 참가: HTTP 202 `{requestId, matchId, status:"PENDING"}`. 방장 requestId는 matches.creation_request_id와 같다.
- `GET /api/matches/requests/{requestId}`: 본인 요청만 200. 다른 회원/없는 요청 404 REQUEST_NOT_FOUND, 미인증 401.
- 상태 응답: requestId, matchId, requestType(CREATE/JOIN/PROMOTION), status(PENDING/CONFIRMED/FAILED/CANCELLED), refundStatus(NOT_REQUIRED/PENDING/COMPLETED), failureCode, failureMessage.
- A15: 시작 전 취소만 204. CANCELLED 변경과 반환 Outbox를 같은 트랜잭션으로 저장한다. 반환 완료를 기다리지 않는다. 시작 후 400 MATCH_ALREADY_STARTED.
- A13/A17: 취소 알림 대상을 먼저 확정하고, PENDING/CONFIRMED 요청별 MATCH_CANCELLED 반환을 기록한다.
- 생성 대기·실패 매치는 공개 검색/상세에서 제외한다. 정원·참가 목록·방장 권한·알림·리마인더·출석 대상·내 매치·참가 통계는 CONFIRMED 기준이다. 관리자 매치 상태 집계에는 생성 대기·실패 상태도 포함된다.
- I1 기존 필드에 participationSuspended(boolean), suspendedUntil(KST LocalDateTime/null)을 추가해 사용한다. 신규 생성·참가·대기·승급 시 회원 활동 가능 여부와 정지를 검사한다. 실제 차감 시 user-service가 회원 행 잠금 안에서 다시 검사한다.

## Kafka

공통 필드: eventId(UUID), requestId(UUID), userId, matchId, occurredAt(오프셋 포함). key는 userId 문자열.

- 발행 `match.deposit-charge-requested.v1`: requestType CREATE/JOIN/PROMOTE.
- 발행 `match.deposit-refund-requested.v1`: reason CONFIRMATION_FAILED/PARTICIPANT_CANCELLED/MATCH_CANCELLED.
- 수신 `user.deposit-charged.v1`: 차감 성공 후 매치·요청 잠금 안에서 상태·마감·정원을 다시 확인한다.
- 수신 `user.deposit-charge-rejected.v1`: reason INSUFFICIENT_POINTS/PARTICIPATION_SUSPENDED/REQUEST_CANCELLED/USER_NOT_ACTIVE. 실패 요청은 확정하지 않는다.
- 수신 `user.deposit-refund-completed.v1`: outcome RETURNED/CANCELLED_BEFORE_CHARGE, creditedPoints(0~10 정수). 차감 전 취소는 0.
- 수신 `user.participation-suspended.v1`: suspendedUntil(오프셋 포함). 기존 대기 등록만 삭제한다. 확정 참가·방장 매치는 유지한다.
- 매칭 A용 `match.attendance-settlement-requested.v1`도 공통 Outbox 발행 허용 목록에 포함했다. 확정 요청별 정산 이벤트 생성 로직은 매칭 A에서 연결한다.
- 결과 소비 groupId `match-deposit-v1`. 실패 시 1초 간격 3회 재시도 후 `<원본 토픽>.DLT`. DLT 발행 실패는 소비 완료로 처리하지 않는다.
- 기존 참가·상태·승급·리마인더 알림 이벤트 계약과 key(matchId)는 유지한다. 새 보증금 이벤트만 userId key를 사용한다.

## 영속 상태와 잠금

- 새 Saga 테이블 없이 기존 Outbox에 내부 요청 상태를 저장한다. event_id=requestId, event_type=`match.deposit-request.state`, aggregate_id=userId이다. 내부 타입은 Kafka 발행 허용 목록에서 제외한다. 이 타입에 한해 published_at은 참가 요청의 대기/종결 표식으로 사용하여 기존 미발행 인덱스로 PENDING 요청만 조회한다. 일반 업무 이벤트의 published_at은 기존대로 발행 완료 시각이다.
- 내부 payload는 요청 종류·상태·반환 상태·실패 사유·마감 시각·차감/반환 처리 여부를 보존한다. 이 내부 행은 외부 Kafka 이벤트가 아니다. 변경은 매치 잠금 안에서 수행한다.
- 실제 차감·반환 이벤트는 별도 eventId를 발급해 같은 DB 트랜잭션에 저장하며 payload를 수정하지 않는다. 재발행은 기존 eventId를 유지한다.
- 내부 요청 행과 발행 완료된 업무 이벤트를 삭제하면 이전 요청 조회·중복 방지·지연 보상이 깨진다. 보존 정책 적용 전에 별도 영속 저장소로 이전해야 한다.
- 재신청은 기존 참가자 행에 새 requestId를 넣고 PENDING으로 바꾼다. 이전 요청 상태는 별도 내부 Outbox 행으로 남는다. 이전 요청 결과는 새 요청 상태를 바꾸지 않는다.
- 일반 작업/결과 소비는 회원 advisory lock → 매치 행 → 요청/참가 행 순서다. 정원과 수신자 목록은 잠금 조회로 읽어 MariaDB REPEATABLE READ의 오래된 스냅샷을 피한다.
- 만료는 먼저 CANCELLED를 저장하고 동일 트랜잭션에 반환/취소 결정 이벤트를 남긴다. 늦은 성공은 참가를 확정하지 않는다. user-service는 취소 선도착 시 DEPOSIT_CANCELLED를 기록한다.
- 대기 승급은 취소 커밋 후 별도 트랜잭션에서 실행한다. 회원 잠금은 즉시 획득만 시도하고, 회원 조회 장애/잠금 경합은 기존 취소에 영향을 주지 않는다. 10초 기본 유지보수 작업이 남은 빈자리를 재시도한다. 승급 후보가 차감 거절되면 다음 후보를 재시도한다.
- 회원 탈퇴는 기존 Saga를 유지한다. 탈퇴 회원의 보증금은 반환하지 않으며 해당 요청을 취소해 늦은 확정을 막는다. 탈퇴 방장의 모임이 취소되면 다른 회원의 요청은 반환한다. 참가 기록은 삭제하지 않는다.

## 검증 구분

2026-11-03 최종 실행: 전체 163개 중 151개 통과, 기존 외부 연결 선택 테스트 12개 제외, 실패/오류 0. `:match-service:bootJar` 통과. 이 실행에서 `DepositSagaTest` 19개는 공식 R10 MariaDB 11.4와 CRUD 전용 계정 및 실제 advisory lock을 사용했고, `DepositKafkaBrokerTest` 1개는 별도 Kafka 3.9.1을 사용했다. 두 임시 컨테이너는 검증 후 종료했으며 팀 DB는 변경하지 않았다.

- `DepositSagaTest`: 202·공개 제외·확정·거절·취소 선도착·지연·중복·재신청·정원 경합·승급·조회 권한·탈퇴 공존.
- 기본은 H2. `DEPOSIT_TEST_JDBC=jdbc:mariadb://127.0.0.1:33485/match_db`, `DEPOSIT_TEST_PASSWORD`를 지정하면 공식 R10을 미리 적용한 격리 DB에서 validate 및 CRUD 전용 계정으로 실행한다. 다른 DB 주소는 거절한다.
- `DepositKafkaBrokerTest`: `DEPOSIT_TEST_BROKER=localhost:39485`일 때만 실행. 실제 Outbox→Kafka, 결과 수신→확정, 취소→반환 완료, 잘못된 이벤트→DLT 확인.
- 이 테스트의 user-service 응답은 계약에 맞춘 테스트 이벤트다. 우영·성민 담당 코드가 준비된 후 실제 회원/포인트 서비스와 Gateway·프론트까지 연결하는 통합 검증이 필요하다.
