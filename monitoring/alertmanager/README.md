# Discord 장애 알림

Prometheus가 경보 조건을 평가하고 Alertmanager가 Discord로 발생·복구 메시지를 보냅니다.
Grafana의 임계값 선과 별개의 경보입니다.

## 경보 기준

| 규칙 | 조건 |
| --- | --- |
| SportyServiceDown | 지표 수집 실패가 1분 지속 |
| SportyHigh5xxRate | 최근 5분 요청의 5xx 비율이 5% 초과, 2분 지속 |
| SportyHighP95Latency | 최근 5분 요청 p95가 1초 초과, 2분 지속 |
| SportyKafkaConsumerLagHigh | Consumer Group·토픽의 파티션 Lag 합계가 100 초과 |
| SportyWithdrawalSagaLateCleaned | 최근 5분 동안 늦은 CLEANED Counter가 증가 |

Actuator 요청은 HTTP 비율과 지연 계산에서 제외합니다. 요청이 없거나 지표가 없으면
성공·정상으로 꾸미지 않습니다. 현재 규칙은 '시계열 자체가 사라짐'에 대한 별도 경보를 포함하지 않습니다.
특히 Eureka 자동 발견에서 대상이 제거되면 up=0도 사라질 수 있습니다. 대상 유지나
별도의 기대 서비스 목록 기반 부재 감지는 인프라 담당과 추가 설계해야 합니다.

## 필요한 지표

- HTTP·JVM 지표의 `application` 라벨과 HTTP 응답시간 히스토그램 버킷이 필요합니다.
- `up`의 `application`은 Micrometer 공통 태그가 아니라 **Prometheus 수집 대상 라벨**입니다.
  루트 수집 설정은 `job="sporty-services"`의 static target마다 이 라벨을 붙입니다.
- Kafka 경보는 별도 Exporter의 `kafka_consumergroup_lag` 및 `consumergroup`, `topic`, `partition`을 가정합니다.
  Consumer 자체의 records_lag_max와 다릅니다. 실제 Exporter 연동 후 확인해야 합니다.
- 한 클러스터 기준입니다. 클러스터가 여러 개면 Kafka 집계와 그룹 키에 클러스터 라벨을 추가합니다.

## 비밀값 준비

저장소 루트의 `.env`에 `DISCORD_WEBHOOK_URL`을 설정합니다. 실제 URL은 커밋하지 않습니다.
`.env.example`에는 변수명과 빈 값만 둡니다.
Compose Secret의 environment 소스로 전달하고 Alertmanager는 `webhook_url_file`로 읽습니다.
Alertmanager YAML 자체에서 환경변수가 자동 치환된다고 가정하지 않습니다.
일반 YAML이나 이미지 안에 실제 웹후크를 넣지 않습니다.

## 루트 Compose 통합

