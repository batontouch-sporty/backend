## 관련 이슈

Refs #34

## 변경 내용

매칭 측 R7 탈퇴 Saga만 별도 패키지로 분리했습니다. 회원 요청을 받아 진행 중 방장 여부와 마감 시각을 검사하고, R7 match_db의 모임·참가 기록 정리와 결과 Outbox·중복 처리 기록을 같은 트랜잭션에 저장합니다.

- 요청 requestedAt/deadlineAt, 결과 requestEventId/cleanedAt/rejectedAt
- 커밋 직전 마감 초과 시 정리와 중간 이벤트 롤백 후 DEADLINE_EXCEEDED 결과만 저장
- 같은 이벤트 동시 수신 시 정리·결과 중복 방지
- Saga 상태 변경 이벤트에 ownerId 포함
- Saga 전용 Kafka 수신/3회 재시도/DLT 및 Outbox 발행

## 다른 서비스 영향

- 기존 CRUD·엔티티·MatchService·루트 Compose·공식 DDL 변경 없음
- match-service 빌드 의존성과 DB 연결/활성화 설정만 추가
- 기본 비활성화. 공식 R7 DDL 준비 후 MATCH_WITHDRAWAL_ENABLED=true로 활성화
- 회원 #21, 알림 #20과 실제 연동 검증 필요
- 일반 참가 CLOSED 이벤트 ownerId는 #33에서 처리, 대기열 정리·승급은 #35에서 연결
- #33 통합 Outbox relay 적용 시 발행 담당을 통일할 것

## 테스트 결과

- 단위 및 실제 MariaDB 기반 테스트 12개 통과, 빌드 통과
- DML-only match_app + 실제 Kafka 요청/결과/중복/3회 재시도 후 DLT 검증
- Kafka 중단·복구 후 Outbox 자동 발행 및 이벤트 ID 유지 확인
- 실제 회원·알림 서비스와의 통합은 미검증
- 상세 환경 및 한계는 verification.md 참고 (broker 4.3.1, 팀 Compose broker 3.9.1과 버전 차이)
- Gateway 경유 호출은 이번 Saga 이벤트 작업의 검증 대상이 아님

## 리뷰 요청사항

- 호성 담당 CRUD 코드와 파일이 겹치지 않도록 Saga 저장소를 별도 구성. 공유 R7 테이블의 상태·잠금 정책 확인 요청
- JDBC와 향후 JPA가 같은 DataSource/트랜잭션 관리자를 사용하도록 유지
- 공식 회원·알림 서비스와 통합 검증 후 머지/활성화

## 리뷰 반영

- DLT 파티션 수 불일치: Kafka에 파티션 선택 위임(-1), 실제 7번 원본 → 단일 파티션 DLT 검증
- Saga 비활성화 시 DB health도 비활성화, 활성화 시 DB health 포함
- outbox_event.aggregate_id 조회 인덱스: DBA 적용용 docs/sql/003_match_outbox_aggregate_index.sql과 두 테스트 스키마에 반영. 앱이 DDL 실행하지 않으며 팀 공식 R7에 보완 적용 필요
- 주요 트랜잭션·재시도·중복 처리 메서드 설명 보완
- 수정 후 실제 MariaDB/Kafka 포함 테스트 17개 및 빌드 통과
