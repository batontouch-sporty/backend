# Grafana 대시보드

각 JSON은 Grafana 시작 때 파일 provider로 자동 등록됩니다.
데이터 소스 UID는 기존 자동 등록한 `sporty-prometheus`입니다.
다른 환경에서는 해당 UID로 Prometheus를 등록하거나 JSON의 UID를 변경합니다.
대시보드 UID는 파일마다 달라 서로 덮어쓰지 않습니다.

Saga·AI와 Match Service Circuit Breaker·Feign HTTP 패널은 수집된 계측에 연결했습니다. 프론트엔드 Feign·Kafka 연동은 별도 작업입니다.
지표 미연결 시 데이터 없음이 정상입니다. 모든 패널에 가짜 0 또는 UP을 채우지 않습니다.
쿼리 문법 검사는 실제 서비스 지표가 생성되거나 업무 의미가 검증되었다는 뜻이 아닙니다.
시각적 임계값은 Alertmanager/Discord 경보 규칙이 아닙니다.

## 지표 구현 및 검증 상태

| 이름 | 기록 기준과 라벨 |
| --- | --- |
| sporty_feign_http_requests_seconds_count/sum | Match의 회원·시설 Feign HTTP Timer 수집 확인. `application=match-service`, `job=sporty-services`, `clientName`, `http_method`, `http_status_code`, `http_url`, `error`, `instance`로 구분. 2026-11-02 Prometheus 조회에서 MemberClient HTTP 200 count=1, FacilityClient HTTP 404 count=1, 양쪽 sum 확인. 통신 대시보드 panel-6~8은 두 Client만 선택 |
| http_client_requests_seconds_count/sum | Match의 Eureka Discovery RestTemplate HTTP Timer 수집 확인. `client_name=discovery-service`이며 순수 Feign 또는 CB 보호 호출과 합산하지 않음 |
| resilience4j_circuitbreaker_state | Match의 `MemberClientgetLong`·`FacilityClientgetLong` 회로에서 application·job·instance·name·group·state 시계열 수집 확인. `MemberClientprofilesList`는 기본 Feign CB 이름 생성 규칙에서 도출했으며 기존 Mock HTTP 테스트는 profiles 호출을 검증함. 운영 Prometheus 시계열은 아직 미관측. 통신 대시보드는 세 회로를 선택하며 미관측 회로에 가짜 0을 만들지 않음. state는 소문자이며 값 1이 해당 시점의 활성 상태. CLOSED 관측, OPEN/HALF_OPEN 전환은 미검증 |
| resilience4j_circuitbreaker_calls_seconds_count/sum | 기존 두 회로의 CB 보호 호출 Timer 수집 확인. 프로필 회로는 수집 대기. kind=successful/failed/ignored이며 선택 기간 호출량은 `increase(count)`, 평균 처리시간은 같은 필터의 `rate(sum)/rate(count)`로 계산. 순수 Feign HTTP 시간이나 차단된 호출량이 아님 |
| resilience4j_circuitbreaker_failure_rate | 기존 두 회로의 Gauge 수집 확인. 프로필 회로는 수집 대기. 최소 호출 수 미충족 시 `-1`이며 통신 대시보드에서는 제외하고 0%로 바꾸지 않음 |
| resilience4j_circuitbreaker_not_permitted_calls_total | 회로가 차단한 호출의 별도 Counter 수집 확인. CB Timer 호출량에 포함하지 않음 |
| kafka_consumergroup_lag | 별도 Exporter 필요. 현재 수집 미확인. consumergroup·topic·partition 기준 검증 대기 |
| kafka_consumer_fetch_manager_records_lag_max | 실제 바인더 출력 이름과 수집 여부 미검증. 최대 파티션 Lag이며 그룹 전체 Lag과 구분 |
| sporty_kafka_records_processed_total | 커스텀 처리량 계측 미구현. topic·group 라벨 제안 |
| sporty_kafka_dlt_published_total | 커스텀 DLT 발행 계측 미구현. topic 라벨 제안. DLT 잔량 아님 |
| sporty_match_created_total / sporty_match_joined_total | 매치 생성·참가 Counter 계측 확인 전. Grafana 패널 존재가 구현 완료를 뜻하지 않음 |
| sporty_withdrawal_saga_results_total | user-service Counter 구현됨. 커밋 후 최종 상태 확정 시 1회 기록. outcome=success/rejected/timed_out |
| sporty_withdrawal_saga_late_results_total | user-service Counter 구현됨. 시간 초과 뒤 도착한 결과를 Final과 별도로 기록. outcome=cleaned/rejected |
| ai_request_seconds_count/sum | ai-service Timer 구현됨. type=search/draft/recommend, outcome=success/fallback/error. Agent 처리 시간 기준이며 일부 HTTP 400도 success로 기록하므로 HTTP 성공률이 아님 |

2026-11-02 Prometheus 조회에서 Saga Final 3개·Late 2개·AI Timer 1개·CB 상태 12개·CB 호출 Timer 6개 시계열과 Match Service `up=1`을 확인했습니다. 이는 시계열 존재 증거이며 Saga·AI 전체 E2E나 CB OPEN/HALF_OPEN 장애 전환의 실측 완료를 뜻하지 않습니다. Feign HTTP count/sum과 기존 Discovery HTTP Timer는 별도로 확인했습니다.

통신 대시보드의 CB Timer는 보호된 호출을 기록하고, Feign HTTP Timer는 실제 HTTP 요청을 기록합니다. CB가 차단한 요청은 HTTP 호출로 나가지 않을 수 있습니다. Feign panel-6~8은 `increase(...[$__range])`로 선택 기간의 증가량·평균·5xx 비율을 계산합니다. HTTP 404는 5xx 분자에 포함하지 않으며 타임아웃·연결 오류도 HTTP 5xx 응답으로 기록되지 않을 수 있습니다. 기간 내 요청 증가량이 있되 5xx가 없으면 0%, 요청 증가량이 없으면 평균과 5xx 비율은 데이터 없음입니다. Counter 첫 수집값이 이미 1이면 `increase()`는 그 첫 요청을 소급하지 못해 누적값 1과 기간 증가량 0이 함께 나타날 수 있습니다.

Saga Final과 Late의 선택 기간 stat은 `increase()`로 계산한 건수이며, 추이의 `rate()`는 초당 발생량입니다. Frontend PR #31도 선택 기간 증가량을 표시하지만 Final의 success/rejected/timed_out과 Late의 cleaned/rejected를 각각 분리합니다. Grafana의 Final 보상 stat은 rejected와 timed_out의 합계입니다.
AI 직접 성공률은 `success / (success + fallback + error)`이며 요청이 없는 구간에는 계산값이 없습니다. Grafana는 type별 성공률과 type·outcome별 평균을 초(s)로 표시하고, Frontend PR #31은 세 type의 전체 가중 평균을 밀리초(ms)로 표시합니다. 집계축·시간 창이 다르면 값도 달라질 수 있습니다. Agent의 예외별 outcome 정확성은 별도 Backend 검증 사항이며 실제 E2E 완료로 간주하지 않습니다.

JVM 패널은 application 공통 태그와 instance 수집 라벨이 필요합니다.
도메인 증가량은 Counter의 선택 기간 추정 증가분이며 회계용 정확한 DB 집계가 아닙니다.
사용자 ID·이메일·Saga ID·매치 ID는 고카디널리티 메트릭 라벨로 추가하지 않습니다.
