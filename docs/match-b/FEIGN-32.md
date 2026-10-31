# #32 회원·시설 Feign/CB

## 작업 경계

최신 main 9938d90 기반. match.integration 패키지, match-service 의존성/설정, HTTP 통합 테스트만 추가. Saga·Kafka·대기열·CRUD·엔티티·DDL·루트 Compose 변경 없음.

## 호출 연결

- 생성 A9: checkCreation(userId,facilityId,start,end) → 회원 검사 + 시설 검사 → CreationContext.facility 스냅샷을 호성 담당 매치 생성 트랜잭션에서 저장
- 수정 A12: checkFacility(existingFacilityId,start,end) → 반환 스냅샷으로 자기 match_db 갱신
- 참가 A14: checkParticipant(userId,genderGroup) → 기존 정원/참가 중복 검사는 매치 행 잠금 트랜잭션에서 계속 수행
- 상세 A11: profiles(participantUserIds) → profileId/nickname/imageUrl. 누락/실패 시 닉네임 알 수 없음, profileId null
- I2 요청은 ids=1,2,3 CSV 한 요청. 외부 ID 대신 시설 내부 PK를 사용

## 오류 계약

- 참가 비활성 회원 400 USER_NOT_ACTIVE, 생성 비활성 회원 401 USER_WITHDRAWN
- 회원 404 → 외부 401 MATCH_USER_NOT_FOUND (PDF A14 HTTP 표 기준; 설명상의 404 표현은 확정 필요)
- 시설 404 → 404 SERVICE_NOT_FOUND, 비활성 400 FACILITY_INACTIVE
- 의존 서비스 장애/타임아웃/잘못된 필수 응답 → 503. 프로필 조회는 표시용 대체
- 운영시간 없음/동일 시작·종료는 임의의 24시간 운영으로 해석하지 않고 확인 필요로 거절. 자정 넘는 운영시간은 지원

## 실행 설정

Eureka 서비스명 user-service/facility-service가 기본. 명시 주소가 필요한 로컬 테스트는 MATCH_USER_SERVICE_URL/MATCH_FACILITY_SERVICE_URL 사용. connect 1초/read 2초, method별 CB 10회 창/최소5회/실패50%/10초 OPEN/반개방2회, TimeLimiter 3초. 내부 공통 인증 토큰 규칙은 아직 팀 계약에 없으므로 임의로 필수화하지 않음.

## 아직 연결하지 못한 항목

현재 main에는 매칭 CRUD와 실제 회원·시설 I1/I2/I3 구현이 없다. 클라이언트·정책 구현과 모의 HTTP 연동을 완료해도 실제 서비스 연동이나 엔티티 저장 완료로 표시하지 않는다. #10/#11에서 위 메서드를 연결해야 사용자 API에서 검사가 적용된다. URL 저장은 DBA 스키마 보완 필요.

## 로컬 검증 (2026-10-31)

- :match-service:test :match-service:bootJar 성공, 20개 테스트 실패/오류 0
- Feign 자체를 mock하지 않고 JDK HTTP fixture 서버를 대상으로 실제 요청/JSON 역직렬화/오류·지연 응답 검증
- I2 ids=1,2 CSV 1회 요청, 누락 프로필 알 수 없음, 생성/참가 비활성 오류 구분 검증
- read timeout과 장애 503, CB OPEN 후 추가 HTTP 요청 차단, 단건조회 CB와 프로필조회 CB 분리 검증
- 시간 경계/자정 넘는 운영/불명확한 동일 시각 운영, 생성용 시설 스냅샷 필드 검증
- 테스트 전용 MVC probe로 {code,message} 확인. 제품에는 별도 REST endpoint 추가하지 않음
- 실제 회원·시설 서비스 연결, Gateway 경유 A9/A11/A12/A14, 스냅샷 DB 저장 검증은 아직 미완료. 이슈 완료 조건을 전부 충족했다고 표시하지 않음
