# 매칭 외부 서비스 검사 연결

`MatchRemoteChecks`는 회원·시설 API 호출과 실패 정책을 제공한다. `MatchFacilityIntegration`은 검증 결과를 공통 `MatchEntity`에 연결한다. 저장과 권한·정원·시작 시각 검사는 호출 서비스가 담당한다.

- 생성: 회원별 DB 잠금을 획득한 같은 업무 트랜잭션에서 `MatchFacilityIntegration.create(userId, details, facilityId)`로 회원·시설을 확인한 엔티티를 받아 저장한다. 탈퇴 Saga와 같은 회원 잠금 이름을 사용한다. 방장 참가 행·정원 상태는 생성 서비스에서 함께 처리한다.
- 수정(A12): `MatchRemoteChecks.checkFacility(...)`로 시설을 잠금 전에 검사한다. 트랜잭션에서 회원 잠금 후 매치 행 잠금을 얻고 방장 권한·시설 ID·수정 가능 상태·기간·현재 인원 대비 정원을 재확인한 뒤 저장한다. 트랜잭션 커밋 후 `MatchRemoteChecks.profiles(...)`로 상세 프로필을 조회한다. 원격 시설·프로필 호출 동안 매치 행 잠금을 보유하지 않는다. 검증 실패 시 기존 엔티티는 변경하지 않는다.
- 참가: 회원별 DB 잠금 → `MatchRemoteChecks.activeMember(userId)` → 매치 행 잠금 순서다. 조회한 회원의 성별은 `checkParticipantGender(member, genderGroup)`로 매치 잠금 아래에서 비교한다. 참가의 원격 회원 조회는 매치 행 잠금을 잡기 전에 마친다. 이슈 #11의 PR #59에서 실제 호출부를 연결했으며 참가/Saga와 같은 회원 잠금 이름을 사용한다.
- 상세: `profiles(userIds)` 결과를 참가자 응답에 연결한다. 조회 실패 또는 누락된 회원의 닉네임은 `알 수 없음`이다.

## 통신과 오류

I1 `GET /internal/users/{id}`, I2 `GET /internal/users?ids=1,2`, I3 `GET /internal/facilities/{id}`를 호출한다. 시설 ID는 시설 내부 PK다. 회원·시설 DB를 직접 조회하지 않는다.

기본 서비스 발견은 Eureka를 사용한다. 직접 주소를 지정할 때는 `MATCH_USER_SERVICE_URL`, `MATCH_FACILITY_SERVICE_URL`을 설정한다. 연결 1초·응답 2초, TimeLimiter 3초다. Circuit Breaker는 메서드별로 구분하며 최근 10건·최소 5건·실패율 50%·OPEN 10초·반개방 2건 기준을 사용한다.

회원·시설 장애는 503으로 거절하고, 시설 404는 `SERVICE_NOT_FOUND`, 비활성 시설은 `FACILITY_INACTIVE`, 운영시간 밖의 일정은 `FACILITY_HOURS_MISMATCH`로 처리한다. 참가의 비활성 회원은 `USER_NOT_ACTIVE`, 생성의 비활성 회원은 `USER_WITHDRAWN`이다. 시설 운영시간이 없거나 시작·종료가 같으면 운영시간 확인이 필요하다는 오류를 반환한다.

## 검증과 연결 상태

기본 테스트는 실제 Feign과 HTTP 테스트 서버로 상태·성별·일괄 조회·장애·타임아웃·Circuit Breaker·공통 엔티티 스냅샷 저장을 검증한다.

`MATCH_LIVE_FACILITY=true`로 실행하는 `LiveFacilityContractTest`는 별도로 실행한 실제 시설 서비스를 대상으로 한다. 전용 테스트 서버 localhost:18083에 ID 12(09~22시 활성, Live facility/Live location/송파구/유료/URL https://example.org/live), ID 13(비활성) 데이터를 준비한다. ID 999는 비워 둔다. 테스트는 조회만 수행한다. 공유 운영 데이터 대신 전용 DB를 사용한다.

확인한 실제 시설 코드: PR #41을 포함한 main `adefe5c`. 시설 서비스를 전용 MariaDB 11.4 DB로 실행해 I3 정상·비활성·404·운영시간 밖 요청을 실제 Feign으로 확인했다. 전체 매칭 테스트는 59개 통과했고 실제 브로커 전용 및 서버 중단 전용 테스트 각 1개는 이 실행에서 제외했다.

시설 서버 중단 검증은 동일 서버를 중단한 뒤 `MATCH_LIVE_FACILITY=true`, `MATCH_LIVE_FACILITY_DOWN=true`를 설정하고 `:match-service:test --rerun-tasks --tests '*LiveFacilityContractTest.actualStoppedFacilityReturns503'`를 실행한다. 이때 기존 활성 시설 조회가 503 `FACILITY_SERVICE_UNAVAILABLE`로 바뀌는 것을 확인한다. 정상 조회 테스트와 중단 테스트는 순서대로 실행한다. 메모리 DB 테스트와 구분되는 전용 서버 검증이다.

회원 I1·I2는 #44로 main에 추가됐다. 실제 회원 서비스를 전용 MariaDB에서 실행해 Feign 연결 테스트 6개를 통과했다. 매칭 CRUD·참가 API 호출부는 PR #77·#59로 연결됐다.

## 최신 연결 상태 (2026-11-02)

- PR #47의 I1·I2·I3 Feign 및 Circuit Breaker를 참가(PR #59), 대기열(PR #65), CRUD(PR #77)에 연결했다.
- 생성은 회원별 DB 잠금 안에서 회원·시설을 검증하고 시설 스냅샷과 URL, 방장 참가 행을 함께 저장한다.
- 수정은 시설 조회를 매치 행 잠금 밖에서 완료하고 잠금 후 권한·상태·기간·정원을 다시 확인한다. 프로필 조회는 커밋 후 수행한다.
- 시설 장애 시 생성·수정은 503으로 거절하고 목록·상세는 저장된 시설 정보를 사용한다. 회원 장애 시 참가는 503으로 거절하고 상세 닉네임은 `알 수 없음`으로 대체한다.
- 실제 서비스 검증 이력과 재현 방법은 [통합 검증 기록](INTEGRATION_VERIFICATION.md)에 정리한다.

## 실제 회원 연결 테스트

MATCH_LIVE_MEMBER=true로 LiveMemberContractTest를 실행한다. 전용 localhost:18181에서 실제 user-service를 실행하고 회원 ID 1(남성 ACTIVE), 2(여성 ACTIVE), 3(남성 WITHDRAWN), 4(남성 WITHDRAWING)를 준비한다. 프로필 닉네임은 ID 1이 Live active, ID 3이 Live history이고 ID 999는 비워 둔다. 테스트는 읽기 전용이다. 전용 테스트 DB를 사용하며 실행 시 --rerun-tasks로 외부 서버 상태 변경을 반영한다.

## API 명세 정합성

PR #77·#86 이후 확정 A13 변경과 노션 A9~A13·A16 갱신 내역은 [API_CONTRACT.md](API_CONTRACT.md)에 정리한다. 기존 통합 검증 97항목은 당시 커밋의 기록이며 최신 삭제 보존 동작의 검증과 구분한다.
