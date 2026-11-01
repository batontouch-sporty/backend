# 참가·참가 취소 (#11)

POST `/api/matches/{matchId}/participants`는 201 참가 정보를, DELETE `/api/matches/{matchId}/participants/me`는 본문 없는 204를 반환한다. 인증은 Gateway가 검증한 X-User-Id를 사용한다.

회원별 DB 잠금 → 매치 행 비관적 락 순서로 상태·시간·중복·정원을 검사한다. OWNER도 정원에 포함한다. 회원 API 호출 후 시작 시각을 다시 확인하고 참가 기록·모집 상태를 한 트랜잭션으로 변경한다.

방장은 참가 취소할 수 없다. 일반 참가자는 시작 후에도 취소할 수 있다. 시작 전 CLOSED에서 빈자리가 생기면 RECRUITING으로 바꾸고 시작 후에는 상태를 유지한다. 회원 상태·성별은 #42의 MatchRemoteChecks로 확인한다.

#10 CRUD 서비스와 공통 모델은 유지한다. 참가/상태 변경 Outbox는 #33, 대기자 승급은 #35에서 같은 트랜잭션에 연결한다.

## 검증

H2 서비스·MVC 테스트로 정원 도달·재개, 중복·방장·시작·상태 제한, 실패 롤백, 없는 기록, 20명 동시 참가를 확인했다.

별도 MariaDB와 실제 JWT Gateway에서 201/204, 중복 409, 방장 403, 인증 없음 401, 위조 X-User-Id 교체를 확인했다. 20명 동시 요청 중 남은 2자리만 성공했고 OWNER 포함 정원 3명과 CLOSED를 유지했다.

Gateway 검증은 정적 라우트와 R7 보완 검증용 스키마를 사용했다. 회원 I1은 HTTP 테스트 서버로 대체했다. 실제 회원 API·팀 Compose/Eureka·최종 DDL 연결은 담당 구현 후 검증한다.

## Saga 공존

participationMemberLocks와 withdrawalMemberLocks는 같은 DB 잠금 이름을 사용한다. Saga의 잠금 주입에는 Qualifier를 지정했다. Saga 플래그와 관계없이 참가 잠금을 사용할 수 있다.
