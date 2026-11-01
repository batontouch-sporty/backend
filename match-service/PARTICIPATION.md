# 참가·참가 취소 (#11)

POST `/api/matches/{matchId}/participants`는 201 참가 정보를, DELETE `/api/matches/{matchId}/participants/me`는 본문 없는 204를 반환한다. 인증은 Gateway가 검증한 X-User-Id를 사용한다.

참가는 회원별 DB 잠금 → 회원 상태 조회 → 매치 행 비관적 락 순서로 처리한다. 매치 잠금 아래에서는 조회한 회원의 성별과 매치의 상태·시간·중복·정원을 검사하며 원격 호출을 하지 않는다. OWNER도 정원에 포함한다. 참가 기록·모집 상태를 한 트랜잭션으로 변경한다.

참가·취소에서 회원 잠금 대기 3초가 초과되면 409 `MEMBER_OPERATION_IN_PROGRESS`를 반환한다. SQL 오류나 잠금 결과 NULL은 잠금 경합과 구분한다.

방장은 참가 취소할 수 없다. CANCELLED·COMPLETED에서는 400 MATCH_LEAVE_NOT_ALLOWED로 거절해 참가 이력을 보존한다. 일반 참가자는 IN_PROGRESS에서도 취소할 수 있다. 시작 전 CLOSED에서 빈자리가 생기면 RECRUITING으로 바꾸고 시작 후에는 상태를 유지한다. 회원 상태·성별은 #47로 병합된 MatchRemoteChecks로 확인한다.

#10 CRUD 서비스와 공통 모델은 유지한다. 참가/상태 변경 Outbox는 #33, 대기자 승급은 #35에서 같은 트랜잭션에 연결한다.

## 검증

H2 서비스·MVC 테스트로 정원 도달·재개, 중복·방장·시작·상태 제한, 실패 롤백, 없는 기록, 20명 동시 참가를 확인했다.

별도 MariaDB와 실제 JWT Gateway에서 201/204, 중복 409, 방장 403, 인증 없음 401, 위조 X-User-Id 교체를 확인했다. 20명 동시 요청 중 남은 2자리만 성공했고 OWNER 포함 정원 3명과 CLOSED를 유지했다.

최신 main `668d874`를 병합한 뒤 매칭 전체 테스트와 빌드를 통과했다. 잠금 Bean 설정을 정리한 뒤 해당 설정 테스트와 빌드도 다시 통과했다.

추가 통합 검증에서는 전용 Compose의 실제 Gateway JWT·Eureka·user-service·MariaDB를 연결했다. 회원 I1은 실제 회원 서비스로 호출했다. Gateway의 서비스 발견과 Feign의 회원 서비스 발견을 모두 사용하고, 위조 X-User-Id가 JWT 사용자로 교체되는 것을 확인했다.

실제 회원 20명의 동시 참가에서 남은 2자리만 성공했고 OWNER 포함 정원 3명 및 CLOSED를 유지했다. 참가 취소 204와 RECRUITING 복귀, 중복 409, 방장 취소 403, 성별 제한 및 WITHDRAWING·WITHDRAWN 참가 거절을 확인했다.

리뷰 반영 후 전용 Compose에서 CANCELLED·COMPLETED 참가 취소 400 및 이력 보존을 재확인했다. 회원 조회를 매치 잠금 앞으로 옮긴 뒤에도 실제 20명 동시 참가에서 2자리만 성공하고 일반 취소 204·모집 재개가 유지됐다. 회귀 테스트에서는 지연된 회원 조회 중에도 다른 회원이 같은 매치에 참가할 수 있는지 확인한다.

검증 DB는 팀 데이터와 분리했으며 R7에 승인된 URL·대기열 보완 구성을 적용한 테스트 스키마다. 이 환경에서 매칭을 ddl-auto=validate 및 Saga 활성 상태로 기동했다. 팀의 최종 Compose 초기화 DDL·권한 전환은 담당 PR 반영 후 확인한다. 루트 Compose·DDL·권한 설정은 이 PR에서 변경하지 않는다.

## Saga 공존

participationMemberLocks와 withdrawalMemberLocks는 같은 DB 잠금 이름을 사용한다. Saga의 잠금 주입에는 Qualifier를 지정했다. Saga 플래그와 관계없이 참가 잠금을 사용할 수 있다.
