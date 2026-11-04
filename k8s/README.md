# Sporty Kubernetes 매니페스트

`sporty` 네임스페이스에서 Discovery, Config, Gateway, User, Match, Facility, AI, Notification과 Zipkin을 실행한다. MariaDB, Redis, Kafka는 별도 인프라 EC2의 Compose에서 실행한다. 로컬 렌더링과 클러스터 배포를 별도로 확인한다.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `namespace.yaml` | 모든 Sporty_v2 리소스가 사용할 `sporty` Namespace를 선언한다. |
| `app-common-configmap.yaml` | Spring 애플리케이션이 공유할 OS·JVM 시간대(`Asia/Seoul`) 설정을 관리한다. |
| `discovery-configmap.yaml` | Discovery의 일반 환경 설정인 `prod` 프로필과 Spring Boot health probe 활성화를 관리한다. |
| `discovery-deployment.yaml` | Discovery Pod 1개, 이미지, 포트, probe, 자원 요청·제한을 선언한다. |
| `discovery-service.yaml` | 클러스터 내부에서 Discovery를 노출하는 `ClusterIP` Service를 선언한다. |
| `tracing-configmap.yaml` | 추적 대상 6개 서비스의 클러스터별 예외 설정을 위한 ConfigMap이다. 기본 추적 설정은 config-repo에서 읽는다. |
| `zipkin-deployment.yaml` | 개발·시연용 메모리 저장 방식의 Zipkin 단일 인스턴스를 선언한다. |
| `zipkin-service.yaml` | 같은 Namespace의 애플리케이션이 Zipkin에 접근할 `ClusterIP` Service를 선언한다. |
| `kustomization.yaml` | 위 리소스를 함께 렌더링하고 배포 이미지 이름과 태그를 교체할 지점을 제공한다. |
| `config-configmap.yaml` | Config Server의 prod 프로필, probe, Eureka URL과 저장소 상태 검사 설정이다. |
| `app-client-configmap.yaml` | Gateway와 업무 서비스 5개에 prod 프로필, Config Server 및 Eureka URL, probe 설정을 제공한다. |
| `{config,gateway,user,match,facility,ai,notification}-deployment.yaml` | 각 서비스 Pod의 이미지, 포트, probe, 자원 및 환경변수를 선언한다. |
| `{config,gateway,user,match,facility,ai,notification}-service.yaml` | 각 앱을 같은 이름의 ClusterIP Service로 연결한다. |
| `gateway-nodeport.yaml` | 외부 API 진입점. Gateway 8000을 NodePort 30080으로 연다. ClusterIP `gateway-service`는 그대로 둔다. |
| `frontend-deployment.yaml`, `frontend-service.yaml` | 프론트 nginx Pod와 NodePort 30000. nginx는 `GATEWAY_UPSTREAM=gateway-service:8000`으로 API를 넘긴다. |
| `monitoring-nodeport.yaml` | infra EC2 Prometheus가 긁을 관리 포트 NodePort 6개(30101, 30181~30185). |
| `infra-endpoints.example.yaml` | `sporty-infra-endpoints` ConfigMap 예시. 자리표시만 있고 `resources`에 넣지 않는다. |
| `deploy.sh` | Docker Hub 네임스페이스·태그로 이미지를 바꿔 적용하고 rollout을 기다린다. |

Gateway는 API 포트 8000과 내부 관리 포트 8001을 구분한다. Config는 8888, User·Match·Facility·AI·Notification은 차례로 8081~8085를 사용한다. 외부 API 진입점을 구성할 때는 Gateway의 8000만 연결한다.

## 프로필과 설정

팀 프로필 규칙은 `local=IDE`, `dev=Compose`, `prod=Kubernetes`이다. 따라서 Deployment는 ConfigMap에서 `SPRING_PROFILES_ACTIVE=prod`를 주입한다. `config-repo`의 서비스별 `<서비스명>-prod.yml`도 클러스터 내부 Eureka 기본 주소를 `http://discovery-service:8761/eureka`로 정의하고 있어 이 Service 이름과 일치한다.

