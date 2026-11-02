# Grafana 대시보드

각 JSON은 Grafana 시작 때 파일 provider로 자동 등록됩니다.
데이터 소스 UID는 기존 자동 등록한 `sporty-prometheus`입니다.
다른 환경에서는 해당 UID로 Prometheus를 등록하거나 JSON의 UID를 변경합니다.
대시보드 UID는 파일마다 달라 서로 덮어쓰지 않습니다.

Saga·AI 패널은 구현된 계측에 연결했습니다. Feign·Circuit Breaker 등 일부 패널은 실측 전 조회식 초안입니다.
지표 미연결 시 데이터 없음이 정상입니다. 모든 패널에 가짜 0 또는 UP을 채우지 않습니다.
쿼리 문법 검사는 실제 서비스 지표가 생성되거나 업무 의미가 검증되었다는 뜻이 아닙니다.
시각적 임계값은 Alertmanager/Discord 경보 규칙이 아닙니다.

## 지표 구현 및 검증 상태

| 이름 | 기록 기준과 라벨 |
| --- | --- |
| http_client_requests_seconds_count/sum | Match의 회원·시설 Feign 호출 기능은 구현됨. 현재 수집된 HTTP Client 시계열만으로 Feign을 식별할 수 없으며 호출 대상·상태 레이블과 운영 지표는 #87 2단계 실측 대기 |
| resilience4j_circuitbreaker_state | Match의 Circuit Breaker 기능은 구현됨. 운영 환경의 application·instance·name·state 시계열과 상태 전환은 #87 2단계 실측 대기 |
| kafka_consumergroup_lag | 별도 Exporter 필요. 현재 수집 미확인. consumergroup·topic·partition 기준 검증 대기 |
| kafka_consumer_fetch_manager_records_lag_max | 실제 바인더 출력 이름과 수집 여부 미검증. 최대 파티션 Lag이며 그룹 전체 Lag과 구분 |
| sporty_kafka_records_processed_total | 커스텀 처리량 계측 미구현. topic·group 라벨 제안 |
| sporty_kafka_dlt_published_total | 커스텀 DLT 발행 계측 미구현. topic 라벨 제안. DLT 잔량 아님 |
| sporty_match_created_total / sporty_match_joined_total | 매치 생성·참가 Counter 계측 확인 전. Grafana 패널 존재가 구현 완료를 뜻하지 않음 |
| sporty_withdrawal_saga_results_total | user-service Counter 구현됨. 커밋 후 최종 상태 확정 시 1회 기록. outcome=success/rejected/timed_out |
| sporty_withdrawal_saga_late_results_total | user-service Counter 구현됨. 시간 초과 뒤 도착한 결과를 Final과 별도로 기록. outcome=cleaned/rejected |
| ai_request_seconds_count/sum | ai-service Timer 구현됨. type=search/draft/recommend, outcome=success/fallback/error. Agent 처리 시간 기준이며 일부 HTTP 400도 success로 기록하므로 HTTP 성공률이 아님 |

Saga Final과 Late의 선택 기간 stat은 `increase()`로 계산한 건수이며, 추이의 `rate()`는 초당 발생량입니다. Frontend PR #31도 선택 기간 증가량을 표시하지만 Final의 success/rejected/timed_out과 Late의 cleaned/rejected를 각각 분리합니다. Grafana의 Final 보상 stat은 rejected와 timed_out의 합계입니다.
AI 직접 성공률은 `success / (success + fallback + error)`이며 요청이 없는 구간에는 계산값이 없습니다. Grafana는 type별 성공률과 type·outcome별 평균을 초(s)로 표시하고, Frontend PR #31은 세 type의 전체 가중 평균을 밀리초(ms)로 표시합니다. 집계축·시간 창이 다르면 값도 달라질 수 있습니다. Agent의 예외별 outcome 정확성은 별도 Backend 검증 사항이며 실제 E2E 완료로 간주하지 않습니다.

JVM 패널은 application 공통 태그와 instance 수집 라벨이 필요합니다.
도메인 증가량은 Counter의 선택 기간 추정 증가분이며 회계용 정확한 DB 집계가 아닙니다.
사용자 ID·이메일·Saga ID·매치 ID는 고카디널리티 메트릭 라벨로 추가하지 않습니다.
