# [USR-05] 토큰 재발급

POST `/api/auth/refresh`

요청 본문: `{ "refreshToken": "기존 RT" }`

성공 200: `{ "accessToken": "새 AT" }`, Cache-Control: no-store.

- 401 / INVALID_REFRESH_TOKEN: RT 누락·만료·서명/타입 오류, Redis RT 없음/불일치, 회원 없음/탈퇴 상태.
- Redis/DB 장애의 별도 503 핸들러는 사용하지 않는다. 프론트는 상태 코드만 보고 RT 실패로 단정하지 않는다.
- JSON 문법 자체가 잘못된 요청은 재발급 대상이 아니다.

## 백엔드

AuthController.refresh → AuthService.refresh → JwtProvider.validateRefreshToken으로 RT를 검증하고 회원 ID를 얻는다. RefreshTokenService.findByUserId로 Redis의 `refresh:{userId}`를 조회하여 요청 RT와 비교한다. ACTIVE 회원에게만 새 AT를 반환한다.

재발급 API는 PublicEndpoints에 등록하여 만료된 AT 때문에 차단되지 않게 한다. 인증을 생략하는 것이 아니라 RT로 검증한다.

명세에 따라 AT만 새로 발급한다. RT와 Redis TTL은 갱신하지 않는다. RT 만료 시 다시 로그인해야 한다. 로그아웃/탈퇴로 RT가 삭제되면 재발급할 수 없다. 이미 발급한 AT의 즉시 무효화는 별도 정책이다.

## 프론트

`src/api/axios.js`의 요청 인터셉터는 at를 Authorization에 넣는다. 응답 인터셉터는 401 시 rt로 재발급 후 원래 요청을 한 번만 재시도한다. 동시 요청을 묶는 별도 상태는 두지 않는다. 여러 요청이 동시에 401이면 각각 재발급할 수 있다. RT는 교체하지 않으므로 각 요청에서 같은 RT를 검증할 수 있다. 로그인·로그아웃·회원가입·재발급 자체는 제외한다.

401과 INVALID_REFRESH_TOKEN 코드가 함께 온 경우에만 at/rt/userId를 삭제하고 로그인 화면으로 이동한다. 그 외 서버/네트워크 오류에서는 세션을 유지한다. 재발급 도중 로그아웃하거나 계정이 바뀌면 늦게 도착한 AT를 저장하지 않는다. 토큰은 로그에 출력하지 않는다.

## 검증

백엔드 `TokenRefreshTest`: 실제 JWT 생성/검증 및 보안 필터, Redis/회원 저장소는 mock.
프론트 `src/api/axios.test.js`: 실제 axios 인터셉터, HTTP adapter만 대체.
실제 Redis/MariaDB 연동 테스트는 별도다.
