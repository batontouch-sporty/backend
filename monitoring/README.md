# 로컬 Prometheus와 Grafana 대시보드

이 폴더는 루트 `docker-compose.yml`과 독립적으로 Prometheus만 실행합니다. Grafana 실행 설정은 루트 Compose 통합 작업에서 관리합니다. 기본 수집 대상은 **Prometheus 자기 자신**(`localhost:9090`, 컨테이너 내부 주소)입니다. 앱 서비스는 아직 수집하지 않습니다. 호스트의 9090 포트는 `127.0.0.1`에만 바인딩합니다.

아래 명령은 **백엔드 저장소 루트에서 Windows PowerShell로** 실행합니다. Docker daemon이 실행 중이고 호스트 9090 포트가 비어 있어야 합니다.

## 설정 검사와 실행

```powershell
docker compose -f .\monitoring\compose.yml config
docker compose -f .\monitoring\compose.yml run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose -f .\monitoring\compose.yml up -d
docker compose -f .\monitoring\compose.yml ps
```

`config`는 Compose 구성을, `promtool check config`는 Prometheus 설정 문법을 검사합니다. 검사가 통과해도 실제 수집 성공을 뜻하지는 않습니다. 실행 후 첫 수집까지 최대 수십 초 기다린 다음 아래 응답을 확인합니다.

## 자체 대상과 직접 API 확인

```powershell
$query = [uri]::EscapeDataString('up{job="prometheus"}')
$instant = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/query?query=$query"
$instant.status
$instant.data.resultType
$instant.data.result[0].value

$end = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$start = $end - 300
$range = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/query_range?query=$query&start=$start&end=$end&step=15"
$range.status
$range.data.resultType
$range.data.result[0].values
```

정상 수집이면 즉시 조회는 `status=success`, `resultType=vector`, `value[1]=1`을, 범위 조회는 `status=success`, `resultType=matrix`와 시점별 `values`를 반환합니다. 결과 배열이 비었다면 먼저 `http://127.0.0.1:9090/targets`에서 자체 대상의 상태를 확인하고 다시 조회합니다.

위 호출은 **Prometheus 직접 API**입니다. 확정 계약 A30·A31의 Gateway 경로(`/prometheus/api/v1/query`, `/prometheus/api/v1/query_range`)와 ADMIN 권한 검사를 확인한 것이 아닙니다.

## 중지

```powershell
docker compose -f .\monitoring\compose.yml down
```

이 로컬 구성에는 데이터 보존용 볼륨을 정의하지 않았습니다. 컨테이너를 내렸다가 다시 만들면 이전 시계열이 보존되지 않을 수 있습니다.

## 후속 연동 경계

- **인프라·서비스 담당:** 각 앱에 Prometheus 레지스트리 의존성 및 `/actuator/prometheus` 노출을 추가하고, 실행 위치에 맞는 수집 주소·접근 범위를 정합니다. 현재 설정에는 앱 수집 대상이 없습니다.
- **Gateway 담당:** JWT `ADMIN` 검사와 A30·A31 두 경로만 허용하는 중계, 나머지 Prometheus API 및 서비스 Actuator 경로의 외부 차단을 구현·검증합니다.
- **서비스·프런트 담당:** user/match 누적 통계 A28·A29와 필요한 맞춤 지표를 별도로 구현하고, 실제로 노출된 지표명·단위를 확인한 뒤 프런트 조회·표시에 연결합니다.

서비스 주소, 지표명 및 운영 보관 정책은 담당자와 합의한 뒤 별도 작업에서 확정합니다. 이 폴더만으로 앱 서비스 수집이나 Gateway 경유 호출은 완료되지 않습니다.

## Grafana 대시보드 5종

`grafana/dashboards/`의 JSON을 Grafana의 Dashboards > New > Import에서 각각 불러옵니다.

| 파일 | 화면 |
| --- | --- |
| sporty-service-overview.json | 서비스 개요 |
| sporty-service-communication.json | 서비스 간 통신 |
| sporty-kafka.json | Kafka |
| sporty-jvm-resources.json | JVM 리소스 |
| sporty-domain-ai.json | 도메인 AI |

현재 범위는 패널 배치·유형·단위·조회식 초안입니다. 수집되지 않는 지표는 데이터 없음으로 표시됩니다.
서비스별 실측 연동, 부하·장애 시연, Slack 알림은 별도 작업입니다.
지표 이름과 라벨의 가정 및 커스텀 Counter 제안은 `grafana/dashboards/README.md`를 확인합니다.

## 루트 Compose 통합 시 연결 조건

Grafana와 Prometheus의 루트 Compose 통합은 인프라 담당과 조율합니다.
현재 `monitoring/compose.yml`에는 Grafana 실행 설정이 없습니다.

- Grafana와 Prometheus가 같은 Docker 네트워크에 있어야 합니다.
- 데이터 소스 주소: `http://prometheus:9090`
- 데이터 소스 UID: `sporty-prometheus` (대시보드 JSON에서 사용)
- 루트 Compose에서 `./monitoring/grafana/provisioning`을
  `/etc/grafana/provisioning`에 읽기 전용으로 마운트하면 데이터 소스 설정을 불러올 수 있습니다.
- 로그인 계정은 실행 설정에서 정합니다. 예시 포트·비밀번호 변수는 `.env.example`에 있으며
  실제 비밀번호는 Git에서 제외된 `.env`로 관리합니다.
- 현재 대시보드는 수동 Import 방식입니다. JSON 자동 등록에는 별도의 dashboard provider 설정과
  대시보드 폴더 마운트가 필요하며, 데이터 소스 마운트만으로 대시보드까지 자동 등록되지는 않습니다.
- 실제 접속 포트·볼륨은 루트 Compose 통합 결과를 기준으로 확인합니다.
- Gateway의 ADMIN 검사와 Prometheus 중계는 별도 검증 대상입니다.
