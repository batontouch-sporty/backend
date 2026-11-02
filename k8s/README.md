# Kubernetes 공통 기반, Discovery 및 tracing 초안

이 디렉터리는 Sporty_v2의 Kubernetes 공통 구조, `discovery-service` 배포와 HTTP 분산 추적 공통 기반을 시작하기 위한 초안이다. 현재 대상 클러스터와 이미지 레지스트리가 정해지지 않았으므로 정적 렌더링과 설정 연결 검토까지만 대상으로 한다. 실제 클러스터 적용과 전체 서비스 배포는 후속 작업이다.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `namespace.yaml` | 모든 Sporty_v2 리소스가 사용할 `sporty` Namespace를 선언한다. |
| `discovery-configmap.yaml` | Discovery의 일반 환경 설정인 `prod` 프로필과 Spring Boot health probe 활성화를 관리한다. |
| `discovery-deployment.yaml` | Discovery Pod 1개, 이미지, 포트, probe, 자원 요청·제한을 선언한다. |
| `discovery-service.yaml` | 클러스터 내부에서 Discovery를 노출하는 `ClusterIP` Service를 선언한다. |
| `tracing-configmap.yaml` | 추적 대상 애플리케이션이 공유할 Zipkin endpoint, sampling 및 scheduled task observation 설정을 관리한다. |
| `zipkin-deployment.yaml` | 개발·시연용 메모리 저장 방식의 Zipkin 단일 인스턴스를 선언한다. |
| `zipkin-service.yaml` | 같은 Namespace의 애플리케이션이 Zipkin에 접근할 `ClusterIP` Service를 선언한다. |
| `kustomization.yaml` | 위 리소스를 함께 렌더링하고 배포 이미지 이름과 태그를 교체할 지점을 제공한다. |
| `README.md` | 초안의 전제, 검증 범위, 이미지 준비 및 후속 배포 절차를 설명한다. |

애플리케이션 Deployment는 현재 Discovery만 구현되어 있다. 이번 tracing 기반에는 공통 ConfigMap과 Zipkin 리소스만 포함하며, Gateway·User·Match·Facility·AI·Notification Deployment는 아래의 다음 단계에서 담당자와 설정을 맞춘 뒤 추가한다. Config, 데이터 저장소, Kafka, 나머지 도메인 서비스와 모니터링 매니페스트도 후속 범위다.

## 프로필과 설정

팀 프로필 규칙은 `local=IDE`, `dev=Compose`, `prod=Kubernetes`이다. 따라서 Deployment는 ConfigMap에서 `SPRING_PROFILES_ACTIVE=prod`를 주입한다. `config-repo`의 `application-prod.yml`도 클러스터 내부 Eureka 기본 주소를 `http://discovery-service:8761/eureka`로 정의하고 있어 이 Service 이름과 일치한다.

Discovery는 현재 애플리케이션 설정에서 포트 `8761`을 사용하고, Eureka 서버 자신을 등록하거나 레지스트리를 가져오지 않도록 구성되어 있다. Discovery 단독 기동에 필요하지 않은 DB·Redis·Kafka·JWT 설정은 ConfigMap에 포함하지 않았다.

## 배포 구성

- Namespace: `sporty`
- Deployment / Service: `discovery-service`
- replicas: `1`
- Deployment 업데이트 전략: `Recreate`
- Service 유형: `ClusterIP`
- 컨테이너 / Service 포트: `8761`
- 같은 Namespace의 접속 주소: `http://discovery-service:8761`
- 클러스터 도메인이 기본값인 `cluster.local`일 때의 전체 DNS 주소 예시: `http://discovery-service.sporty.svc.cluster.local:8761`
- 시작·생존 probe: `GET /actuator/health/liveness`
- 준비 probe: `GET /actuator/health/readiness`

사용자 정의 클러스터 도메인을 사용하면 전체 DNS 주소의 마지막 `cluster.local` 부분을 실제 클러스터 도메인에 맞춰야 한다.

### 교체 전략과 가용성 제한

현재 구성은 replicas가 1개인 단일 standalone Eureka 서버이며, Deployment 업데이트에는 `Recreate` 전략을 사용한다. 업데이트할 때 기존 Pod가 종료된 뒤 새 Pod가 생성되므로 교체 중 Discovery 서버에 접속할 수 없는 시간이 발생한다.

