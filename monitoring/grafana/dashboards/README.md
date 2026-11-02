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
| kafka_consumergroup_lag | Kafka Exporter v1.10.0 수집 연결. `consumergroup`·`topic`·`partition` 기준. 임시 그룹에서 0→2→0 실측; 현재 Notification 그룹은 커밋 오프셋이 없어 Lag 시계열 미관측 |
| kafka_consumer_fetch_manager_records_lag_max | Consumer 내부 fetch 지표 시계열의 NaN 관측. Kafka 대시보드 panel-4는 이 지표 대신 Exporter의 Group Offset 기준 파티션 Lag 사용 |
| sporty_kafka_records_processed_total | Notification 신규 이벤트의 DB 커밋 후 Counter 구현·테스트 확인. `topic`·`group` 라벨. 실행 중인 서비스의 신규 코드 배포·Prometheus 실측은 별도 검증 |
| sporty_kafka_dlt_published_total | DLT 브로커 ACK 후 Counter 구현·테스트 확인. 원본 `topic` 라벨. 실행 중인 서비스의 신규 코드 배포·Prometheus 실측은 별도 검증. DLT 잔량 아님 |
| sporty_match_created_total / sporty_match_joined_total | 매치 생성·참가 Counter 계측 확인 전. Grafana 패널 존재가 구현 완료를 뜻하지 않음 |
| sporty_withdrawal_saga_results_total | user-service Counter 구현됨. 커밋 후 최종 상태 확정 시 1회 기록. outcome=success/rejected/timed_out |
| sporty_withdrawal_saga_late_results_total | user-service Counter 구현됨. 시간 초과 뒤 도착한 결과를 Final과 별도로 기록. outcome=cleaned/rejected |
| ai_request_seconds_count/sum | ai-service Agent Timer 구현됨. type=search/draft/recommend, outcome=success/fallback/rejected/error. PR #92에서 기존 400 success 오분류를 수정하여 4xx AiException은 rejected, 5xx와 예상하지 못한 RuntimeException은 error로 기록. Agent 실행 전 Controller Validation 실패는 포함되지 않을 수 있으므로 HTTP 전체 요청 지표는 아님 |

2026-11-02 Prometheus 조회에서 Saga Final 3개·Late 2개·AI Timer 1개·CB 상태 12개·CB 호출 Timer 6개 시계열과 Match Service `up=1`을 확인했습니다. 이는 시계열 존재 증거이며 Saga·AI 전체 E2E나 CB OPEN/HALF_OPEN 장애 전환의 실측 완료를 뜻하지 않습니다. Feign HTTP count/sum과 기존 Discovery HTTP Timer는 별도로 확인했습니다.

Kafka Exporter는 `danielqsj/kafka-exporter:v1.10.0`이며 Compose 내부 `kafka:29092`에 접속하고 Prometheus `kafka-exporter` job이 내부 `kafka-exporter:9308/metrics`를 15초마다 수집합니다. Exporter 포트는 호스트에 공개하지 않습니다. Kafka Lag는 Exporter가 Kafka의 그룹 커밋 오프셋과 토픽 로그 끝을 바탕으로 내보내는 파티션별 `kafka_consumergroup_lag`입니다. 그룹·토픽 합계는 `sum by (consumergroup, topic) (kafka_consumergroup_lag)`이며 `SportyKafkaConsumerLagHigh` 경보도 동일한 합계가 100을 초과한 상태가 2분 지속될 때 평가합니다. 이 값은 Kafka Consumer 프로세스 내부 fetch lag 지표와 측정 주체가 다릅니다. Exporter 기본 `offset.show-all=true`는 Consumer 중단 뒤에도 커밋 오프셋이 남은 그룹의 Lag 수집을 허용하지만, Broker 접근과 관련 토픽·오프셋이 필요합니다.

2026-11-02 Prometheus API에서 `up{job="kafka-exporter"}=1`, `kafka_brokers=1`, 기존 6개 `sporty-services`의 `up=1`, Lag 경보 규칙 `health=ok`와 Alertmanager 연결을 확인했습니다. `notification-service` 그룹 멤버 4개는 관측됐지만, 네 토픽의 CURRENT-OFFSET이 모두 `-`이고 LOG-END-OFFSET은 0이라 Lag 시계열과 그룹·토픽 합계는 아직 없습니다. `match.starting-soon.v1` 토픽도 현재 Broker에 없습니다. 2026-11-03 임시 테스트 토픽·그룹에서 커밋 오프셋 1/로그 끝 1/Lag 0 → 1/3/2 → 3/3/0을 Kafka CLI와 Prometheus에서 확인하고 테스트 자원을 삭제했습니다. 실제 `consumergroup`·`topic`·`partition` 레이블도 확인했지만 Notification 그룹의 첫 정상 커밋과 경보 발생·복구 E2E는 미검증입니다. Exporter의 `up=1`만으로 Broker 연결을 보장하지 않으므로 `kafka_brokers`도 함께 확인합니다. DLT 발행 성공 Counter는 전송 횟수이며 Consumer Lag나 DLT 잔량을 뜻하지 않습니다.

