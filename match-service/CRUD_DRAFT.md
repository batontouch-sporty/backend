# 매칭 CRUD 초안

기준 main: `5de929a` (2026-11-01). 확정 API A9~A13·A16, 공식 R8 DDL과 공통 MatchEntity·Repository를 사용한다. 로컬 초안 브랜치 `feat/10/match-crud-draft`에 작성하며 바로 main에 반영하지 않는다.

## 구현 범위

- A9 POST `/api/matches`: 201 + JSON 숫자 matchId. 시설 내부 PK를 serviceId로 받는다. 회원 잠금 → 회원·시설 Feign 검사 → 매치·OWNER 참가 저장. 방장을 포함해 정원 1이면 CLOSED와 상태 이벤트를 저장한다.
- A10 GET `/api/matches`: 조건 검색과 현재 인원 일괄 집계. 기본 상태 RECRUITING·CLOSED·IN_PROGRESS. 완료·취소는 명시적 status 조건으로 조회. distance는 null. 시설·종목·성별·실력·시간·정원·지역·무료 여부·제목·설명 필터 지원. 정렬은 시작 시각, ID 오름차순으로 두었다.
- A11 GET `/api/matches/{id}`: 시설 복사본과 회원 I2 프로필, OWNER·참가 여부 반환. 비로그인 플래그 false. 프로필 누락은 `알 수 없음`. 시설 서비스는 다시 호출하지 않는다.
- A12 PUT `/api/matches/{id}`: 방장만 시작 전에 수정. 현재 인원 미만 정원 거절. 시설·종목은 기존 값 유지. 기존 시설 I3 검사 후 복사본 갱신. 정원과 현재 인원에 따라 모집 상태를 재계산하고 바뀐 경우에만 공용 Outbox에 상태 이벤트 저장.
- A13 DELETE `/api/matches/{id}`: 방장·시작 전·모집 상태에서 참가 삭제 → flush → 매치 삭제. 대기열은 R8 FK CASCADE. 삭제 이벤트는 발행하지 않는다.
- A16 GET `/api/matches/me`: Gateway 사용자 ID만 사용한다. 회원 재조회 없이 OWNER·PARTICIPANT와 과거 기록을 시작 시각 내림차순으로 반환한다.

## 기존 작업과 연결

기존 참가·취소·대기열·탈퇴 Saga 코드는 변경하지 않는다. 생성·수정·삭제는 `participationMemberLocks`를 쓰며 Saga와 같은 회원 잠금 이름을 공유한다. 생성의 회원 검사와 OWNER 저장이 한 트랜잭션에서 잠금으로 보호된다. 수정·삭제는 회원 잠금 후 공통 매치 행 잠금을 얻는다.

시설 검사에는 PR #47의 MatchFacilityIntegration을, 상세에는 MatchRemoteChecks.profiles를 사용한다. 생성·수정 실패는 동일 트랜잭션을 롤백한다. 수정의 시설 검사와 프로필 조회는 매치 행 잠금을 가진 상태에서 수행되므로 기존 Feign 타임아웃 동안 잠금이 유지될 수 있다.

DB 엔티티·DDL·권한·Compose·다른 서비스는 수정하지 않았다. 검색을 위해 MatchRepository에 JpaSpecificationExecutor를 추가하고, 입력 검증용 validation 의존성을 추가했다.

## 연결 및 후속 작업

1. 공동 작업 시 컨트롤러·DTO·서비스 이름과 위치를 맞춰 하나의 구현으로 통합한다.
2. A17 방장 soft cancel, A29 관리자 통계, 시작·종료 스케줄러는 이번 CRUD 초안 밖이며 후속 연결이 필요하다.
3. 시작 하루 전 알림 `match.starting-soon.v1`의 발행 및 모임당 한 번 처리도 별도 작업이다.
4. 정원 증가 시 현재 확정 명세대로 RECRUITING으로 재계산한다. 기존 대기자를 정원 수정 순간에 자동 승급시키는 추가 정책은 구현하지 않았다. 현재 #35는 참가 취소·회원 탈퇴 시 승급한다.
5. 생성 시 OWNER 자동 등록에는 joined 이벤트를 별도로 만들지 않는다. 정원 1로 CLOSED가 되는 경우에는 CLOSED 이벤트를 발행한다. 알림 정책 검토 시 이 동작을 확인한다.
6. 초안 단계의 검증은 H2·MockMvc와 회원·시설 검사 Bean 대체 기반이다. 공식 MariaDB·실제 Gateway·시설·회원 서비스의 신규 CRUD 통합 검증은 합의 후 진행한다. #32 완료로 판정하지 않는다.

## 테스트

MatchCrudDraftTest에서 숫자 생성 응답, 회원 잠금 순서, 시설 스냅샷·OWNER 저장, 정원 1 마감 이벤트, 입력·검색 검증, 기본 상태 필터, 상세 프로필·플래그, 수정 상태 전이·권한·정원·시설 장애 롤백, terminal 상태 제한, 삭제 CASCADE, 내 매치 회원 재조회 없음, Outbox 실패 시 생성 롤백을 확인한다. 기존 매칭 전체 회귀 테스트와 bootJar도 실행한다.

2026-11-01 실행 결과: `:match-service:test :match-service:bootJar` 성공. 총 115건 중 103건 성공, 별도 실서비스 실행 조건이 있는 12건 제외, 실패 0건. 신규 CRUD 테스트 11건을 포함한다. 실서비스 CRUD 통합 검증은 아직 수행하지 않았다.