Eureka의 등록 정보는 메모리에 보관되므로 새 서버가 시작된 뒤 클라이언트의 재등록과 레지스트리 캐시 갱신을 기다려야 한다. 기존 클라이언트 캐시로 일부 호출이 이어질 수 있지만 모든 호출의 연속성을 보장하지는 않는다. 따라서 Discovery Pod의 `Ready` 상태와 전체 서비스의 재등록·조회 복구를 별도로 확인해야 한다.

`Recreate`는 레지스트리 보존이나 무중단 배포를 보장하지 않는다. 복구 시간은 애플리케이션 시작, probe, Eureka 등록 및 캐시 갱신 주기 등 실제 환경에 따라 달라지므로 30초와 같은 고정 시간으로 단정하지 않는다. 실제 교체 동작과 복구 과정은 아직 검증하지 않았으며 후속 배포 단계에서 확인한다.

`startupProbe`는 최초 10초 뒤 5초 간격으로 최대 24회 실패를 허용한다. 즉 probe 요청 시간까지 고려하면 약 130초 동안 시작을 기다릴 수 있다. `startupProbe`가 성공할 때까지 `livenessProbe`와 `readinessProbe`의 실행은 보류된다. 이후 liveness는 10초, readiness는 5초 간격으로 검사하며 각 요청의 timeout은 3초이다. `startupProbe` 또는 `livenessProbe`가 설정된 연속 실패 기준에 도달하면 해당 컨테이너가 재시작된다. `readinessProbe`가 연속 3회 실패하면 Pod는 `Ready=false` 상태가 되어 Service의 일반 트래픽 대상에서 제외되지만, readiness 실패 자체가 컨테이너를 재시작시키지는 않는다.

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

`tracing-config` ConfigMap은 추적 대상 애플리케이션이 공유할 다음 세 환경변수를 관리한다.

- `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT`: Zipkin span 수집 endpoint
- `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`: 추적 sampling 비율
- `MANAGEMENT_OBSERVATIONS_ENABLE_TASKS`: scheduled task observation 활성화 여부

향후 Gateway·User·Match·Facility·AI·Notification Deployment를 추가할 때 각 서비스 전용 ConfigMap과 함께 다음과 같이 주입한다.

```yaml
envFrom:
  - configMapRef:
      name: <service-specific-config>
  - configMapRef:
      name: tracing-config
```

현재 Kubernetes 디렉터리에는 애플리케이션 중 Discovery Deployment만 있으며, Discovery는 이번 tracing 적용 대상이 아니다. 따라서 Discovery ConfigMap과 Deployment에는 `tracing-config`를 연결하지 않았다. 위 여섯 서비스에 대한 실제 `envFrom` 연결은 각 Deployment를 추가할 때 수행해야 한다.

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

초안의 `sporty-discovery-service:k8s-draft`는 유효한 이미지 참조 형식을 맞추기 위해 정한 이름일 뿐, 이미지가 이미 빌드되었거나 레지스트리에 업로드되었다는 뜻이 아니다. 실제 적용 전에 대상 Kubernetes 노드가 이미지를 사용할 수 있도록 준비해야 한다.

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

## 다음 단계

1. **Config·Gateway 연결**: Config 및 Gateway 담당자와 배포 순서, `CONFIG_SERVER_URL`, `EUREKA_URL`, Gateway route와 내부 Service 이름을 맞춘다.
2. **DB·Redis·Kafka 배포**: 공통 인프라 담당자와 영속 볼륨, StorageClass, Secret 관리, 백업, Kafka listener·bootstrap 주소와 노출 방식을 확정한다. 각 데이터 저장소를 실제로 쓰는 서비스 담당자와 자원 요구량도 맞춘다.
3. **도메인 서비스 등록·통신 검증**: 각 서비스 담당자와 이미지·포트·환경변수·probe·자원값을 확정하고, Eureka 등록과 Gateway/Feign 통신을 순서대로 검증한다.
4. **모니터링 연결**: 모니터링 담당자와 Prometheus service discovery, scrape 대상, actuator 노출 범위, Grafana dashboard와 Alertmanager 알림 경로를 맞춘다.

각 단계에서는 담당 서비스의 API 및 Kafka 이벤트 계약을 변경하지 않고, 필요한 환경 설정과 운영 전제만 별도 합의한다.
