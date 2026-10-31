# #10·#11 공통 매치 모델

이 파일은 매치 CRUD(#10)와 참가·취소(#11)가 함께 사용할 코드 계약이다. 기준은
Sporty v2 API 명세 v1.0과 R7 DDL에 `matches.url`·`match_waitlist`를 더한 보완안이다.
실제 팀 DB에는 보완 DDL을 DBA가 적용해야 한다. 애플리케이션 계정에는 DDL 권한을 주지 않는다.

## 소유 데이터와 클래스

| 테이블 | 클래스 | 핵심 규칙 |
| --- | --- | --- |
| `match_db.matches` | `MatchEntity` | 시설 내부 ID 및 시설명·장소명·지역·무료 여부·예약 URL을 값으로 복사한다. `ServiceEntity` 관계를 만들지 않는다. |
| `match_db.match_participants` | `MatchParticipantEntity` | 같은 DB의 `MatchEntity`만 JPA로 연결한다. `userId`는 숫자 값이다. OWNER도 참가 행 1건으로 센다. |
| `match_db.match_waitlist` | `MatchWaitlistEntity` | `userId`는 숫자 값이다. `registeredAt,id` 순서로 첫 대기자를 고른다. 하드 삭제 시 DB CASCADE, 소프트 취소 시 보존한다. |

enum은 match-service 안에 독립적으로 둔다. `MatchStatus`는 R7의 다섯 값,
`SportType`은 DDL의 열 종목, `GenderGroup`은 MALE/FEMALE/MIXED이다.
다른 서비스의 Entity나 저장소를 참조하지 않는다.

## #10 CRUD 담당

- `MatchEntity.Details`와 `FacilitySnapshot`으로 매치를 생성·수정한다. 시설 값은
  facility-service 내부 API에서 받는다. 요청 본문의 시설 ID를 검증 없이 복사하지 않는다.
- 검색·상세 응답은 매치에 저장된 시설 스냅샷을 사용한다. 기존 `ServiceEntity`
  JOIN·EntityGraph는 옮기지 않는다.
- 시작 후 수정과 정원 축소 제한은 서비스 계층에서 검사한다. 정원 변경 시 인원과
  `RECRUITING/CLOSED` 상태를 다시 계산한다.
- 하나의 `match_db` 트랜잭션에서 하드 삭제한다. 먼저 `MatchRepository.findByIdForUpdate`로
  매치 행을 잠그고, 잠금 상태에서 방장 권한·시작 전·모집 상태 등 삭제 허용 조건을 확인한다.
  이어서 `MatchParticipantRepository.deleteAllByMatch_Id` 후 `flush()`, 그다음
  `MatchRepository.deleteHardById`를 호출한다. DB가 대기열 행을 CASCADE로 삭제한다.
  `CANCELLED` 소프트 취소는 매치·참가·대기 기록을 남긴다.

## #11 참가·취소 담당

- 하나의 `match_db` 트랜잭션에서 `MatchRepository.findByIdForUpdate`로 매치 행을
  먼저 잠근다. 그 후 참가 중복·정원·시작 시각을 확인하고 참가 행과 모집 상태를 바꾼다.
- 방장도 참가자 수에 포함한다. `(match_id,user_id)` 고유 제약이 중복 참가의 최종 방어선이다.
- 참가 취소 후 첫 대기자를 승급할 때도 같은 매치 행 잠금 아래에서
  `findFirstByMatch_IdOrderByRegisteredAtAscIdAsc`로 조회하고, 대기 행 삭제·참가 행 삽입·
  상태 재계산·outbox 이벤트 기록을 같은 DB 트랜잭션으로 묶는다.
- user-service 상태·성별 검사는 별도 Feign 작업이다. `UserEntity`나
  `user_db` JOIN으로 대체하지 않는다.

## 현재 범위와 검증

이 브랜치는 공통 Entity·Repository 및 JPA 의존성만 제공한다. #10·#11 API 구현,
Feign, 대기 승급 서비스 로직, 실제 MariaDB에 대한 R7 보완 DDL 적용은 포함하지 않는다.
기존 탈퇴 Saga의 JDBC 코드와 공존한다. 운영에서는 DDL 적용 후
`ddl-auto=validate`로 전환하며, 그 전에는 앱 계정에 DDL 권한을 주지 않는다.

`./gradlew.bat :match-service:test`로 컴파일·기존 Saga 테스트·H2 JPA 매핑과
대기열 CASCADE 삭제 경로를 확인한다. H2 테스트는 MariaDB DDL 실행 검증을 대체하지 않는다.