Discovery는 현재 애플리케이션 설정에서 포트 `8761`을 사용하고, Eureka 서버 자신을 등록하거나 레지스트리를 가져오지 않도록 구성되어 있다. Discovery 단독 기동에 필요하지 않은 DB·Redis·Kafka·JWT 설정은 ConfigMap에 포함하지 않았다.

### 공통 시간대

`app-common-config`는 모든 Spring 애플리케이션이 같은 한국 시간 기준을 사용하도록 `TZ=Asia/Seoul`과 `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Seoul`을 제공한다. Discovery와 신규 앱 7개의 Deployment가 모두 이 설정을 `envFrom`으로 참조한다. 서비스별 JVM 옵션을 추가할 때는 최종 옵션에 시간대가 포함되도록 한다.

## Discovery 배포 구성

Discovery는 포트 8761의 ClusterIP Service로 연결한다. 같은 네임스페이스의 클라이언트는 `http://discovery-service:8761/eureka`를 사용한다. 시작·생존 probe는 `/actuator/health/liveness`, 준비 probe는 `/actuator/health/readiness`를 사용한다.

### 교체 전략과 가용성 제한

현재 구성은 replicas가 1개인 단일 standalone Eureka 서버이며, Deployment 업데이트에는 `Recreate` 전략을 사용한다. 업데이트할 때 기존 Pod가 종료된 뒤 새 Pod가 생성되므로 교체 중 Discovery 서버에 접속할 수 없는 시간이 발생한다.

Eureka의 등록 정보는 메모리에 보관되므로 새 서버가 시작된 뒤 클라이언트의 재등록과 레지스트리 캐시 갱신을 기다려야 한다. 기존 클라이언트 캐시로 일부 호출이 이어질 수 있지만 모든 호출의 연속성을 보장하지는 않는다. 따라서 Discovery Pod의 `Ready` 상태와 전체 서비스의 재등록·조회 복구를 별도로 확인해야 한다.

`Recreate`는 레지스트리 보존이나 무중단 배포를 보장하지 않는다. 복구 시간은 애플리케이션 시작, probe, Eureka 등록 및 캐시 갱신 주기 등 실제 환경에 따라 달라지므로 30초와 같은 고정 시간으로 단정하지 않는다. 실제 교체 동작과 복구 과정은 아직 검증하지 않았으며 후속 배포 단계에서 확인한다.

`startupProbe`는 최초 10초 뒤 5초 간격으로 최대 48회 실패를 허용한다. 즉 probe 요청 시간까지 고려하면 약 250초 동안 시작을 기다릴 수 있다. AWS 배포에서 match-service는 시작부터 기동 완료까지 약 134초가 걸려 24회(약 130초)로는 기동 직전에 재시작됐다. 서비스 여러 개가 동시에 뜨면 CPU limit 500m 안에서 더 느려지므로 Spring 앱 8개 모두 48회로 둔다. `startupProbe`가 성공할 때까지 `livenessProbe`와 `readinessProbe`의 실행은 보류된다. 이후 liveness는 10초, readiness는 5초 간격으로 검사하며 각 요청의 timeout은 3초이다. `startupProbe` 또는 `livenessProbe`가 설정된 연속 실패 기준에 도달하면 해당 컨테이너가 재시작된다. `readinessProbe`가 연속 3회 실패하면 Pod는 `Ready=false` 상태가 되어 Service의 일반 트래픽 대상에서 제외되지만, readiness 실패 자체가 컨테이너를 재시작시키지는 않는다.

### 서비스별 replicas

| 서비스 | replicas | 이유 |
|---|---|---|
| frontend, gateway-service, config-service, ai-service | 2 | 상태와 `@Scheduled` 작업이 없다. `topologySpreadConstraints`(`kubernetes.io/hostname`, maxSkew 1, `DoNotSchedule`, `nodeTaintsPolicy: Honor`, `matchLabelKeys: [pod-template-hash]`)로 worker마다 1개씩 둔다. 롤아웃 중에는 새 ReplicaSet Pod끼리만 나눠 계산한다 |
| user-service, match-service, facility-service | 1 | 상태 전이, Outbox 발행, 탈퇴 시간 초과 검사, 시설 동기화 같은 `@Scheduled` 작업이 Pod마다 돈다. 분산 잠금 없이 늘리면 중복 실행되므로 잠금을 넣은 뒤 확장한다 |
| discovery-service | 1 | standalone Eureka라 여러 개로 늘리려면 서버 간 복제 설정이 필요하다 |
| notification-service, zipkin | 1 | 이번 범위에서 제외 |

