#!/usr/bin/env bash
# Docker Hub 이미지로 sporty 네임스페이스에 배포한다.
# 사용법: k8s/deploy.sh <DOCKERHUB_NAMESPACE> <TAG> [FRONTEND_TAG]
# 태그는 커밋 sha 7자리를 쓴다. 프론트는 저장소가 달라 태그가 다르면 세 번째 인자로 준다(생략 시 TAG).
# kustomization.yaml은 고치지 않고, 임시 overlay에서 이미지 이름·태그만 바꿔 적용한다.
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "사용법: $0 <DOCKERHUB_NAMESPACE> <TAG> [FRONTEND_TAG]" >&2
  echo "예: $0 batontouch 1a2b3c4 9f8e7d6" >&2
  exit 1
fi

REGISTRY_NS="$1"
TAG="$2"
FRONTEND_TAG="${3:-$TAG}"
# imagePullPolicy가 IfNotPresent라 latest처럼 같은 이름에 내용이 바뀌는 태그는 새 이미지를 받지 않는다. 커밋 sha 7자리만 받는다.
if ! [[ "$TAG" =~ ^[0-9a-f]{7}$ && "$FRONTEND_TAG" =~ ^[0-9a-f]{7}$ ]]; then
  echo "[멈춤] 태그는 커밋 sha 7자리(소문자 16진수)만 받는다. latest 같은 태그는 쓰지 않는다." >&2
  exit 1
fi
NS=sporty
K8S_DIR="$(cd "$(dirname "$0")" && pwd)"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"

APPS=(discovery config gateway user match facility ai notification)
SECRETS=(sporty-jwt sporty-rabbitmq user-service-secret match-service-secret facility-service-secret notification-service-secret)

echo "대상 클러스터: $(kubectl config current-context)"

# Secret에 키가 있고 값이 비어 있지 않은지 본다. 값은 출력하지 않는다. 키에 점이 있으면 \. 로 넘긴다.
has_key() { [ -n "$(kubectl -n "$NS" get secret "$1" -o "jsonpath={.data.$2}")" ]; }
# 선택 Secret이 있는지 본다. 없으면 1, 권한·연결 문제로 조회가 실패하면 "없음"으로 넘기지 않고 멈춘다.
secret_exists() {
  local out
  out="$(kubectl -n "$NS" get secret "$1" --ignore-not-found -o name)" || { echo "[멈춤] Secret $1 조회 실패(권한·클러스터 연결 확인)" >&2; exit 1; }
  [ -n "$out" ]
}

# Deployment가 참조하는 ConfigMap·Secret이 먼저 있어야 Pod가 뜬다.
missing=0
if ! kubectl get namespace "$NS" >/dev/null 2>&1; then
  echo "[멈춤] 네임스페이스 $NS 가 없다. kubectl apply -f $K8S_DIR/namespace.yaml 로 먼저 만든다." >&2
  exit 1
fi
if ! kubectl -n "$NS" get configmap sporty-infra-endpoints >/dev/null 2>&1; then
  echo "[없음] ConfigMap sporty-infra-endpoints (예시: k8s/infra-endpoints.example.yaml)" >&2
  missing=1
elif [ -z "$(kubectl -n "$NS" get configmap sporty-infra-endpoints -o jsonpath='{.data.RABBITMQ_HOST}')" ]; then
  echo "[없음] ConfigMap sporty-infra-endpoints 의 RABBITMQ_HOST (예시: k8s/infra-endpoints.example.yaml)" >&2
  missing=1
fi
for s in "${SECRETS[@]}"; do
  if ! kubectl -n "$NS" get secret "$s" >/dev/null 2>&1; then
    echo "[없음] Secret $s" >&2
    missing=1
  fi
done
# sporty-rabbitmq는 7개 Deployment가 필수로 읽는다. 키가 빠지면 Pod가 CreateContainerConfigError로 멈춘다.
if kubectl -n "$NS" get secret sporty-rabbitmq >/dev/null 2>&1; then
  for k in RABBITMQ_USER RABBITMQ_PASSWORD; do
    has_key sporty-rabbitmq "$k" || { echo "[없음] Secret sporty-rabbitmq 의 $k" >&2; missing=1; }
  done
fi
# 키스토어는 선택이지만, 만들었다면 키가 모두 있어야 한다. 하나라도 빠지면 config-service가 조용히 암호화 없이 뜨거나 기동에 실패한다.
keystore=0
if secret_exists config-encrypt-keystore; then
  keystore=1
  has_key config-encrypt-keystore 'config\.jks' || { echo "[없음] Secret config-encrypt-keystore 의 config.jks" >&2; missing=1; }
  for k in ENCRYPT_KEYSTORE_LOCATION ENCRYPT_KEYSTORE_PASSWORD ENCRYPT_KEYSTORE_ALIAS; do
    has_key config-encrypt-keystore "$k" || { echo "[없음] Secret config-encrypt-keystore 의 $k" >&2; missing=1; }
  done
fi
if [ "$missing" -ne 0 ]; then
  echo "[멈춤] 위 리소스를 k8s/README.md 'AWS 배포 절차' 2단계대로 만든 뒤 다시 실행한다." >&2
  exit 1
fi
# 키스토어는 선택이다. 없으면 {cipher} 복호화 없이 기동한다.
if [ "$keystore" -eq 0 ]; then
  echo "[안내] Secret config-encrypt-keystore 가 없어 config-service는 암호화 없이 기동한다. prod 설정에 {cipher} 값이 있으면 복호화되지 않는다."
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp -R "$K8S_DIR" "$TMP/base"

{
  echo "apiVersion: kustomize.config.k8s.io/v1beta1"
  echo "kind: Kustomization"
  echo "resources:"
  echo "  - base"
  echo "images:"
  for app in "${APPS[@]}"; do
    echo "  - name: sporty-${app}-service"
    echo "    newName: ${REGISTRY_NS}/sporty-${app}-service"
    echo "    newTag: \"${TAG}\""
  done
  echo "  - name: sporty-frontend"
  echo "    newName: ${REGISTRY_NS}/sporty-frontend"
  echo "    newTag: \"${FRONTEND_TAG}\""
} > "$TMP/kustomization.yaml"

kubectl apply -k "$TMP"

# Discovery·Config가 먼저 준비돼야 나머지가 설정을 받고 등록된다.
for app in "${APPS[@]}"; do
  kubectl -n "$NS" rollout status "deployment/${app}-service" --timeout="$ROLLOUT_TIMEOUT"
done
kubectl -n "$NS" rollout status deployment/zipkin --timeout="$ROLLOUT_TIMEOUT"
kubectl -n "$NS" rollout status deployment/frontend --timeout="$ROLLOUT_TIMEOUT"

echo
echo "배포 완료: ${REGISTRY_NS}/sporty-*-service:${TAG}, ${REGISTRY_NS}/sporty-frontend:${FRONTEND_TAG}"
echo "노드 사설 IP:"
kubectl get nodes -o jsonpath='{range .items[*]}  {.metadata.name} {.status.addresses[?(@.type=="InternalIP")].address}{"\n"}{end}'
echo "접속 주소 (워커 공인 IP 기준):"
echo "  프론트  http://<워커-공인-IP>:30000"
echo "  API     http://<워커-공인-IP>:30080/<서비스명>/..."
echo "  지표    http://<워커-사설-IP>:30101~30185/actuator/prometheus (infra EC2에서만)"
