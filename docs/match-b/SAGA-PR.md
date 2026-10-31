# #34 R7 매칭 측 탈퇴 Saga

기존 전체 작업은 ../backend에 그대로 보존. 이 브랜치는 최신 main 4c509ec에서 시작했으며 Saga 전용 패키지만 추가했다.

## 포함
- match.withdrawal 패키지: 요청, 처리, R7 저장소, 회원별 잠금, Kafka 수신/재시도/DLT, 결과 Outbox relay
- 공식 R7 match_db 4개 테이블만 사용. URL 컬럼·대기열 테이블 추가 불필요
- match-service/build.gradle: JDBC·Kafka·MariaDB 및 H2 테스트 의존성 추가
- match-service/application.yml: 매칭 전용 DB 연결과 명시적 기능 활성화 플래그 추가

## 제외
- 호성 담당 CRUD/상태 스케줄러, 엔티티·기존 저장소·MatchService 변경 없음
- #11 참가 이관, #32 Feign/CB, #33 참가 이벤트, #35 P1 대기열 제외
- 루트 Compose·공식 DDL·권한 SQL·JWT 필터 변경 없음

## 실행
R7 DDL 초기화 후 MATCH_WITHDRAWAL_ENABLED=true를 매칭 서비스 실행 환경에 설정. MATCH_DB_HOST, MARIADB_HOST_PORT, MATCH_APP_PASSWORD, SPRING_KAFKA_BOOTSTRAP_SERVERS는 팀 환경 사용. 앱은 CREATE/ALTER 없이 DML만 사용한다. 플래그 기본값 false이므로 PR 머지만으로 소비가 시작되지 않는다.

## 동료 연결 지점
- user #21: 결과 eventId와 원본 requestEventId 구분, userId+sagaId+requestEventId 검증
- 알림 #20: 이 Saga가 발행하는 CANCELLED/RECRUITING 상태 이벤트에는 ownerId 포함. 일반 참가의 CLOSED ownerId는 #33 범위
- 동일 match_db 행을 FOR UPDATE로 잠그고 동일 트랜잭션에서 정리/Outbox/processed_events 저장. 기존 참가 로직도 매치 행 잠금을 유지해야 함
- P1 대기열 정리/승급은 이 브랜치에 포함하지 않는다. #35 적용 시 Saga 연결 필요
- #33 통합 Outbox relay 도입 시 Saga relay와 운영 소유권을 통일한다. 이 relay는 Saga 결과와 sagaId가 있는 상태 이벤트만 처리
- 늦은 cleaned의 회원 복구/운영 알림은 user-service 책임. 자동 참가 복구는 계약에 없음

## 검증 한계
H2 테스트의 테이블/제약은 공식 R7 중 match_db 4개 테이블에서 추출했다. JSON만 H2용 CLOB으로 대체. MariaDB 실환경/회원·알림 통합 검증은 별도이며 로컬 단위 테스트를 통합 완료로 표시하지 않는다.

MariaDB 검증은 SAGA_TEST_JDBC가 `/match_saga_r7_review`로 끝나는 전용 임시 DB만 허용한다. 이 환경변수를 설정하면 매 테스트가 해당 DB의 매칭 테이블 4개를 재생성하므로 팀 DB에는 사용하지 않는다.

MariaDB 실행 시도는 Docker 데몬이 실행되지 않아 진행하지 못했다. H2 검증 결과와 MariaDB 통합 결과를 구분한다.

## 최종 확인

- 독립 분리본 테스트 12개, 실패 0, 오류 0
- :match-service:bootJar 및 git diff --check 통과
- Kafka 실제 브로커 통합은 이 분리본에서 미검증. Outbox 발행 순서/실패는 KafkaTemplate 모의 검증
- 원래 작업 폴더의 35개 통합 테스트 결과를 이 분리본 검증으로 혼용하지 않는다
- 별도 브랜치 feat/34/match-withdrawal-saga, 원본 작업은 ../backend에 그대로 보존

## 실제 환경 검증 추가

Docker 기동 후 MariaDB 12개 테스트와 실제 Kafka 송수신·중복·DLT·장애 복구 검증을 완료했다. 위의 Docker 미실행/Kafka 모의 검증 한계는 초기 기록이며, 최신 결과는 [verification.md](verification.md)를 기준으로 한다. 실제 회원·알림 서비스와 팀 전체 Compose 연동은 여전히 별도다.

## CodeRabbit 검토 보완

- DLT는 원본 파티션을 강제하지 않고 -1로 Kafka에 위임한다. 원본 파티션 정보는 DLT 헤더로 확인 가능하다.
- DBA는 R7 적용 후 docs/sql/003_match_outbox_aggregate_index.sql을 추가 적용하고 공식 DDL에도 같은 인덱스를 병합해야 한다. 앱 계정은 DDL을 실행하지 않는다. 두 테스트 스키마에도 같은 정의를 반영했다.
- DB health는 MATCH_WITHDRAWAL_ENABLED(기본 false)에 따른다. 추후 CRUD가 항상 DB를 요구하면 서비스 전체 DB health 정책을 함께 재조정해야 한다.