worker 1대가 내려가면 그 노드에 `NoSchedule` taint가 붙고 `nodeTaintsPolicy: Honor`라 계산에서 빠지므로, 다시 만들어지는 Pod는 남은 worker에 뜬다(master도 taint 때문에 계산에서 빠진다). 노드가 돌아와도 자동으로 다시 나뉘지 않으므로 `kubectl -n <네임스페이스> rollout restart deployment/<이름>`으로 다시 나눈다.

외부 접속은 worker 공인 IP의 NodePort(30000)로 들어온다. NodePort는 모든 노드에 열려 있어 어느 worker 주소로 들어와도 살아 있는 Pod로 전달되지만, 접속한 그 worker가 내려가면 그 주소로는 접속할 수 없다. 이때는 살아 있는 worker의 공인 IP로 접속한다. 같은 주소를 유지하려면 상태 검사를 하는 외부 Load Balancer나 DNS 장애 조치가 필요하며, 현재 구성에는 없다.

Java 17 환경에서 다음 의존성 확인 명령을 실행한 결과, `spring-boot-starter-actuator:3.5.4`가 `spring-cloud-netflix-eureka-server:4.3.0`을 통해 Discovery의 `runtimeClasspath`에 전이 포함됨을 확인했다.

```bash
sh ./gradlew :discovery-service:dependencyInsight \
  --dependency spring-boot-starter-actuator \
  --configuration runtimeClasspath \
  --no-daemon --console=plain
```

ConfigMap의 `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`는 Kubernetes용 liveness/readiness health group을 활성화한다. 다만 이번 작업에서는 서비스를 실행하지 않았고 클러스터에도 적용하지 않았으므로, 위 HTTP 경로가 실제 실행 환경에서 응답하는지는 아직 검증하지 않았다. 실제 배포 전후에 아래 확인 절차로 검증해야 한다.

자원 설정은 다음 시작값을 사용한다.

| 구분 | CPU | 메모리 |
| --- | --- | --- |
| requests | `100m` | `256Mi` |
| limits | `500m` | `512Mi` |

기존 Dockerfile은 JVM 최대 힙을 `-Xmx256m`으로 지정한다. JVM 메타데이터, 스레드 스택, 네이티브 메모리와 기타 프로세스 메모리도 필요하므로 메모리 limit은 최대 힙보다 큰 `512Mi`로 잡았다. 이 값은 초안이며 실제 배포 환경의 사용량을 측정한 뒤 조정해야 한다.

## Kubernetes tracing 구조

Compose의 애플리케이션은 `http://zipkin:9411/api/v2/spans`로 span을 전송한다. Kubernetes에서도 같은 `sporty` Namespace에 있는 `zipkin` ClusterIP Service의 이름을 사용하므로 endpoint는 동일하게 `http://zipkin:9411/api/v2/spans`이다. `zipkin` Service는 9411 포트를 Zipkin Pod의 9411 포트에 연결하며 외부에는 노출하지 않는다.

Gateway·User·Match·Facility·AI·Notification은 `tracing-config`를 참조한다. 이 ConfigMap의 기본값은 비어 있다. Zipkin 주소는 config-repo의 각 `<서비스명>-prod.yml`, sampling 비율과 스케줄 작업 observation은 공통 `application.yml`에서 관리한다. 설정을 변경하면 config-repo PR 머지 후 해당 서비스를 재시작한다.

sampling probability `1.0`은 모든 호출을 확인하기 위한 개발·시연 기준이다. 운영 환경에서는 트래픽 양과 저장 비용을 측정한 뒤 더 낮은 값으로 조정할 수 있다. `MANAGEMENT_OBSERVATIONS_ENABLE_TASKS=false`는 Scheduled Task 기능 자체를 끄는 설정이 아니라, Scheduled Task Observation과 그에 따른 span 생성을 줄이기 위한 설정이다.

