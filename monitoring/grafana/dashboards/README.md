# Grafana 대시보드 초안

각 JSON은 Grafana 시작 때 파일 provider로 자동 등록됩니다.
데이터 소스 UID는 기존 자동 등록한 `sporty-prometheus`입니다.
다른 환경에서는 해당 UID로 Prometheus를 등록하거나 JSON의 UID를 변경합니다.
대시보드 UID는 파일마다 달라 서로 덮어쓰지 않습니다.

현재 작업은 패널 배치와 조회식 초안입니다.
지표 미연결 시 데이터 없음이 정상입니다. 모든 패널에 가짜 0 또는 UP을 채우지 않습니다.
쿼리 문법 검사는 실제 서비스 지표가 생성되거나 업무 의미가 검증되었다는 뜻이 아닙니다.
시각적 임계값은 Alertmanager/Discord 경보 규칙이 아닙니다.

## 합의가 필요한 지표

| 이름 | 기록 기준과 라벨 |
| --- | --- |
| http_client_requests_seconds_count/sum | Feign 관측 연동, application·client_name·status 라벨 확인. 실제 라이브러리 출력에 맞춰 변경 |
| resilience4j_circuitbreaker_state | application·instance·name·state. 값 1인 state가 활성 상태 |
| kafka_consumergroup_lag | 별도 Exporter 필요. consumergroup·topic·partition. Consumer 중단 중 Lag 시연에 사용 |
| kafka_consumer_fetch_manager_records_lag_max | Consumer 바인더의 실제 출력 이름 확인. 최대 파티션 Lag이며 그룹 전체 Lag과 구분 |
| sporty_kafka_records_processed_total | 제안 Counter. 실제 처리 성공 후 증가. topic·group 라벨 |
| sporty_kafka_dlt_published_total | 제안 Counter. DLT 발행 성공 후 증가. topic 라벨. DLT 잔량 아님 |
| sporty_match_created_total | 제안 Counter. 매치 생성 확정 후 증가 |
| sporty_match_joined_total | 제안 Counter. 참가 확정 후 증가. 방장 포함 여부 합의 |
| sporty_withdrawal_saga_results_total | 제안 Counter. user에서 최종 상태 확정 후 1회. outcome=success/rejected/timed_out |
| ai_request_seconds_count/sum | 계획서 Timer ai.request. type·outcome=success/fallback/error. 측정 구간 합의 |

JVM 패널은 application 공통 태그와 instance 수집 라벨이 필요합니다.
도메인 증가량은 Counter의 선택 기간 추정 증가분이며 회계용 정확한 DB 집계가 아닙니다.
사용자 ID·이메일·Saga ID·매치 ID는 고카디널리티 메트릭 라벨로 추가하지 않습니다.
