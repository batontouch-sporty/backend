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
docker compose up -d --build --wait         # 전체
docker compose up -d mariadb redis kafka    # 인프라만
```

외부 공개 포트는 Gateway 8000뿐입니다. 기동 직후 약 1분는 Gateway가 503을 줄 수 있습니다.