Zipkin은 개발·시연용 단일 인스턴스이며 `STORAGE_TYPE=mem`을 사용한다. trace 데이터는 메모리에만 보관되므로 Zipkin Pod가 재시작되거나 교체되면 사라진다. 영속 저장소와 고가용성 구성은 운영 환경 요구사항을 확정한 뒤 별도로 설계해야 한다.

## Kustomize 렌더링

저장소 루트에서 다음 중 설치된 도구 하나를 사용한다. 두 명령 모두 클러스터에 리소스를 적용하지 않고 최종 YAML을 표준 출력으로 렌더링한다.

```bash
kubectl kustomize k8s
# 또는
kustomize build k8s
```

`kustomization.yaml`의 `images` 항목이 실제 레지스트리와 태그를 지정할 위치다. 배포 환경이 확정되면 `newName`을 레지스트리 경로가 포함된 이미지 이름으로, `newTag`를 불변 배포 태그로 바꾼다.

## 이미지 준비

현재 앱 이미지의 `k8s-draft` 태그는 로컬 렌더링용이다. 배포 전 Discovery를 포함한 앱 8개의 이미지를 팀 Docker Hub에 push하고 `kustomization.yaml`의 각 `images.newName`과 `newTag`에 실제 저장소·커밋 태그를 넣는다. 워커 아키텍처와 이미지 플랫폼이 일치해야 한다. 비공개 이미지는 워커가 pull할 수 있도록 `sporty` 네임스페이스에 풀 자격 증명을 구성한다.

기존 Dockerfile은 backend 저장소 루트를 build context로 사용한다. 저장소 루트에서 실행할 빌드 명령 예시는 다음과 같다. 이 명령은 문서 예시이며 이번 초안 작업에서는 실행하지 않았다.

```bash
docker build \
  -f discovery-service/Dockerfile \
  -t sporty-discovery-service:k8s-draft \
  .
```

Mac에서 만든 로컬 이미지는 별도 VM의 Kubernetes 노드로 자동 전달되지 않는다. 실제 환경에 맞춰 다음 중 사용할 방식을 정해야 한다.

- 접근 가능한 이미지 레지스트리에 push하고 `images.newName`과 `images.newTag`를 변경한다.
- 로컬·개발 클러스터가 제공하는 이미지 로드 기능으로 각 노드에 전달한다.
- 폐쇄 환경이면 검증된 별도 전달 절차로 각 노드의 컨테이너 런타임에 적재한다.

이미지 저장소, 태그 정책, 노드 아키텍처(`amd64`/`arm64` 등), 단일 또는 다중 아키텍처 빌드 여부와 전달 방식을 배포 환경에 맞춰 확정해야 한다. 현재 `imagePullPolicy: IfNotPresent`이므로 같은 이름과 태그의 오래된 로컬 이미지가 남지 않도록 불변 태그 사용을 권장한다.

## 배포 환경별 입력

실제 값은 `sporty` 네임스페이스에 별도로 준비한다. Git에 비밀번호를 넣지 않는다.

