# config-service

Config Server(8888)입니다. [config-repo](https://github.com/batontouch-sporty/config-repo)를 읽어 서비스에 설정을 내려 줍니다.

## `{cipher}` 비밀값 암호화

config-repo의 비밀값은 `${환경변수}`로 두거나 `'{cipher}<암호문>'`으로 넣습니다. `{cipher}`는 prod 파일(`*-prod.yml`)에만 쓰고, local·dev는 지금처럼 `${환경변수}`를 씁니다.
Config Server가 키스토어로 복호화해서 서비스에는 평문을 내려 줍니다. 서비스 쪽에는 키가 필요 없습니다.

키 설정이 없으면 config-service는 암호화 없이 그대로 기동합니다(이때 `/encrypt`는 500). 팀원 로컬과 CI Compose는 키 없이 씁니다.

### 1. 키스토어 만들기

키스토어 파일(`*.jks`)과 비밀번호는 저장소에 올리지 않습니다(`.gitignore`에 `*.jks`가 있습니다). 저장소 밖 폴더에 만들고 팀 채널로 따로 전달합니다.

```bash
keytool -genkeypair -alias sporty-config -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=sporty-config" -storetype JKS \
  -keystore ~/sporty-keystore/config.jks
```

비밀번호는 `-storepass`·`-keypass`로 넘기지 말고 keytool이 물어볼 때 입력합니다(셸 기록·프로세스 목록에 남지 않게). 키 비밀번호를 물을 때 Enter를 누르면 키스토어 비밀번호와 같아집니다.

`-validity`를 빼면 90일 뒤 만료됩니다. 키를 바꾸면 기존 암호문은 모두 다시 만들어야 합니다.

### 2. 환경변수

| 환경변수 | 값 |
| --- | --- |
| `ENCRYPT_KEYSTORE_LOCATION` | 키스토어 위치. 예: `file:/etc/config-keystore/config.jks` (Windows는 `file:C:/Users/<이름>/sporty-keystore/config.jks`) |
| `ENCRYPT_KEYSTORE_PASSWORD` | 키스토어 비밀번호(`-storepass`) |
| `ENCRYPT_KEYSTORE_ALIAS` | `-alias` 값 (위 예시는 `sporty-config`) |
| `ENCRYPT_KEYSTORE_SECRET` | 키 비밀번호(`-keypass`). 키스토어 비밀번호와 같으면 넣지 않습니다 |

Spring의 `encrypt.key-store.*` 설정에 그대로 연결됩니다. 키를 쓰지 않을 때는 빈 값으로 넣지 말고 변수 자체를 빼 둡니다.

### 3. 암호문 만들기

키를 넣고 띄운 config-service에 직접 요청합니다. `/encrypt`·`/decrypt`와 설정 조회(`/{서비스}/{프로필}`)는 Gateway에 라우트가 없어 Gateway로는 부를 수 없습니다.
다만 config-service에는 인증이 없어서 같은 Compose 네트워크의 컨테이너, 같은 클러스터의 Pod, Compose 호스트(`127.0.0.1:8888`)에서는 직접 부를 수 있고, 키를 넣은 뒤에는 복호화된 평문을 받습니다. 키는 이 범위를 믿을 수 있는 환경에만 넣습니다.

```bash
curl -X POST http://localhost:8888/encrypt -H 'Content-Type: text/plain' -d '<평문>'
# 확인용 복호화
curl -X POST http://localhost:8888/decrypt -H 'Content-Type: text/plain' -d '<암호문>'
```

셸 기록에 평문이 남지 않게 `-d @파일`로 넘기고 파일은 바로 지워도 됩니다.

### 4. config-repo에 넣기

prod 파일에 작은따옴표로 감싸 넣습니다. 따옴표가 없으면 YAML이 `{`를 객체로 읽습니다.

```yaml
# user-service-prod.yml
spring:
  datasource:
    password: '{cipher}AQB2...'
```

`curl http://localhost:8888/user-service/prod`로 보면 복호화된 값이 나옵니다. 키가 틀리거나 없는 Config Server는 이 값을 `invalid.spring.datasource.password`로 바꿔 내려 주므로 서비스가 DB 비밀번호 없이 뜨다 실패합니다.

### Compose·K8s에서 키 넘기기

- **Compose**: 기본 `docker-compose.yml`은 키를 넘기지 않습니다. 시험할 때만 커밋하지 않는 `docker-compose.override.yml`에서 config-service에 키스토어를 읽기 전용 볼륨으로 마운트하고(예: `~/sporty-keystore:/etc/config-keystore:ro`) 위 환경변수 4개를 넣습니다. 컨테이너가 `nobody`로 돌므로 파일에 읽기 권한이 있어야 합니다.
- **K8s**: 키스토어 파일과 비밀번호를 Secret으로 만듭니다(`kubectl -n sporty create secret generic config-keystore --from-file=config.jks=<경로> --from-env-file=<임시 파일>`). 비밀번호는 `--from-literal`로 넘기지 말고, 본인만 읽을 수 있게 권한을 둔 임시 파일에 `ENCRYPT_KEYSTORE_PASSWORD=...`·`ENCRYPT_KEYSTORE_ALIAS=...`·`ENCRYPT_KEYSTORE_SECRET=...` 줄로 적어 넘긴 뒤 바로 지웁니다. config-service Deployment에서 `config.jks`는 볼륨으로 `/etc/config-keystore`에 읽기 전용 마운트, 비밀번호·alias는 `env`의 `secretKeyRef`(또는 `envFrom`)로 넣습니다. `ENCRYPT_KEYSTORE_LOCATION`은 `file:/etc/config-keystore/config.jks`입니다. Secret 매니페스트는 저장소에 올리지 않고 클러스터에서 직접 만듭니다.
