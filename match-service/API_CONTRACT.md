# 매칭 API 계약 보완 (2026-11-02, 이슈 #104)

기준 main은 `151491e`다. PR #77(CRUD)·#86(Feign 통합 검증) 이후 노션의 확정 A13 변경을 구현하고, 해당 PR에서 확인한 API 동작을 노션에 반영한다. 과거 검증 결과는 당시 커밋의 기록으로 보존한다.

## A13 DELETE 변경

- Gateway: `DELETE /match-service/api/matches/{matchId}`. 응답은 기존처럼 204와 빈 본문.
- 회원 잠금 → 매치 행 잠금 → 방장 권한·시작 전·RECRUITING/CLOSED 검사.
- 하드 삭제 대신 CANCELLED로 전환하고 `cancel_reason=OWNER_DELETE`, 취소 시각·상태 변경 시각을 기록한다.
- 매치·참가·대기 기록은 보존한다. 대기자 승급은 발생하지 않는다.
- 같은 트랜잭션에서 `match.status-changed.v1`을 Outbox에 저장한다. 기존 필드·key 계약을 유지하고 `ownerId`와 방장 포함 확정 `participantIds`를 담는다. 대기자는 수신 대상에서 제외한다.
- 비방장 403 MATCH_DELETE_FORBIDDEN, 취소/진행/완료 또는 시작 시각 경과 400 MATCH_DELETE_NOT_ALLOWED, 없는 모임 404 MATCH_NOT_FOUND를 유지한다. 반복 삭제도 거절해 추가 취소 이벤트를 만들지 않는다.
- Outbox 저장 실패 시 취소 상태·사유·시각도 롤백한다.
- A17 PATCH 취소는 별도 경로·200 응답·MATCH_CANCEL_* 오류를 유지한다. A13 변경에 따른 DDL·이벤트 필드 추가는 없다.

## 노션 갱신 항목

- [A9 생성](https://app.notion.com/p/4090b90e16028199898ef1778220b1a0): 최소 정원 2명, 미래 일정, 회원·시설 검증과 스냅샷/URL 저장, USER_SERVICE_UNAVAILABLE 및 비활동 회원 설명.
- [A10 검색](https://app.notion.com/p/4090b90e1602817bab69f4b693b91ef7): 기본 상태 3종, 조건 AND, 대소문자 무시·지역 부분 검색, 시작 시각·ID 오름차순, 최대 500건.
- [A11 상세](https://app.notion.com/p/4090b90e160281aeb010fb842d86edeb): 선택 인증의 401 조건, 저장된 시설 정보와 I2 프로필 대체 응답.
- [A12 수정](https://app.notion.com/p/4090b90e160281d8b90ae290d68b11f4): 최소 정원, 기간, 시설 불변, 잠금 밖 시설 조회·잠금 안 재검사, 커밋 후 프로필 조회, 시설 404·비활성 오류.
- [A13 삭제](https://app.notion.com/p/4090b90e160281928764eada60134b42): 확정된 취소·기록 보존 계약을 구현. 분류를 `1차에서 변경`, 상태를 `진행 중`으로 맞췄다. 머지 전 완료로 표시하지 않는다.
- [A16 내 매치](https://app.notion.com/p/4090b90e160281c38227d896bdb45f41): 동일 시작 시각의 ID 내림차순, 과거 참가 기록 포함·대기 신청만 한 모임 제외, 회원 원격 조회 없음.
- 이미 머지된 A9·A10·A11·A12·A16은 `완료`로 갱신했다. A17·A29 등 타 이슈의 담당·상태는 변경하지 않았다.

## 검증

`./gradlew :match-service:test :match-service:bootJar` 성공. 총 142개 중 130개 통과, 별도 실서비스 실행 조건의 12개 제외, 실패·오류 0개.

DELETE의 권한·기록 보존·수신자·중복 요청 방지와 CLOSED 상태에서 Outbox 실패 롤백·정상 취소를 회귀 검증했다. 기존 terminal 상태·참가·대기열·Saga 테스트도 통과했다.

격리된 공식 R8·DML 계정·validate Compose에서 이번 수정 match-service 이미지를 새로 빌드했다. Gateway·회원·시설·알림은 #32 검증 때 빌드한 `f792f2e` 이미지를 재사용했다. 팀 DB는 변경하지 않았다. 실제 가입·로그인 및 Gateway 호출부터 Kafka와 알림 서비스 저장까지 다음 22항목을 확인했다. 포인트 연결, 이번 변경 후 삭제·탈퇴 병렬 경합 및 리마인더 역순 수신은 이 실행에서 별도로 검증하지 않았다.

1. signup 0
2. login 0
3. signup 1
4. login 1
5. signup 2
6. login 2
7. A9 creates match via actual Feign
8. participant joins and closes match
9. waitlist entry created
10. non-owner delete rejected
11. A13 owner receives 204
12. match retained as CANCELLED with reason/time
13. owner and participant records retained
14. waitlist retained
15. published event includes owner and confirmed participants, excludes waiter
16. cancellation notification saved for 43
17. cancellation notification saved for 44
18. repeated delete rejected
19. repeated delete emits no second cancellation
20. detail remains available
21. cancelled match retained in participant history
22. default search hides cancelled match
# R10 포인트 Saga 변경 (작업 브랜치)

생성·참가 202 접수, 본인 요청 상태 조회, CONFIRMED 기준 조회, 참가 취소 시 비동기 보증금 반환 계약은 [POINT_SAGA.md](POINT_SAGA.md)를 따른다. 아래 기존 API 기록과 달라진 항목은 R10 적용 시 새 계약으로 전환한다. PR #111 및 회원·포인트 코드와 함께 적용하며, 기존 R8 환경에는 이 변경만 배포하지 않는다.

## 출석 수정·A11 참가자 필드 (#125)

- `PATCH /api/matches/{matchId}/participants/{participantId}/attendance` (Gateway `/match-service/...`), X-User-Id 필수, body `{"attendance":"ATTENDED"|"NO_SHOW"}`. 응답 200 `{participantId, userId, attendance}`.
- 매치 행 잠금 후 검사. 미인증 401 UNAUTHORIZED, 잘못된 값·누락 400 INVALID_INPUT, 없는 매치 404 MATCH_NOT_FOUND, 방장 아님 403 FORBIDDEN, `start_at <= 현재 < end_at`이 아니거나 CANCELLED·COMPLETED 400 ATTENDANCE_NOT_EDITABLE(스케줄러 지연과 무관하게 시각 기준), 그 매치의 CONFIRMED 참가자가 아니면 404 PARTICIPANT_NOT_FOUND. 오류 본문은 기존 `{code, message}`.
- 출석 기본값 ATTENDED. 종료 시 출석 정산 요청 발행과 상태 전이 스케줄러는 [POINT_SAGA.md](POINT_SAGA.md) "상태 전이·출석 정산"을 따른다.
- A11 상세 `participants[]` 항목에 `participantId`(match_participant_id), `attendance`(ATTENDED/NO_SHOW)를 추가했다. 기존 profileId·nickname·imageUrl·role은 유지한다.
