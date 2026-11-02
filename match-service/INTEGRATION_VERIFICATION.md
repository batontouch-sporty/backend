# 회원·시설 Feign 및 Circuit Breaker 통합 검증

## 기준과 환경

- 검증일: 2026-11-02. 기준 main: `f792f2e` (CRUD PR #77 머지).
- #32 구현 PR #47, 참가 PR #59, 대기열 PR #65, CRUD PR #77을 함께 검증했다.
- 격리된 Compose 프로젝트에서 실제 Gateway·Eureka·Config·회원·시설·매칭·알림·Kafka·MariaDB·Redis를 사용했다. 서비스 실행 파일 5개는 위 main 기준으로 새로 빌드했다. Discovery·Config는 기존 격리 이미지와 원격 Config 설정을 사용했다.
- 공식 R8 DDL, 서비스별 DML 계정, `ddl-auto=validate`를 사용했다. 전용 DB에 시설 fixture를 넣고 회원 12명은 실제 가입·로그인 API로 생성했다. 팀 DB 데이터는 변경하지 않았다.

## 결과

- match-service 기본 테스트: 130개 중 118개 통과, 12개 제외, 실패·오류 0개. 제외 항목은 별도 서버·브로커 실행 조건을 가진 테스트다.
- 실행 명령: `./gradlew :match-service:test :match-service:bootJar :user-service:bootJar :facility-service:bootJar :notification-service:bootJar :gateway-service:bootJar`.
- 별도로 실제 서비스 통합 확인 97항목을 통과했다. 아래 항목 수에는 fixture 가입·로그인 및 API 응답·DB 확인이 포함된다.
- A9: I1 활동 자격, I3 활성·운영시간·404 검사, 시설 스냅샷·URL 저장, JWT 회원 식별자를 확인했다.
- A11: I2 실제 닉네임, 방장·참가 여부, 익명 상세, 시설 중단 시 스냅샷 응답을 확인했다.
- A12: 실제 시설의 비활성·운영시간 위반과 시설 중단을 거절하고 기존 저장값을 유지했다. 정원 변경 시 상태·알림을 확인했다.
- A14: 활동 상태·성별·중복 참가, 8개 동시 참가의 정원 초과 방지를 확인했다.
- 실제 시설 컨테이너 중단: 생성·수정 503, 상세 200. 실제 회원 컨테이너 중단: 참가 503, 상세 200 및 `알 수 없음`, 내 매치 200.
- 회원·시설 재기동 및 Circuit Breaker OPEN 대기 시간 경과 후 생성·상세 프로필·참가가 정상 복구됐다.
- 대기 등록 → 참가 취소 → 선착순 승급 → 실제 알림함 저장과 실제 탈퇴 Saga → 후속 승급·알림을 확인했다.
- 모임 2개 삭제와 참가자 탈퇴를 동시에 수행하는 경합을 3회 실행했다. 삭제 204, 탈퇴 접수 202 → CLEANED·WITHDRAWN을 확인했고 MariaDB 데드락 카운터는 0이었다.

## Circuit Breaker·타임아웃 확인 범위

실제 Feign과 로컬 HTTP 테스트 서버를 사용하는 `MatchRemoteChecksTest`에서 회원·시설 응답 지연, 503, 반복 실패 후 OPEN 및 추가 원격 호출 차단, I1과 I2 회로 분리, 반복 404가 회로를 열지 않는 정책을 검증했다. 위 컨테이너 중단·복구는 실제 서비스 경로를 확인하는 별도 검증이다. 네트워크 패킷 지연 주입이나 전체 시스템 부하 테스트는 수행하지 않았다.

## 재현 순서

1. 전용 Compose 환경과 R8 DB를 준비하고 Gateway 및 회원·시설·매칭·알림 서비스를 기동한다. 공유 환경에서는 컨테이너를 중단하지 않는다.
2. 회원 가입·로그인, 활성/비활성 시설과 운영시간 fixture를 준비한다.
3. Gateway로 A9 → A11 → A12 → A14/A15, 대기 등록·승급·회원 탈퇴를 호출해 응답과 소유 서비스 DB·알림함을 확인한다.
4. 시설 서비스만 중단하고 생성·수정의 503 및 상세 스냅샷 200을 확인한 뒤 재기동한다.
5. 회원 서비스만 중단하고 참가 503, 상세 닉네임 대체값, 내 매치 200을 확인한 뒤 재기동한다.
6. 서비스 건강 상태와 Eureka 등록을 기다리고 OPEN 대기 시간(기본 10초) 경과 후 정상 생성·조회·참가를 확인한다.

## 계약과 범위 (PR #86 검증 당시)

2026-11-02 후속 변경: 노션 A13 계약은 CANCELLED 전환·기록 보존·취소 이벤트 발행으로 변경됐고 이슈 #104에서 반영한다. 아래 97항목은 `f792f2e` 당시의 검증 기록으로 보존한다. 최신 A13 보존 동작의 검증 결과는 [API 계약 보완](API_CONTRACT.md)을 참고한다.


API·이벤트·DDL·설정을 변경하지 않는다. 기본 통합 검증에서 A13은 당시 main의 하드 삭제·대기열 CASCADE·추가 이벤트 없음 동작을 확인했다. 삭제 시 CANCELLED 알림 발행 변경 요청은 이번 #32 검증 완료 범위에 포함하지 않으며 별도 계약·구현에서 다룬다. 리마인더 수신부 PR #84도 이번 검증 대상이 아니다.

## 실제 서비스 확인 목록

1. actual signup 0
2. actual login 0
3. actual signup 1
4. actual login 1
5. actual signup 2
6. actual login 2
7. actual signup 3
8. actual login 3
9. actual signup 4
10. actual login 4
11. actual signup 5
12. actual login 5
13. actual signup 6
14. actual login 6
15. actual signup 7
16. actual login 7
17. actual signup 8
18. actual login 8
19. actual signup 9
20. actual login 9
21. actual signup 10
22. actual login 10
23. actual signup 11
24. actual login 11
25. A9 creates numeric match ID for 28
26. JWT identity overrides forged X-User-Id on creation
27. actual I3 facility snapshot and URL persisted
28. A11 anonymous view flags
29. A11 actual I2 nickname and owner flag
30. A14 real member qualification and join
31. duplicate participant returns 409
32. A12 capacity closes recruiting using current count
33. A12 CLOSED event reaches owner notification
34. A12 rejects capacity below current
35. A12 rejects non-owner
36. A12 capacity increase reopens recruiting
37. A10 case-insensitive keywords and partial region
38. A16 actual participant matches
39. A13 rejects non-owner
40. A9 rejects inactive facility
41. A9 rejects outside opening hours
42. A9 rejects missing facility
43. A9 creates numeric match ID for 28
44. female participant rejected from MALE match
45. WITHDRAWING member cannot join
46. WITHDRAWING member cannot create
47. A9 creates numeric match ID for 28
48. queue fixture filled through API
49. A18 FIFO queue registered through API
50. A15 leave promotes earliest waiter
51. actual promotion arrives in promoted user notification list
52. real withdrawal accepted for 30
53. user-service finishes CLEANED for 30
54. real withdrawal Saga promotes next FIFO candidate
55. Saga promotion reaches next user notification
56. A9 creates numeric match ID for 28
57. A13 actual hard delete returns 204
58. A13 participant deletion and R8 waitlist CASCADE
59. A13 creates no extra event
60. A9 creates numeric match ID for 28
61. 8 concurrent participants fill only 2 free seats
62. A9 creates numeric match ID for 39
63. running owner withdrawal accepted asynchronously
64. running owner Saga rejects and restores ACTIVE without deleting membership
65. A9 creates numeric match ID for 35
66. A9 creates numeric match ID for 28
67. race member joins match 1793527320
68. race member joins match 1793527321
69. parallel two-match A13 deletion and real withdrawal round 1
70. multi-match Saga CLEANED/WITHDRAWN and both matches deleted round 1
71. A9 creates numeric match ID for 35
72. A9 creates numeric match ID for 28
73. race member joins match 1793527322
74. race member joins match 1793527323
75. parallel two-match A13 deletion and real withdrawal round 2
76. multi-match Saga CLEANED/WITHDRAWN and both matches deleted round 2
77. A9 creates numeric match ID for 35
78. A9 creates numeric match ID for 28
79. race member joins match 1793527324
80. race member joins match 1793527325
81. parallel two-match A13 deletion and real withdrawal round 3
82. multi-match Saga CLEANED/WITHDRAWN and both matches deleted round 3
83. A9 creates numeric match ID for 28
84. facility outage does not break snapshot detail
85. facility outage rejects create with contract 503
86. facility outage rejects update without changing data
87. actual I2 user outage returns nickname fallback
88. actual I1 user outage blocks participant
89. A16 does not call unavailable user service
90. A9 creates numeric match ID for 28
91. I2 profile lookup recovers after actual user restart
92. I1 join recovers after actual user restart
93. A12 rejects actual inactive facility
94. A12 inactive facility leaves persisted data unchanged
95. A12 rejects actual outside-hours update
96. A12 outside-hours update leaves persisted data unchanged
97. MariaDB deadlock counter remains zero