| 리소스 | 필요한 키 | 용도 |
| --- | --- | --- |
| ConfigMap `sporty-infra-endpoints` | `MARIADB_HOST`, `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `RABBITMQ_HOST`, `CORS_ALLOWED_ORIGIN`, `PROMETHEUS_URL` | DB·Redis·RabbitMQ는 Pod가 접근할 호스트, Kafka는 host:port, Prometheus는 스킴·포트가 있는 URL, CORS는 프론트엔드의 실제 origin이다. |
| 같은 ConfigMap의 선택 키 | `MARIADB_HOST_PORT`, `REDIS_HOST_PORT`, `RABBITMQ_PORT` | DB, Redis, RabbitMQ의 기본 포트는 3306, 6379, 5672다. |
| Secret `sporty-jwt` | `JWT_SECRET_KEY` | Gateway와 User가 같은 서명 키를 사용한다. |
| Secret `sporty-rabbitmq` | `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | Spring Cloud Bus 계정. infra EC2 `.env`의 같은 키와 값이 같아야 한다. |
| Secret `config-encrypt-keystore` (선택) | 파일 `config.jks`, `ENCRYPT_KEYSTORE_LOCATION`, `ENCRYPT_KEYSTORE_PASSWORD`, `ENCRYPT_KEYSTORE_ALIAS`, 선택 `ENCRYPT_KEYSTORE_SECRET` | config-service의 `{cipher}` 복호화 키. 없으면 암호화 없이 기동한다. |
| Secret `user-service-secret`, `match-service-secret`, `facility-service-secret`, `notification-service-secret` | 각각 `USER_APP_PASSWORD`, `MATCH_APP_PASSWORD`, `FACILITY_APP_PASSWORD`, `NOTIFICATION_APP_PASSWORD` | 인프라 MariaDB의 서비스별 계정과 일치해야 한다. 기존 DB 초기화 스크립트를 기준으로 스키마와 권한을 준비한다. |
| 선택 키 | Facility Secret의 `PUBLIC_DATA_API_KEY`, AI Secret `ai-service-secret`의 `OPENAI_API_KEY` | 키가 필요한 외부 기능용이다. 시연용 관리자 계정이 필요하면 User Secret에 `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `ADMIN_NICKNAME`을 추가한다. |

로컬 Compose의 호스트 바인딩과 Kafka advertised listener를 인프라 EC2의 사설 네트워크에 맞춘다. Kafka가 알려주는 브로커 주소에도 워커 Pod에서 접근할 수 있어야 한다. `PROMETHEUS_URL`은 Gateway가 관리자 쿼리를 보낼 주소다. 인프라 EC2에서 앱 메트릭을 수집할 경로 및 타깃은 `deploy/README.md`의 infra EC2 절차(`deploy/infra/prometheus.yml`, `targets/k8s-targets.json`)로 구성한다.

DB를 쓰는 4개 앱은 `MARIADB_HOST`, Kafka를 쓰는 User·Match·Notification은 `KAFKA_BOOTSTRAP_SERVERS`를 Pod에 주입한다. prod 설정이 이 이름을 그대로 참조한다. Match의 탈퇴 리스너는 Deployment에 명시한 `MATCH_WITHDRAWAL_ENABLED="true"`로 활성화한다.

Bus를 쓰는 config-service와 Gateway·업무 서비스 5개는 `RABBITMQ_HOST`·`RABBITMQ_PORT`와 `sporty-rabbitmq`의 계정을 주입받는다. 서비스는 config-repo `<서비스명>-prod.yml`의 `${RABBITMQ_HOST}`, config-service는 자기 `application.yml`이 이 이름을 읽는다.

config-service는 `config-encrypt-keystore`의 `config.jks`를 `/etc/config-keystore`에 읽기 전용으로 마운트하고 나머지 키를 환경변수로 받는다. 볼륨과 환경변수 모두 `optional: true`라 Secret이 없으면 변수가 빠진 채 암호화 없이 기동한다. `ENCRYPT_KEYSTORE_LOCATION`까지 Secret에서 받는 이유는, 위치만 있고 비밀번호가 없으면 Config Server가 키스토어를 읽으려다 기동에 실패하기 때문이다.

## 실제 배포 전 확인 사항

1. 대상 클러스터와 접근 권한을 확정하고 `kubectl config current-context` 결과가 의도한 환경인지 확인한다. context 변경은 명시적인 팀 합의 후 수행한다.
2. 이미지 레지스트리, 태그, 인증 방식과 노드로의 이미지 전달 방식을 확정하고 `kustomization.yaml`을 갱신한다.
3. 노드 아키텍처가 이미지 아키텍처와 맞는지, `256Mi` request와 `512Mi` limit을 수용할 자원이 있는지 확인한다.
4. 대상 클러스터의 Kubernetes 버전과 정책에서 이 리소스와 probe 구성이 허용되는지 확인한다.
5. 실제 실행 환경에서 `/actuator/health/liveness`와 `/actuator/health/readiness`가 기대한 상태와 HTTP 응답을 반환하는지 확인한다.

## 환경 확정 후 배포·확인 절차

아래 명령은 이번 작업에서 실행한 결과가 아니라, 클러스터와 이미지 준비가 끝난 뒤 수행할 후속 절차다.

```bash
# 적용 전 대상 확인과 최종 렌더링
kubectl config current-context
kubectl kustomize k8s

