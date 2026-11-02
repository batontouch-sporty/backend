# A29 관리자 모임·참가 통계

`GET /match-service/api/admin/stats` (서비스 내부: `/api/admin/stats`).
기준은 팀 API 명세 v1.0 37쪽과 2026-11-02 사용자가 확정한 집계 규칙이다.

- `matches.total`: 현재 저장된 전체 매치 행 수. 취소·완료 상태도 포함한다.
- `matches.byStatus`: 상태 5종의 매치 행 수. 없는 상태는 숫자 0이다.
- `participants.total`: 현재 저장된 전체 참가 기록 수. 방장과 취소·완료 매치의
  보존된 참가 행을 포함하고, 같은 회원이 여러 매치에 참가하면 각각 센다.
  대기자는 제외한다. 참가 취소·탈퇴·하드 삭제로 지워진 행은 집계하지 않는다.
- 기간 필터가 없는 누적 집계이며, 고유 회원 수나 실제 출석 인원 수를 뜻하지 않는다.
- 프론트 표시 이름은 `전체 참가 기록`이다.

Gateway가 JWT를 검증하고 사용자 ID·역할 헤더를 교체한다.
토큰 없음·잘못됨·만료는 401 `UNAUTHORIZED`, ADMIN이 아니면 403 `FORBIDDEN`이다.
서비스의 추가 가드도 ID 없음·잘못됨은 401, 역할이 ADMIN이 아니면 403을 반환한다.
서비스 직접 호출 시 A28의 ID 누락 400과 차이가 있지만, 외부 Gateway 계약은 동일하다.

응답은 `matches: {total, byStatus}`와 `participants: {total}` 구조이고
상태별 합계와 참가 수는 같은 REPEATABLE READ 트랜잭션에서 읽는다.
