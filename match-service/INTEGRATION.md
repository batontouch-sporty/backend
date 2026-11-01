# 매칭 외부 서비스 검사 연결

`MatchRemoteChecks`는 회원·시설 API 호출과 실패 정책을 제공한다. `MatchFacilityIntegration`은 검증 결과를 공통 `MatchEntity`에 연결한다. 저장과 권한·정원·시작 시각 검사는 호출 서비스가 담당한다.

- 생성: `MatchFacilityIntegration.create(userId, details, facilityId)`로 회원·시설을 확인한 엔티티를 받아 같은 업무 트랜잭션에서 저장한다. 방장 참가 행·정원 상태·이벤트는 생성 서비스에서 함께 처리한다.
- 수정: 매치 잠금과 방장 권한·수정 가능 상태·정원 검사를 마친 뒤 `MatchFacilityIntegration.update(match, details, facilityId)`를 호출한다. 검증 실패 시 기존 엔티티는 변경하지 않는다.
- 참가: 매치 잠금 아래 `MatchRemoteChecks.checkParticipant(userId, match.getGenderGroup().name())`로 회원 자격을 확인한다. 회원별 잠금을 사용하는 참가/Saga의 잠금 순서도 통일한다.
- 상세: `profiles(userIds)` 결과를 참가자 응답에 연결한다. 조회 실패 또는 누락된 회원의 닉네임은 `알 수 없음`이다.

## 통신과 오류

I1 `GET /internal/users/{id}`, I2 `GET /internal/users?ids=1,2`, I3 `GET /internal/facilities/{id}`를 호출한다. 시설 ID는 시설 내부 PK다. 회원·시설 DB를 직접 조회하지 않는다.

기본 서비스 발견은 Eureka를 사용한다. 직접 주소를 지정할 때는 `MATCH_USER_SERVICE_URL`, `MATCH_FACILITY_SERVICE_URL`을 설정한다. 연결 1초·응답 2초, TimeLimiter 3초다. Circuit Breaker는 메서드별로 구분하며 최근 10건·최소 5건·실패율 50%·OPEN 10초·반개방 2건 기준을 사용한다.

회원·시설 장애는 503으로 거절하고, 시설 404는 `SERVICE_NOT_FOUND`, 비활성 시설은 `FACILITY_INACTIVE`, 운영시간 밖의 일정은 `FACILITY_HOURS_MISMATCH`로 처리한다. 참가의 비활성 회원은 `USER_NOT_ACTIVE`, 생성의 비활성 회원은 `USER_WITHDRAWN`이다. 시설 운영시간이 없거나 시작·종료가 같으면 운영시간 확인이 필요하다는 오류를 반환한다.

## 검증과 연결 상태

기본 테스트는 실제 Feign과 HTTP 테스트 서버로 상태·성별·일괄 조회·장애·타임아웃·Circuit Breaker·공통 엔티티 스냅샷 저장을 검증한다.

`MATCH_LIVE_FACILITY=true`로 실행하는 `LiveFacilityContractTest`는 별도로 실행한 실제 시설 서비스를 대상으로 한다. 전용 테스트 서버 localhost:18083에 ID 12(09~22시 활성, Live facility/Live location/송파구/유료/URL https://example.org/live), ID 13(비활성) 데이터를 준비한다. ID 999는 비워 둔다. 테스트는 조회만 수행한다. 공유 운영 데이터 대신 전용 DB를 사용한다.

확인한 실제 시설 코드: PR #41을 포함한 main `adefe5c`. 시설 서비스를 전용 MariaDB 11.4 DB로 실행해 I3 정상·비활성·404·운영시간 밖 요청을 실제 Feign으로 확인했다. 전체 매칭 테스트는 59개 통과했고 실제 브로커 전용 및 서버 중단 전용 테스트 각 1개는 이 실행에서 제외했다.

시설 서버 중단 검증은 동일 서버를 중단한 뒤 `MATCH_LIVE_FACILITY=true`, `MATCH_LIVE_FACILITY_DOWN=true`를 설정하고 `:match-service:test --rerun-tasks --tests '*LiveFacilityContractTest.actualStoppedFacilityReturns503'`를 실행한다. 이때 기존 활성 시설 조회가 503 `FACILITY_SERVICE_UNAVAILABLE`로 바뀌는 것을 확인한다. 정상 조회 테스트와 중단 테스트는 순서대로 실행한다. 메모리 DB 테스트와 구분되며, 전체 팀 Compose/Eureka/Gateway 연결은 후속 API 연결 뒤 확인한다.

회원 I1·I2 내부 API와 매칭 CRUD·참가 서비스의 호출부는 해당 담당 구현 후 연결해야 한다. 이슈 #32의 실제 회원 서비스 통합 완료 조건은 이 연결 뒤 확인한다.
