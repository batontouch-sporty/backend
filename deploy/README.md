# 배포 구성

AWS EC2에 올리는 두 가지 구성이다. 기본은 **A. K8s + infra EC2**이고, K8s가 준비되지 않으면 **B. 단일 서버 전체 Compose**로 바꾼다.

| 구성 | 서버 | 파일 |
| --- | --- | --- |
| A. infra EC2 | t3.large 1대 (DB·Redis·Kafka·RabbitMQ·모니터링). 앱은 K8s(master 1 + worker 2) | `deploy/infra/compose.yml`, `deploy/infra/prometheus.yml` |
| B. 단일 서버 | t3.xlarge 1대에 전체 | 루트 `docker-compose.yml` + `deploy/single/compose.yml` |

두 구성 모두 루트 `docker-compose.yml`의 서비스 정의를 그대로 쓰고 다른 점만 덮어쓴다. 루트 파일과 로컬 실행 방법은 바뀌지 않는다.
명령은 모두 **backend 저장소 루트**에서 실행한다.

## 필요한 `.env` 키

`.env.example`을 `.env`로 복사해 값을 채운다. `.env`는 커밋하지 않는다.

| 키 | A. infra | B. 단일 | 비고 |
| --- | --- | --- | --- |
| `MARIADB_ROOT_PASSWORD`, `USER_APP_PASSWORD`, `MATCH_APP_PASSWORD`, `FACILITY_APP_PASSWORD`, `NOTIFICATION_APP_PASSWORD` | 필수 | 필수 | A에서는 K8s Secret의 앱 비밀번호와 같아야 한다 |
| `JWT_SECRET_KEY` | 필수 | 필수 | A는 쓰지 않지만 루트 파일 전체를 해석해서 비어 있으면 실패한다 |
| `GRAFANA_ADMIN_PASSWORD` | 필수 | 필수 | |
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | 필수 | 선택 | Spring Cloud Bus 계정. A는 비어 있으면 실패하고 K8s Secret `sporty-rabbitmq`와 같아야 한다. B는 비우면 `guest`. 서버에서는 `guest`가 아닌 값을 쓴다 |
| `INFRA_PRIVATE_IP` | 필수 | - | infra EC2 사설 IP |
| `DOCKERHUB_NAMESPACE`, `IMAGE_TAG` | - | 필수 | 이미지 `<네임스페이스>/sporty-<서비스>-service:<태그>` |
| `FRONTEND_IMAGE_TAG` | - | 선택 | 비우면 `IMAGE_TAG` |
| `DISCORD_WEBHOOK_URL` | 선택 | 선택 | 비우면 Discord 전송만 꺼진다 |
| `PUBLIC_DATA_API_KEY`, `OPENAI_API_KEY`, `ADMIN_*`, `CORS_ALLOWED_ORIGIN` | - | 선택 | A에서는 K8s Secret·ConfigMap에 넣는다 (`k8s/README.md`) |

## A. infra EC2

```bash
# 1. K8s 앱 수집 대상. <worker-사설-IP>·<worker1-사설-IP>·<worker2-사설-IP>를 워커 사설 IP로 바꾼다.
#    Pod가 1개인 서비스는 워커 하나만 적어도 NodePort라 수집된다. gateway·ai·notification(30101·30184·30185)은 Pod가 2개라 worker1·worker2 주소를 둘 다 적는다.
mkdir -p deploy/infra/targets
cp deploy/infra/k8s-targets.example.json deploy/infra/targets/k8s-targets.json
vi deploy/infra/targets/k8s-targets.json

# 2. 설정 검사 후 기동. 파일이 deploy/infra에 있어 .env 위치를 직접 준다.
docker compose --env-file .env -f deploy/infra/compose.yml config -q
docker compose --env-file .env -f deploy/infra/compose.yml up -d --wait
```

