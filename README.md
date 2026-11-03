# Sporty_v2 Backend

AI 기반 생활체육 매칭 플랫폼 Sporty_v2의 백엔드 저장소입니다. 서비스별로 폴더를 나눠 관리합니다.

| 서비스 | 포트 | 역할 |
| --- | --- | --- |
| discovery-service | 8761 | Eureka 서비스 등록 |
| config-service | 8888 | 설정 중앙화 ([config-repo](https://github.com/batontouch-sporty/config-repo)) |
| gateway-service | 8000 | 단일 진입점, 라우팅, 인증 필터 |
| user-service | 8081 | 회원, 인증, 프로필 |
| match-service | 8082 | 매칭 생성, 참가, 상태 관리 |
| facility-service | 8083 | 공공체육시설 조회 |
| ai-service | 8084 | AI 검색, 초안, 추천 |
| notification-service | 8085 | 알림 |

- 기술 스택: Java 17, Spring Boot 3.5.x, Spring Cloud 2025.0.x, MariaDB, Redis, Kafka
- 비밀값은 `.env`에만 두고 커밋하지 않습니다. 예시는 `.env.example`에 적습니다.

## 실행

```bash
cp .env.example .env
openssl rand -base64 48                     # 결과를 .env의 JWT_SECRET_KEY에 넣는다
docker compose up -d --build --wait         # 전체
docker compose up -d mariadb redis kafka    # 인프라만
```

호스트에 열리는 앱 포트는 기본값 기준 Gateway 8000과 Config Server 8888이고(`.env`의 `GATEWAY_HOST_PORT`·`CONFIG_HOST_PORT`로 변경), 127.0.0.1에만 바인딩합니다. 기동 직후 약 1분은 Gateway가 503을 줄 수 있습니다.

### 설정과 IDE 실행

서비스 설정은 [config-repo](https://github.com/batontouch-sporty/config-repo)에 있고, 각 서비스의 `application.yml`에는 이름·포트·기본 프로필(`local`)·Config 주소만 있습니다(ai-service는 OpenAI 키 기본값도 둡니다). 서비스는 Config Server에 붙지 못하면 기동하지 않습니다. 설정을 바꿀 때는 config-repo에 PR을 올립니다.

IDE에서 서비스를 띄울 때는 Compose로 인프라와 config-service를 먼저 띄웁니다. 프로필을 따로 주지 않으면 `local`로 뜨고 `.env`의 포트로 붙습니다. Compose의 discovery-service는 호스트에 포트를 열지 않아서, IDE로 띄운 서비스는 Eureka에 등록되지 않습니다. 단독 기동과 자기 API 확인까지만 됩니다.

```bash
docker compose up -d --wait mariadb redis kafka zipkin config-service
./gradlew :user-service:bootRun
```

DB 스키마는 MariaDB 볼륨이 비어 있을 때만 `docker/mariadb/`의 스크립트로 만들어집니다. 기존 볼륨이 있으면 `docker compose down -v`로 지운 뒤 다시 올립니다(DB 데이터가 모두 삭제됩니다).

## 설정 새로고침 (Spring Cloud Bus)

config-repo 값을 바꾼 뒤 config-service에 `POST /actuator/busrefresh`를 한 번 보내면 RabbitMQ를 거쳐 서비스 6개(gateway·user·match·facility·ai·notification)가 재시작 없이 설정을 다시 읽습니다. `busrefresh`는 config-service에만 열려 있고 Gateway 라우트가 없어 외부에서 부를 수 없습니다.

- RabbitMQ 계정은 `.env`의 `RABBITMQ_USER`·`RABBITMQ_PASSWORD`입니다(비우면 로컬 전용 기본값 guest를 씁니다. 운영에서는 반드시 지정합니다). 관리 화면은 [http://localhost:15672](http://localhost:15672)입니다.
- 로그 레벨(`logging.level.*`)과 `@ConfigurationProperties` 값은 바로 바뀝니다. DB 연결·포트처럼 기동 때만 읽는 값은 재시작해야 바뀝니다.

로그 레벨로 확인하는 순서입니다(코드 변경 없음).

```bash
# 1. 지금 값 확인 (Gateway는 Actuator 포트가 8001이다)
docker compose exec user-service curl -s localhost:8081/actuator/loggers/com.example.sporty
# {"configuredLevel":"INFO","effectiveLevel":"INFO"}

# 2. config-repo application.yml의 logging.level.com.example.sporty를 DEBUG로 바꿔 main에 머지한다

# 3. 새로고침 한 번 (204)
curl -i -X POST http://localhost:8888/actuator/busrefresh

# 4. 다시 확인하면 DEBUG. 서비스를 재시작하지 않았다
docker compose exec user-service curl -s localhost:8081/actuator/loggers/com.example.sporty
```

되돌릴 때는 `INFO`로 바꿔 다시 새로고침합니다. 서비스 로그에는 `Keys refreshed [logging.level.com.example.sporty]`가 남습니다.

## Zipkin 로컬 실행

처음 실행할 때는 예시 파일을 복사해 로컬 환경변수 파일을 준비합니다. 기존 `.env`가 있다면 덮어쓰지 말고 `ZIPKIN_HOST_PORT=9411`만 추가합니다. `JWT_SECRET_KEY`가 비어 있으면 `openssl rand -base64 48`의 출력값을 직접 넣습니다. `.env`는 Git에 커밋하지 않습니다.

```bash
cp -n .env.example .env
docker compose config --quiet
docker compose up -d --wait zipkin
docker compose ps zipkin
ZIPKIN_ADDRESS="$(docker compose port zipkin 9411)"
curl -L --fail --silent --show-error --output /dev/null \
  --write-out 'HTTP %{http_code}\n' "http://${ZIPKIN_ADDRESS}/"
```

Zipkin UI의 기본 접속 주소는 [http://localhost:9411](http://localhost:9411)입니다. 호스트의 9411 포트를 이미 사용 중이라면 `.env`에서 `ZIPKIN_HOST_PORT`를 사용 가능한 포트로 변경하고, 변경한 포트로 접속합니다.

현재 Zipkin은 메모리 저장 방식이므로 컨테이너를 재시작하면 추적 기록이 사라집니다. 이번 구성은 Zipkin 실행 환경만 추가하며, 실제 서비스와 추적 데이터는 후속 Spring 서비스 연동 후 확인할 수 있습니다.


## 모니터링과 운영 알림

Prometheus·Grafana·Alertmanager도 루트 Compose의 전체 실행에 포함됩니다.
경보 구성만 실행하려면 `docker compose up -d prometheus alertmanager`를 사용합니다.
Grafana 비밀번호는 `.env`의 `GRAFANA_ADMIN_PASSWORD`, Discord 웹후크는 `DISCORD_WEBHOOK_URL`로 설정합니다.
웹후크가 없으면 Alertmanager는 정상 기동하고 Discord 전송만 비활성화됩니다.
웹후크를 추가·변경한 뒤에는 `docker compose up -d --force-recreate alertmanager`로 반영합니다.
자세한 실행법과 검증 범위는 [모니터링 안내](monitoring/README.md)와 [경보 안내](monitoring/alertmanager/README.md)를 참고합니다.