루트 Compose의 Prometheus·Grafana 통합본(#50)을 사용합니다.
규칙 파일은 루트 Prometheus에 이미 마운트되어 있습니다.
추가 Compose는 Alertmanager와 Secret·볼륨만 선언하며 기존 Prometheus를 중복 실행하지 않습니다.
루트 Compose를 첫 번째로 지정하여 상대 경로를 저장소 루트 기준으로 해석합니다.

```bash
docker compose --env-file .env -f docker-compose.yml -f monitoring/compose.alerting.yml config --quiet
docker compose --env-file .env -f docker-compose.yml -f monitoring/compose.alerting.yml up -d prometheus alertmanager
```

루트의 Prometheus는 `monitoring/prometheus.yml`을 `/etc/prometheus/prometheus.yml`로
마운트하고 Alertmanager와 같은 네트워크를 사용해야 합니다.
추가 Compose를 지정하지 않으면 Discord 전송용 Alertmanager는 실행되지 않습니다.
Prometheus 자체의 지표 수집·경보 평가는 계속되지만 알림 전송에는 Alertmanager가 필요합니다.
소스 환경변수 또는 Secret 갱신 시 Alertmanager 컨테이너를 재생성해야 합니다.

Alertmanager는 기본적으로 http://localhost:9093 에만 노출됩니다. 외부 공개는 하지 않습니다.
발생 경보는 10초 동안 묶고, 상태 변경은 30초 간격으로 전달하며, 지속 장애는 4시간마다 다시 알립니다.
복구 메시지도 전송합니다. 대시보드 임계값과 달리 이 조건들은 실제 경보 평가에 사용됩니다.

## 검증

저장소 루트에서 규칙의 경계값·대기시간·복구·지표 누락 사례를 검사합니다.

```bash
docker run --rm --network none --entrypoint /bin/promtool \
  --mount "type=bind,source=$(pwd -W)/monitoring,target=/work,readonly" \
  -w /work/tests prom/prometheus:v3.15.1 test rules alert-rules.test.yml
```

위 명령은 Windows Git Bash 기준입니다. Git Bash가 컨테이너 경로를 바꾸는 경우
명령 앞에 `MSYS_NO_PATHCONV=1`을 지정합니다. PowerShell에서는 마운트 source를 절대 경로로 작성합니다.
운영 이미지 버전이 바뀌면 같은 버전의 promtool로 다시 검사합니다.

실제 알림 테스트는 테스트 채널과 발송 승인을 먼저 확인합니다.
테스트용 경보에는 `test=true` 라벨과 테스트임을 알리는 문구를 붙입니다.
Discord에 도착하는지 확인할 때는 Alertmanager의 전송 성공 카운터만으로 수신 화면까지
확인했다고 기록하지 않습니다. 최종 시연은 실제 서비스 지표로 발생·복구를 확인합니다.

## Discord 메시지 형식

발생 제목은 서비스 다운·서버 오류율 상승·응답 지연·Kafka 처리 지연·탈퇴 Saga 늦은 CLEANED로 구분합니다.
본문에 서비스 또는 Consumer Group·토픽, 장애 내용, 확인 사항과 한국 시간 기준 발생 시각을 표시합니다.
복구 제목은 `✅ [Sporty] 경보 해제`이며 해당 경보명과 복구 시각을 표시합니다.
일반 경보의 해제 본문은 `해당 경보 조건이 더 이상 충족되지 않습니다.`로 표시합니다.
늦은 CLEANED는 기존 발생 건의 정합성 확인이 별도로 필요하다는 전용 안내를 표시합니다.
실제 라벨이 없는 항목은 빈 값으로 표시하지 않고 생략합니다. 템플릿 형식은 가상 라벨로 오프라인 검증합니다.


## 탈퇴 Saga 지표 계약 (#21)

- `sporty_withdrawal_saga_results_total{outcome="success|rejected|timed_out"}`: 최종 결과 대시보드용. 기존 패널 이름을 유지합니다.
- `sporty_withdrawal_saga_late_results_total{outcome="cleaned|rejected"}`: 이미 TIMED_OUT인 Saga의 늦은 결과입니다.
- user-service가 기동 시 각 outcome을 0으로 등록합니다. 경보는 `outcome="cleaned"`의 `increase(...[5m]) > 0`만 사용합니다.
- 늦은 rejected, 최종 결과 Counter, 값이 0인 시계열은 이 경보를 발생시키지 않습니다.
- `application`별로 인스턴스를 합산합니다. Counter 재시작은 increase가 처리하며, 첫 수집 이전 사건은 기준 샘플이 없어 증가로 판별하지 못할 수 있습니다.
- 별도 `for` 대기 없이 다음 평가에서 감지하고, 기본 10초 그룹 대기 뒤 전송합니다. 수집·평가 주기도 추가로 걸립니다.
- 이 경보는 운영 확인 요청이며 자동 보상·자동 복구를 실행하지 않습니다.
- 경보 해제는 최근 5분 증가가 더 이상 관측되지 않는다는 뜻입니다. 기존 회원·매치 정합성 문제가 해결됐다는 뜻이 아닙니다. 지표 수집 단절도 해제 원인이 될 수 있으므로 서비스 다운 경보와 함께 확인합니다.
- 시연은 요청 후 5분+30초 타임아웃 → 늦은 CLEANED → WARN·Counter 증가 → 경보·Discord를 확인하고 캡처합니다. 실제 흐름 재현은 규칙 단위 테스트와 구분합니다.


## 검증 범위 (2026-11-01)

- promtool 규칙 테스트 23개: 기존 4종 14개와 늦은 CLEANED 9개. 늦은 rejected 제외, 0 등록, 지표 누락, 5분 구간 만료, Counter 리셋, 복수 인스턴스 합산을 포함합니다.
- 루트 Compose + 추가 Compose 구조, Prometheus 설정 및 규칙 5개, Alertmanager 설정과 Secret 마운트를 검사했습니다.
- 경보 5종의 발생·해제 메시지 10개를 amtool로 오프라인 렌더링했습니다.
- 격리된 Prometheus·Alertmanager와 가짜 지표 제공자·로컬 Discord 수신기로 전송 흐름을 검사했습니다. 전송 테스트에서만 집계 구간을 15초로 줄였고 실제 규칙은 5분입니다.
- 이번 검증에서 실제 Discord 전송, 실제 회원 탈퇴·5분30초 타임아웃 재현, Kafka Exporter 수집 검증은 수행하지 않았습니다. 기존 Discord 연결 테스트와 구분합니다.

서비스와 함께 실행한 뒤에는 Prometheus `/targets`에서 user-service가 UP인지, `/rules`에서 경보 5종이 로딩되는지 확인합니다.
실제 늦은 CLEANED 시나리오와 Discord 수신 화면은 통합 시연에서 추가 캡처해야 합니다.
