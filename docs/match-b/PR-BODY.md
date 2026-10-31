## 관련 이슈

Refs #32

## 변경 내용

매칭 서비스에서 회원·시설 내부 API를 Feign으로 호출하고, 생성·수정·참가에서 사용할 검사 정책과 프로필 표시 Fallback을 별도 패키지로 구현했습니다.

- I1 회원 상태·성별, I2 프로필 CSV 일괄 조회, I3 시설 활성·운영시간 조회
- 생성/참가의 비활성 회원 오류를 각 API 명세대로 구분
- 시설 검사 후 자기 DB에 저장할 FacilitySnapshot 반환
- 회원·시설 장애는 503으로 거절, 프로필은 알 수 없음으로 대체
- Feign connect/read timeout과 메서드별 Circuit Breaker 적용

## 다른 서비스 영향

- 매칭 전용 integration 패키지와 build/application 설정만 변경
- CRUD·엔티티·Saga·Kafka·대기열·루트 Compose·DDL 변경 없음
- 현재 main에는 매칭 CRUD 및 I1/I2/I3 대상 서비스 구현이 없어 실제 서비스 연동 완료는 아님
- #10/#11에서 checkCreation/checkFacility/checkParticipant/profiles를 호출하도록 연결 필요
- 스냅샷 URL 영속화는 공식 DDL 보완 필요

## 테스트 결과

- [x] 매칭 빌드·테스트 20개 통과
- [x] 실제 Feign HTTP, JSON/CSV 계약, 타임아웃, Circuit Breaker OPEN/요청 차단 확인
- [x] 생성·참가 상태/성별 오류, 시설 운영시간·활성 검사, 프로필 Fallback 확인
- [ ] 실제 회원·시설 서비스 연동 — 대상 내부 API 구현 대기
- [ ] Gateway 경유 CRUD 및 match_db 스냅샷 저장 — #10/#11 연결 대기

## 리뷰 요청사항

- 공통 엔티티를 중복 구현하지 않았으며, CRUD 저장 전에 호출할 메서드는 FEIGN-32.md에 정리
- 회원 없음 외부 401은 PDF A14 표 기준. 404 표기 혼선은 별도 계약 정리 필요
- 동일 시작/종료 운영시간은 임의의 24시간으로 해석하지 않음. 실제 시설 계약 확인 필요
