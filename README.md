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

호스트에 열리는 앱 포트는 Gateway 8000뿐이고 127.0.0.1에만 바인딩합니다. 기동 직후 약 1분은 Gateway가 503을 줄 수 있습니다.

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