# 매니페스트 적용
kubectl apply -k k8s

# Deployment rollout과 Pod Ready 확인
kubectl -n sporty rollout status deployment/discovery-service --timeout=180s
kubectl -n sporty get pods -l app.kubernetes.io/name=discovery-service

# Service와 endpoint 연결 확인
kubectl -n sporty get service discovery-service
kubectl -n sporty get endpoints discovery-service

# 로컬 포트 포워딩
kubectl -n sporty port-forward service/discovery-service 8761:8761
```

포트 포워딩 중 별도 터미널에서 Eureka 화면과 health 응답을 확인한다.

```bash
curl -fsS http://127.0.0.1:8761/ > /dev/null
curl -fsS http://127.0.0.1:8761/actuator/health/liveness
curl -fsS http://127.0.0.1:8761/actuator/health/readiness
```

Eureka 화면은 브라우저에서 `http://127.0.0.1:8761/`로 확인한다. 렌더링 성공은 실제 이미지 pull, 애플리케이션 기동, probe 성공 또는 Pod Ready를 보장하지 않는다.

## 신규 앱 배포 확인

Config import가 필수인 이미지(backend #113 반영본)를 사용한다. 앱을 기동하거나 재시작하기 전에 Config Server가 Git 설정을 제공할 수 있는지 확인한다. 맥북에서 IDE로 실행할 때는 Compose의 Config 포트(기본 `127.0.0.1:8888`), Pod 안에서는 `http://config-service:8888`에 접속한다.

1. `kubectl -n sporty get deploy,pods,svc`로 신규 앱 7개의 rollout과 Ready 상태를 확인한다.
2. `kubectl -n sporty port-forward svc/config-service 18888:8888`을 실행하고 다른 터미널에서 `curl -fsS http://127.0.0.1:18888/gateway-service/prod/main`을 호출한다. 응답의 프로필, Git 버전 및 propertySources에 prod 설정이 있는지 확인한다.
3. `kubectl -n sporty port-forward svc/gateway-service 18000:8000`을 실행한다. Eureka 등록 후 다른 터미널에서 다섯 서비스의 실제 응답 코드를 확인한다.

```bash
for svc in user match facility ai notification; do
  curl -fsS --max-time 5 -o /dev/null -w "${svc}-service HTTP %{http_code}\n" \
    "http://127.0.0.1:18000/${svc}-service/actuator/health" || break
done
```

4. `kubectl -n sporty exec deploy/match-service -- printenv MATCH_WITHDRAWAL_ENABLED`가 `true`인지 확인한다. 테스트 계정의 탈퇴 요청에 대해 동일한 Saga가 `CLEANED` 또는 정상적인 거절 사유가 있는 `REJECTED`로 끝나는지 확인한다. HTTP 202는 접수 결과다.

각 단계에서는 담당 서비스의 API 및 Kafka 이벤트 계약을 변경하지 않고, 필요한 환경 설정과 운영 전제만 별도 합의한다.

## AWS 배포 절차

EC2 4대: k8s-master(t3.medium), k8s-worker 2대(t3.large, kubeadm), infra(t3.large, Compose로 MariaDB·Kafka·Redis·RabbitMQ·모니터링). 이미지는 Docker Hub `<네임스페이스>/sporty-<svc>-service:<태그>`, 프론트는 `<네임스페이스>/sporty-frontend:<태그>`다. 외부 접속은 NodePort + 보안 그룹으로 한다.

### 보안 그룹 포트

| 포트 | 대상 | 허용 출처 |
| --- | --- | --- |
| 30000 | 프론트(`frontend` NodePort) | 시연 접속 대역 (필요하면 0.0.0.0/0) |
| 30080 | Gateway API(`gateway-nodeport`) | 시연 접속 대역 |
| 30100~30199 | 관리 포트(`*-metrics`, `/actuator/prometheus`) | infra EC2 사설 IP만. master·worker 보안 그룹 모두 같은 규칙 |
| 5672 (infra EC2) | RabbitMQ(Bus) | 워커 사설 IP만 |

관리 포트 NodePort는 다음과 같다. Gateway만 관리 포트가 따로 있고, 업무 서비스는 서비스 포트에서 Actuator를 함께 연다.

| Service | 컨테이너 포트 | NodePort |
| --- | --- | --- |
| `gateway-metrics` | 8001 | 30101 |
| `user-metrics` | 8081 | 30181 |
| `match-metrics` | 8082 | 30182 |
| `facility-metrics` | 8083 | 30183 |
| `ai-metrics` | 8084 | 30184 |
| `notification-metrics` | 8085 | 30185 |

워커 쪽에서 infra EC2의 3306·6379·9092·5672·9090을 쓸 수 있게 infra 보안 그룹은 워커 사설 IP를 허용한다. infra의 Prometheus 수집 타깃은 `<워커-사설-IP>:<NodePort>`, 경로는 `/actuator/prometheus`다. 로컬 Compose용 `monitoring/prometheus.yml`은 그대로 두고, infra 전용 설정 `deploy/infra/prometheus.yml`이 `deploy/infra/targets/k8s-targets.json`(file_sd)을 읽는다. 예시 파일을 복사해 IP를 채우면 재시작 없이 다시 읽고, Prometheus `/targets`에서 여덟 대상이 UP인지 확인한다. Pod가 2개인 gateway-service·ai-service의 지표 Service(30101·30184)는 `externalTrafficPolicy: Local`이라 각 worker 주소가 그 노드의 Pod만 가리키므로, 두 worker 주소를 모두 대상으로 넣어 Pod별로 따로 수집한다(`instance` 라벨로 구분). worker 1대가 내려가면 그 대상만 DOWN이 되고 `SportyServiceDown` 경보가 해당 대상에 대해 울린다. Pod가 1개인 서비스는 kube-proxy가 어느 worker 주소로 들어와도 그 Pod로 넘기므로 주소 하나로 수집한다.

### 순서

① infra EC2에서 Compose를 기동한다. MariaDB·Redis·Kafka·RabbitMQ·Prometheus 포트를 사설 IP에 바인딩하고, Kafka `EXTERNAL` advertised listener를 `<infra-사설-IP>:9092`로 맞춘다.

② master에서 Namespace, ConfigMap, Secret을 만든다. ConfigMap은 예시 파일을 복사해 자리표시를 바꾼다. 복사본과 비밀값은 Git에 올리지 않는다.

```bash
kubectl apply -f k8s/namespace.yaml

cp k8s/infra-endpoints.example.yaml /tmp/infra-endpoints.yaml
# /tmp/infra-endpoints.yaml의 <...> 자리를 실제 값으로 바꾼다
kubectl apply -f /tmp/infra-endpoints.yaml

# = 뒤에 값을 넣는다. 셸 기록에 남지 않게 명령 앞에 공백을 두거나 실행 후 기록을 지운다.
kubectl -n sporty create secret generic sporty-jwt \
  --from-literal=JWT_SECRET_KEY=
# infra EC2 .env의 RABBITMQ_USER·RABBITMQ_PASSWORD와 같은 값
kubectl -n sporty create secret generic sporty-rabbitmq \
  --from-literal=RABBITMQ_USER= \
  --from-literal=RABBITMQ_PASSWORD=
kubectl -n sporty create secret generic user-service-secret \
  --from-literal=USER_APP_PASSWORD=
kubectl -n sporty create secret generic match-service-secret \
  --from-literal=MATCH_APP_PASSWORD=
kubectl -n sporty create secret generic facility-service-secret \
  --from-literal=FACILITY_APP_PASSWORD= \
  --from-literal=PUBLIC_DATA_API_KEY=
kubectl -n sporty create secret generic notification-service-secret \
  --from-literal=NOTIFICATION_APP_PASSWORD=
# 선택: AI 키, 시연용 관리자 계정
kubectl -n sporty create secret generic ai-service-secret \
  --from-literal=OPENAI_API_KEY=
# user-service-secret에 ADMIN_EMAIL·ADMIN_PASSWORD·ADMIN_NICKNAME을 함께 넣을 수 있다 (--from-literal 추가)

# 선택: {cipher} 복호화 키스토어. 비밀번호는 본인만 읽을 수 있는 임시 파일에 적어 넘기고 바로 지운다.
# 임시 파일 내용:
#   ENCRYPT_KEYSTORE_LOCATION=file:/etc/config-keystore/config.jks
#   ENCRYPT_KEYSTORE_PASSWORD=<키스토어 비밀번호>
#   ENCRYPT_KEYSTORE_ALIAS=<alias>
#   ENCRYPT_KEYSTORE_SECRET=<키 비밀번호>   (키스토어 비밀번호와 같으면 이 줄을 뺀다)
kubectl -n sporty create secret generic config-encrypt-keystore \
  --from-file=config.jks=<키스토어 경로> \
  --from-env-file=<임시 파일>
```

키스토어 만드는 방법과 `{cipher}` 값을 config-repo prod 파일에 넣는 순서는 [config-service README](../config-service/README.md#cipher-비밀값-암호화)를 따른다. 클러스터에서 `/encrypt`를 부를 때는 `kubectl -n sporty port-forward svc/config-service 18888:8888`로 연결한 뒤 `http://127.0.0.1:18888/encrypt`에 요청한다. 배포 뒤에 Secret을 만들었으면 `kubectl -n sporty rollout restart deployment/config-service`로 다시 띄워야 키를 읽는다. config-repo에 암호문을 넣고 머지한 뒤에는 그 값을 쓰는 서비스를 재시작한다.

`PUBLIC_DATA_API_KEY`, `ai-service-secret`, `ADMIN_*`, `config-encrypt-keystore`는 Deployment에서 `optional: true`라 없어도 Pod는 뜬다. 나머지는 없으면 Pod가 `CreateContainerConfigError`로 멈춘다.

③ master에서 배포 스크립트를 실행한다. `kustomization.yaml`은 고치지 않고 임시 overlay에서 이미지 이름·태그만 바꾼다. ConfigMap(`RABBITMQ_HOST` 포함)이나 필수 Secret이 없으면 적용 전에 멈추고, `config-encrypt-keystore`가 없으면 암호화 없이 기동한다고 알린다. rollout 대기 시간은 `ROLLOUT_TIMEOUT`(기본 `300s`)으로 바꿀 수 있다.

```bash
bash k8s/deploy.sh <DOCKERHUB_NAMESPACE> <TAG> [FRONTEND_TAG]
```

④ 접속을 확인한다.

```bash
kubectl -n sporty get deploy,pods,svc
curl -fsS -o /dev/null -w "front %{http_code}\n" http://<워커-공인-IP>:30000/
curl -fsS http://<워커-공인-IP>:30080/user-service/actuator/health
# infra EC2에서
curl -fsS http://<워커-사설-IP>:30181/actuator/prometheus | head
```

### 대체 경로

K8s 구성이 막히면 infra를 t3.xlarge로 올려 단일 서버 Compose로 전체를 띄운다. 절차는 `deploy/README.md`(단일 서버 배포)를 따른다.

### 배포 전 확인

- 태그는 커밋 sha 7자리를 쓴다. 백엔드와 프론트는 저장소가 달라 태그가 다르면 `FRONTEND_TAG`를 따로 준다. `IfNotPresent`라 같은 태그를 다시 쓰면 새 이미지를 받지 않으므로, 스크립트는 sha 7자리가 아닌 태그(`latest` 등)를 거부한다.
- Docker Hub 저장소 `sporty-*`가 공개(Public)인지 확인한다. 비공개면 워커가 pull하지 못한다(imagePullSecret 없음).
- 업무 서비스 5개의 `*-metrics` NodePort(30181~30185)는 관리 포트가 따로 없어 업무 API 포트를 그대로 연다. 서비스는 Gateway가 넣은 `X-User-Id`를 믿으므로, 이 포트는 보안 그룹에서 infra EC2만 허용하고 외부에는 절대 열지 않는다.