- 루트와 다른 점: DB·Redis·Kafka·RabbitMQ(5672)·Prometheus를 `127.0.0.1` 대신 `INFRA_PRIVATE_IP`에 연다. RabbitMQ 관리 화면 15672는 `127.0.0.1`에만 열어 SSH 터널(`ssh -L 15672:127.0.0.1:15672 <infra>`)로 본다. Kafka는 `EXTERNAL`을 `INFRA_PRIVATE_IP:9092`로 광고한다. Alertmanager는 호스트에 열지 않는다. Grafana는 시연용으로 모든 인터페이스에 연다. 앱·Zipkin·Config·Discovery는 띄우지 않는다(K8s에서 돈다).
- Prometheus는 `deploy/infra/prometheus.yml`을 쓴다. `sporty-services` job만 Compose 서비스 이름 대신 `targets/*.json`(file_sd)을 읽고, job 이름·`application` 라벨·경보 규칙은 루트와 같다. json을 고치면 재시작 없이 다시 읽는다.
- K8s 쪽 입력(`k8s/README.md`의 `sporty-infra-endpoints`): `MARIADB_HOST`·`REDIS_HOST`=`INFRA_PRIVATE_IP`, `KAFKA_BOOTSTRAP_SERVERS`=`INFRA_PRIVATE_IP:9092`, `RABBITMQ_HOST`=`INFRA_PRIVATE_IP`, `PROMETHEUS_URL`=`http://INFRA_PRIVATE_IP:9090`.
- 수집 NodePort: gateway 관리 30101, user 30181, match 30182, facility 30183, ai 30184, notification 30185. 이 Service는 `k8s/monitoring-nodeport.yaml`(PR #137, `kubectl apply -k k8s`에 포함)이 만든다. 확인: `kubectl get svc -n sporty -l app.kubernetes.io/component=metrics`.

### 보안 그룹 (infra EC2 인바운드)

| 포트 | 용도 | 허용 대상 |
| --- | --- | --- |
| 3306 | MariaDB | VPC 내부(워커) |
| 6379 | Redis | VPC 내부(워커) |
| 9092 | Kafka | VPC 내부(워커) |
| 5672 | RabbitMQ (Bus) | VPC 내부(워커) |
| 9090 | Prometheus (Gateway `/prometheus` 조회) | VPC 내부(워커) |
| 3001 | Grafana | 팀 IP (시연) |
| 22 | SSH | 팀 IP |

워커 보안 그룹에는 infra EC2에서 오는 30101, 30181~30185(Prometheus 수집)를 연다.

## B. 단일 서버

```bash
docker compose -f docker-compose.yml -f deploy/single/compose.yml config -q
docker compose -f docker-compose.yml -f deploy/single/compose.yml pull
docker compose -f docker-compose.yml -f deploy/single/compose.yml up -d --wait
```

- 루트와 다른 점: 앱 8개는 빌드 대신 Docker Hub 이미지를 받는다. 프론트(`sporty-frontend`, 80)를 추가하고 nginx가 `GATEWAY_UPSTREAM=gateway-service:8000`으로 API를 넘긴다. Gateway 8000과 Grafana 3001을 모든 인터페이스에 연다. 나머지 포트는 루트대로 `127.0.0.1`에만 열린다. nginx는 시작할 때 Gateway 주소를 한 번만 찾으므로, gateway-service 컨테이너를 다시 만들었으면 frontend도 `docker compose ... restart frontend`로 다시 띄운다.
- 브라우저가 8000으로 직접 API를 부르는 구성이면 `.env`의 `CORS_ALLOWED_ORIGIN`을 `http://<공인 IP>`로 맞춘다. nginx를 거치면 같은 출처라 필요 없다.

### A에서 B로 전환 (같은 EC2)

A(`sporty-infra` 프로젝트)와 B(`sporty` 프로젝트)는 Compose 프로젝트 이름이 달라 볼륨도 따로 쓴다(`sporty-infra_mariadb-data`, `sporty_mariadb-data`). Grafana 3001 등 같은 포트를 쓰므로 A를 먼저 내린다.

1단계: A를 내린다. `-v`를 붙이지 않아 A의 DB 볼륨은 남는다(되돌아갈 때 그대로 쓴다).

```bash
docker compose --env-file .env -f deploy/infra/compose.yml down
```

2단계는 아래 셋 중 **하나만** 실행한다. B의 DB 볼륨은 `sporty_mariadb-data`이고, 한 번 만들어지면 `up`을 다시 해도 그대로 쓴다.

방법 1: B를 처음 띄운다(B의 DB 볼륨이 아직 없음). 첫 기동 때 R10 스키마·서비스 계정이 만들어진다. 관리자 계정은 `.env`에 `ADMIN_EMAIL`·`ADMIN_PASSWORD`를 넣었을 때만 만들어진다.

```bash
docker compose -f docker-compose.yml -f deploy/single/compose.yml up -d --wait
```

방법 2: 예전에 띄운 B의 DB를 버리고 새 DB로 시작한다. `down -v`는 Alertmanager 데이터까지 지우므로 쓰지 않고 MariaDB 볼륨만 지운다.

```bash
docker compose -f docker-compose.yml -f deploy/single/compose.yml down \
  && docker volume rm sporty_mariadb-data \
  && docker compose -f docker-compose.yml -f deploy/single/compose.yml up -d --wait
```

방법 3: A에서 쌓은 데이터를 B로 옮긴다. A의 DB 볼륨이 있고 B의 DB 볼륨이 없을 때만 진행하고, 복사가 실패하면 B를 띄우지 않는다. B의 DB 볼륨이 이미 있으면 방법 2의 앞 두 줄로 먼저 지운다.

```bash
docker volume inspect sporty-infra_mariadb-data >/dev/null \
  && ! docker volume inspect sporty_mariadb-data >/dev/null 2>&1 \
  && docker volume create sporty_mariadb-data \
  && docker run --rm -v sporty-infra_mariadb-data:/from:ro -v sporty_mariadb-data:/to alpine sh -c "cp -a /from/. /to/" \
  && docker compose -f docker-compose.yml -f deploy/single/compose.yml up -d --wait \
  || { echo "중단: A의 DB 볼륨이 없거나, B의 DB 볼륨이 이미 있거나, 복사·기동에 실패했다"; false; }
```

- B로 전환한 뒤 A로 되돌아가려면 B를 `down`(볼륨 유지)으로 내리고 A를 다시 올린다. A의 DB는 1단계에서 남겨 둔 그대로다.
- `down -v`는 MariaDB와 Alertmanager 볼륨을 모두 지운다. DB만 새로 만들 때는 방법 2를 쓴다.

### 보안 그룹 (단일 서버 인바운드)

| 포트 | 용도 | 허용 대상 |
| --- | --- | --- |
| 80 | 프론트 | 시연 대상 |
| 8000 | Gateway API | 시연 대상 |
| 3001 | Grafana | 팀 IP |
| 22 | SSH | 팀 IP |

## 확인

```bash
# Gateway 경유 서비스 health. A는 Gateway NodePort 주소, B는 http://<서버>:8000
for svc in user match facility ai notification; do
  curl -fsS --max-time 5 -o /dev/null -w "${svc}-service HTTP %{http_code}\n" \
    "http://<gateway-주소>/${svc}-service/actuator/health"
done

# Prometheus 수집 대상 상태. infra에서는 INFRA_PRIVATE_IP, 단일 서버에서는 127.0.0.1
curl -s http://<prometheus-주소>:9090/api/v1/targets | grep -o '"health":"[a-z]*"' | sort | uniq -c
```

브라우저에서는 `http://<prometheus-주소>:9090/targets`(SSH 터널), `http://<서버>:3001`(Grafana)로 본다.
