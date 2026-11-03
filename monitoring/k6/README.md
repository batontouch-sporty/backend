# TC-OBS-003 · 보증금 Saga 기준 k6 조회·동시 참가 테스트

현재 계약은 생성·참가 **202 접수 → 요청 조회 → CONFIRMED**다. HTTP 202 자체를 참가 성공으로 세지 않는다. `monitoring/k6/`의 스크립트와 검증기는 R10 및 보증금 활성화 환경을 사용한다.

## 실행 환경

- Windows PowerShell, Python 3, Docker Compose, 저장소 루트 개발용 `.env`가 필요하다.
- `stack.ps1 up`은 전용 프로젝트 `sporty-k6-90`을 빌드한다. 일반 개발 스택과 DB 볼륨·네트워크·JWT 키를 분리한다.
- Gateway 18090, Config 18888, MariaDB 13316, Redis 16389, Kafka 19092, Prometheus 19090, Grafana 13001, Alertmanager 19093, Zipkin 19411 포트를 사용한다.
- 기동 시 `.run/test.compose.yml`을 생성한다. 전용 환경의 Discord 전송과 공공시설 자동 동기화를 끄고, 시설은 fixture로 준비한다. 루트 Compose나 config-repo는 바꾸지 않는다.
- `kafka-exporter`와 Alertmanager도 기동한다. AI 서비스는 이 시나리오의 의존성이 아니어서 기동하지 않으며, 해당 Prometheus target은 DOWN으로 보일 수 있다. 전체 서비스 가용성 검증으로 해석하지 않는다.
- JWT 키는 `%LOCALAPPDATA%/Sporty/sporty-k6-90/jwt.key`에 보관한다. 같은 스택에서는 재사용하고 정상 정리 후 삭제한다. 토큰·키·`.env`는 공유하거나 커밋하지 않는다.

## 소규모 예비 실행

backend 저장소 루트에서 실행한다. 12명은 방장 외 남은 9자리보다 많으므로 정상 확정과 정원 거절을 함께 검증한다.

```powershell
python -m unittest discover -s monitoring/k6 -p test_fixture.py -v
./monitoring/k6/stack.ps1 up
python monitoring/k6/fixture.py prepare --members 12
./monitoring/k6/run.ps1 smoke
./monitoring/k6/run.ps1 list -Vus 3 -Duration 10s
./monitoring/k6/run.ps1 join
python monitoring/k6/fixture.py verify
./monitoring/k6/run.ps1 smoke
```

## 100 VU 본 실행

예비 실행 PR을 머지한 뒤 최종 통합 커밋에서 새 데이터를 준비한다. 참가 테스트는 사용한 매치를 재사용하지 않는다.

```powershell
python monitoring/k6/fixture.py prepare
./monitoring/k6/run.ps1 smoke
./monitoring/k6/run.ps1 list
./monitoring/k6/run.ps1 join
python monitoring/k6/fixture.py verify
./monitoring/k6/run.ps1 smoke
```

- `prepare` 기본값은 서로 다른 참가 후보 100명이다. `--members 1..100`으로 예비 실행 크기를 정한다. 가입 시 각 50점, ACTIVE·정지 없음 상태를 확인한다.
- 방장으로 미래 매치 3건을 만들고 **각 생성 요청의 CONFIRMED까지 기다린다.** 마지막 매치가 대상이며 정원 10명, 확정 인원은 방장 1명이다. 방장 잔액은 20점, 참가 후보는 각각 50점이다.
- 목록 기본 부하는 100 VU·60초이며 각 반복에 1초 쉰다. `-Vus`, `-Duration`은 목록에만 적용한다. 참가 VU 수는 fixture의 회원 수와 같다.
- 참가자는 한 번만 POST한다. 202 접수 수와 최종 확정 수를 별도로 기록하고 원래 requestId로 1초 간격 조회한다. 확정 또는 거절 및 필요한 반환 완료까지 최대 360초 기다린다.
- 정원 거절은 즉시 HTTP 400 또는 비동기 FAILED/CANCELLED의 `MATCH_FULL`·`MATCH_RECRUITMENT_CLOSED`를 인정한다. 포인트 부족·인증 실패·조회 오류·기한 초과는 예상 밖 실패다. `refundStatus=PENDING`을 완료로 처리하지 않는다.
- 100명 기준 최종 확정 9명, 정원 거절 91명, 예상 밖 실패 0명이 기대값이다. **접수 202 수는 9명보다 많을 수 있다.** 차감 후 정원 거절이면 반환까지 확인한다. HTTP 요청 수에는 폴링이 포함되므로 100건으로 고정하지 않는다.
- `verify`는 대상 매치의 DB 요청 스냅샷·참가 행·포인트 이력을 조회한다. 확정 인원 10명, 회원 중복 없음, 요청별 차감·반환 횟수와 잔액(확정 참가자 40점, 거절 참가자 50점)을 k6 결과와 대조한다. 취소·실패 행은 정원에 포함하지 않는다. 테스트 ID가 다른 과거 결과도 실패한다.

## 결과와 관측

| 파일 또는 지표 | 의미 |
| --- | --- |
| `.run/list.json` | 목록 요청 수, HTTP 실패율, 응답시간 P95 및 checks |
| `.run/join.json` | k6 원본 summary-export |
| `.run/join-results.json` | runId·matchId와 `handleSummary`의 metrics(values 구조); DB 검증 입력 |
| `.run/verification.json` | 토큰 없는 정원·정산 대조 결과; 성공했을 때만 생성 |
| `.run/*-time.json` | UTC 시작·종료 Unix 초, 실행 옵션, 종료 코드 |
| `join_acceptance_ms` | 참가 POST의 HTTP 응답시간; 접수만 측정 |
| `join_confirmation_ms` | POST 시작부터 CONFIRMED 관측까지의 시간 |
| `join_settlement_ms` | 접수된 요청의 확정 또는 거절·반환 완료 관측까지의 시간 |

확정·정산 시간에는 1초 폴링 간격이 포함된다. HTTP 응답시간과 혼합해서 보고하지 않는다. 참가 결과가 예상과 다르거나 DB/반환 검증에 실패하면 실행 실패로 기록하고 원인을 확인한다.

Grafana의 서비스 개요·JVM·Kafka 대시보드에서 같은 시간 범위의 요청량·P95·CPU·Heap·Lag를 확인한다. Prometheus는 `http://127.0.0.1:19090`, Grafana는 `http://127.0.0.1:13001`이다. 부하 전후 정상 조회도 기록한다. 최종 리포트에는 backend·config-repo 커밋, 머신 자원, VU·기간·테스트 시간 및 결과를 함께 남긴다.

예비 실행은 스크립트와 통합 흐름을 검증하는 용도이며 최종 성능 결과가 아니다. 100 VU 본 실행 수치·캡처·PDF는 최종 통합 버전에서 별도로 작성한다.

## 정리

증빙을 보관한 다음 아래 명령으로 전용 테스트 스택과 전용 DB 볼륨을 정리한다.

```powershell
./monitoring/k6/stack.ps1 down
```

정리가 성공하면 `.run/participants.json`(방장·참가자 토큰)과 테스트 JWT 키를 삭제한다. 실패하면 재시도할 수 있게 보존한다. `.run/`의 결과 파일은 Git에서 제외하며, 필요한 수치만 PR·리포트에 옮긴다.
