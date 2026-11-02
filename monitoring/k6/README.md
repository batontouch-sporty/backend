# TC-OBS-003 · 매치 조회 및 동시 참가 부하 테스트

Windows PowerShell, Docker Compose, Python 3, 저장소 루트의 개발용 `.env`가 필요하다. 조회와 참가는 Gateway를 통과한다. k6는 `grafana/k6:1.0.0` 이미지를 사용한다.

## 테스트용 인증 키

`stack.ps1 up`은 첫 기동 시 임의의 JWT 서명 키를 생성해 전용 Gateway와 회원 서비스에 전달한다. 키는 현재 Windows 사용자의 `%LOCALAPPDATA%/Sporty/sporty-k6-90/jwt.key`에 보관되며 같은 스택을 다시 기동할 때 재사용한다. `down`이 성공하면 키도 삭제되므로 다음 시연은 새 키로 시작한다. 정리에 실패하면 같은 명령을 다시 실행한다.

## 실행

저장소 루트에서 아래 순서로 실행한다. `up`은 전용 Compose 프로젝트 `sporty-k6-90`을 빌드한다. Gateway는 18090, Prometheus는 19090, Grafana는 13001번 포트를 사용한다. 기동 직후 Eureka에 서비스가 등록될 때까지 다음 URL이 200인지 확인한다.

```powershell
./monitoring/k6/stack.ps1 up
Invoke-RestMethod http://127.0.0.1:18090/match-service/api/matches
python monitoring/k6/fixture.py prepare
./monitoring/k6/run.ps1 smoke
./monitoring/k6/run.ps1 list
./monitoring/k6/run.ps1 join
python monitoring/k6/fixture.py verify
./monitoring/k6/run.ps1 smoke
```

- **준비**. 전용 DB에 시설 1건을 넣은 후 Gateway에서 회원가입·로그인으로 방장과 참가자 후보 100명을 만든다. 방장이 미래 매치 3건을 생성하며 마지막 매치를 동시 참가 대상으로 사용한다. 준비 스크립트가 모집 중, 정원 10명, 참가자 1명(방장)을 확인한다.
- **조회**. smoke는 1 VU로 1회 요청한다. list는 100 VU가 60초 동안 매치 목록을 반복 조회하며 매회 1초 쉰다. HTTP 200과 매치가 포함된 배열을 확인한다. 실행 시간·요청 수·실패율·p95는 콘솔과 `.run/list.json`에 기록된다.
- **참가**. 100 VU가 각자 다른 회원의 토큰으로 같은 매치에 한 번씩 POST한다. 201 성공, 400과 정원 마감 코드, 그 외 오류를 별도 집계한다. 예상값은 성공 9건, 마감 91건, 예상 밖 오류 0건이다. `verify`로 실제 참가 행 수, 고유 회원 수, 방장 수와 성공 건수를 대조한다. 한 번 사용한 매치에는 재실행할 수 없으므로 새로운 시연은 `prepare`부터 시작한다.
- **관측**. Grafana의 ‘Sporty 서비스 개요’와 ‘JVM 리소스’에서 부하 전후를 포함한 동일 시간 범위를 선택한다. 요청량·p95·CPU·Heap을 확인한다. 각 실행의 UTC 시작·종료(Unix 초)는 `.run/*-time.json`에 있다. 마지막 smoke는 부하 후 조회 복구를 확인한다.

## 정리

캡처와 DB 검증이 끝나면 아래 명령으로 **전용 테스트 스택과 그 DB 볼륨**을 삭제한다. 준비가 도중에 실패한 경우에도 같은 명령으로 정리할 수 있다. 발급한 참가 토큰은 Git에서 제외되며 정리 명령이 로컬 토큰 파일도 삭제한다.

```powershell
./monitoring/k6/stack.ps1 down
```