Kafka 대시보드 panel-1은 원본 토픽별 DLT 브로커 ACK 성공 Counter의 선택 기간 `increase`, panel-5는 같은 Counter의 초당 `rate`입니다. 둘 다 DLT 잔량이 아니며 재시작·수집 간격에 따라 증가량은 추정 소수가 될 수 있습니다. panel-2는 신규 이벤트가 DB에 커밋된 뒤 증가하는 처리 성공 Counter의 토픽·그룹별 초당 `rate`로, 중복 수신과 롤백은 제외합니다. panel-3은 Exporter의 그룹·토픽별 파티션 Lag 합계로 기존 `SportyKafkaConsumerLagHigh` 경보와 같은 집계 기준이고, panel-4는 같은 파티션 Lag의 그룹·토픽별 최댓값입니다. panel-4는 기존 Consumer 인스턴스별 fetch 지표에서 Exporter 기반 Group Offset 지표로 의미를 변경했습니다. 커밋 오프셋이 없는 경우 No Data를 0으로 바꾸지 않습니다. 2026-11-03 Grafana 프로비저닝 재로드 후 API에서 5개 패널의 제목·쿼리 반영을 확인했고, panel-4의 임시 그룹 과거 값 0·2를 Grafana 데이터 소스 API에서 확인했습니다. 1~3단계 Counter의 코드·테스트는 검증됐지만 실행 중인 Notification 컨테이너에는 아직 반영되지 않아 운영 Prometheus의 Counter 값과 현재 Grafana 표시, Notification 그룹 Lag 및 경보 발생·복구 E2E는 별도 검증입니다.

통신 대시보드의 CB Timer는 보호된 호출을 기록하고, Feign HTTP Timer는 실제 HTTP 요청을 기록합니다. CB가 차단한 요청은 HTTP 호출로 나가지 않을 수 있습니다. Feign panel-6~8은 `increase(...[$__range])`로 선택 기간의 증가량·평균·5xx 비율을 계산합니다. HTTP 404는 5xx 분자에 포함하지 않으며 타임아웃·연결 오류도 HTTP 5xx 응답으로 기록되지 않을 수 있습니다. 기간 내 요청 증가량이 있되 5xx가 없으면 0%, 요청 증가량이 없으면 평균과 5xx 비율은 데이터 없음입니다. Counter 첫 수집값이 이미 1이면 `increase()`는 그 첫 요청을 소급하지 못해 누적값 1과 기간 증가량 0이 함께 나타날 수 있습니다.

Saga Final과 Late의 선택 기간 stat은 `increase()`로 계산한 건수이며, 추이의 `rate()`는 초당 발생량입니다. Frontend PR #31도 선택 기간 증가량을 표시하지만 Final의 success/rejected/timed_out과 Late의 cleaned/rejected를 각각 분리합니다. Grafana의 Final 보상 stat은 rejected와 timed_out의 합계입니다.
AI 직접 성공률은 업무 거절을 제외한 `success / (success + fallback + error)`이며 rejected만 발생하거나 계산 대상 요청이 없는 구간에는 계산값이 없습니다. Grafana의 type·outcome별 초당 처리량과 Timer 평균에는 네 outcome을 모두 포함하며, 평균은 동일 필터의 `_sum / _count`로 초(s) 단위로 표시합니다. Frontend PR #31은 별도 변경 전까지 세 outcome(success/fallback/error) 기준으로 세 type의 전체 가중 평균을 밀리초(ms)로 표시합니다. 집계축·시간 창이 다르면 값도 달라질 수 있습니다. 네 outcome의 코드 구현은 확인했지만 실제 rejected 시계열과 전체 E2E는 별도 검증 사항입니다.

JVM 패널은 application 공통 태그와 instance 수집 라벨이 필요합니다.
도메인 증가량은 Counter의 선택 기간 추정 증가분이며 회계용 정확한 DB 집계가 아닙니다.
사용자 ID·이메일·Saga ID·매치 ID는 고카디널리티 메트릭 라벨로 추가하지 않습니다.
