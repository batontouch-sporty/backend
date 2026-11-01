# 로컬 Prometheus와 Grafana 대시보드

Prometheus와 Grafana는 루트 `docker-compose.yml`로 서비스와 함께 실행합니다. 두 포트는 `127.0.0.1`에만 바인딩합니다.

## 실행

아래 명령은 **백엔드 저장소 루트에서 Windows PowerShell로** 실행합니다. `.env`에 `GRAFANA_ADMIN_PASSWORD`가 없으면 Grafana만 시작되지 않습니다.

```powershell
docker compose run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose up -d --build
docker compose ps
```

| 화면 | 주소 |
| --- | --- |
| Prometheus 수집 대상 | http://127.0.0.1:9090/targets |
| Grafana | http://127.0.0.1:3001 (계정 `admin`, 비밀번호 `GRAFANA_ADMIN_PASSWORD`) |

포트는 `.env`의 `PROMETHEUS_HOST_PORT`, `GRAFANA_HOST_PORT`로 바꿉니다.

## 수집 구조

- user·match·facility·ai·notification·gateway 6개 서비스와 Prometheus 자신을 수집합니다. discovery·config는 수집하지 않습니다.
- 지표 노출과 `application` 공통 태그는 config-repo의 `application.yml`에 있습니다.
- gateway-service의 Actuator는 관리 포트 8001에서만 응답합니다. 8001은 호스트에 게시하지 않습니다.
- 외부에서 8000번으로는 `/<서비스>/actuator/health`만 열려 있고, Prometheus는 ADMIN 토큰으로 `/prometheus/api/v1/query`, `/prometheus/api/v1/query_range`만 조회합니다.

## 중지

```powershell
docker compose down
```

Prometheus와 Grafana 데이터를 보존하는 이름 있는 볼륨은 정의하지 않았습니다. 컨테이너를 다시 만들면 이전 시계열이 보존되지 않을 수 있습니다.

## Grafana 대시보드 5종

`grafana/dashboards/`의 JSON은 Grafana 시작 때 자동으로 등록됩니다. 화면에서 고친 내용은 저장되지 않으므로 JSON을 고칩니다.

| 파일 | 화면 |
| --- | --- |
| sporty-service-overview.json | 서비스 개요 |
| sporty-service-communication.json | 서비스 간 통신 |
| sporty-kafka.json | Kafka |
| sporty-jvm-resources.json | JVM 리소스 |
| sporty-domain-ai.json | 도메인 AI |

현재 범위는 패널 배치·유형·단위·조회식 초안입니다. 수집되지 않는 지표는 데이터 없음으로 표시됩니다.
서비스별 실측 연동과 부하·장애 시연은 별도 검증이 필요합니다. 운영 알림은 Discord로 연결합니다.
지표 이름과 라벨의 가정 및 커스텀 Counter 제안은 `grafana/dashboards/README.md`를 확인합니다.

## 대시보드 형식과 Grafana 버전

- 대시보드 5개는 모두 `dashboard.grafana.app/v2` 리소스 형식입니다.
- 작성 및 로컬 Import 확인 환경은 **Grafana 13.3.0**입니다. 팀 실행 환경도 이 버전으로 맞추는 것을 기준으로 합니다.
- 13.3.0 기본 실행 환경에서 별도 기능 토글을 추가하지 않고 사용했습니다.
  이전 버전에서의 v2 Import 호환성은 검증하지 않았습니다. 이전 버전을 사용해야 한다면
  해당 버전에서 지원하는 형식으로 다시 내보내고 Import를 검증해야 합니다.
- `metadata.name`은 대시보드의 고정 식별자로 유지합니다.
  인스턴스 전용 UUID(`metadata.uid`), 수정 버전, 생성 시각, 내부 숫자 ID 및 작성자 정보는 제거했습니다.
- 위 UUID를 다른 대시보드의 spec에서 참조하지 않는지 확인했습니다.
  데이터 소스 UID인 `sporty-prometheus`는 별개이며 유지해야 합니다.
- 대시보드 스키마 안내: https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/view-dashboard-json-model/

## 장애 경보와 Discord 알림 (#37)

경보 규칙 5종과 Alertmanager 설정은 [alertmanager/README.md](alertmanager/README.md)를 참고합니다.
서비스 수집은 루트 Compose의 `sporty-services` job과 `application` 라벨을 그대로 사용합니다.
규칙 파일은 루트 Prometheus에 마운트하며, Discord 알림을 사용할 때 추가 Compose를 함께 지정합니다.

```powershell
docker compose -f docker-compose.yml -f monitoring/compose.alerting.yml up -d prometheus alertmanager
```

실제 Discord 웹후크는 Git에서 제외된 `.env`의 `DISCORD_WEBHOOK_URL`로만 전달합니다.
