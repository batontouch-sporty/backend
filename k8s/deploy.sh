#!/usr/bin/env bash
# Docker Hub 이미지로 sporty 네임스페이스에 배포한다.
# 사용법: k8s/deploy.sh <DOCKERHUB_NAMESPACE> <TAG>
# kustomization.yaml은 고치지 않고, 임시 overlay에서 이미지 이름·태그만 바꿔 적용한다.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "사용법: $0 <DOCKERHUB_NAMESPACE> <TAG>" >&2
  echo "예: $0 batontouch 1a2b3c4" >&2
  exit 1
fi

REGISTRY_NS="$1"
TAG="$2"
NS=sporty
K8S_DIR="$(cd "$(dirname "$0")" && pwd)"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"

APPS=(discovery config gateway user match facility ai notification)
SECRETS=(sporty-jwt user-service-secret match-service-secret facility-service-secret notification-service-secret)

echo "대상 클러스터: $(kubectl config current-context)"

# Deployment가 참조하는 ConfigMap·Secret이 먼저 있어야 Pod가 뜬다.
missing=0
if ! kubectl get namespace "$NS" >/dev/null 2>&1; then
  echo "[멈춤] 네임스페이스 $NS 가 없다. kubectl apply -f $K8S_DIR/namespace.yaml 로 먼저 만든다." >&2
  exit 1
fi
if ! kubectl -n "$NS" get configmap sporty-infra-endpoints >/dev/null 2>&1; then
  echo "[없음] ConfigMap sporty-infra-endpoints (예시: k8s/infra-endpoints.example.yaml)" >&2
  missing=1
fi
for s in "${SECRETS[@]}"; do
  if ! kubectl -n "$NS" get secret "$s" >/dev/null 2>&1; then
    echo "[없음] Secret $s" >&2
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "[멈춤] 위 리소스를 k8s/README.md 'AWS 배포 절차' 2단계대로 만든 뒤 다시 실행한다." >&2
  exit 1
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
  echo "    newTag: \"${TAG}\""
} > "$TMP/kustomization.yaml"

kubectl apply -k "$TMP"

# Discovery·Config가 먼저 준비돼야 나머지가 설정을 받고 등록된다.
for app in "${APPS[@]}"; do
  kubectl -n "$NS" rollout status "deployment/${app}-service" --timeout="$ROLLOUT_TIMEOUT"
done
kubectl -n "$NS" rollout status deployment/zipkin --timeout="$ROLLOUT_TIMEOUT"
kubectl -n "$NS" rollout status deployment/frontend --timeout="$ROLLOUT_TIMEOUT"

echo
echo "배포 완료: ${REGISTRY_NS}/sporty-*:${TAG}"
echo "노드 사설 IP:"
kubectl get nodes -o jsonpath='{range .items[*]}  {.metadata.name} {.status.addresses[?(@.type=="InternalIP")].address}{"\n"}{end}'
echo "접속 주소 (워커 공인 IP 기준):"
echo "  프론트  http://<워커-공인-IP>:30000"
echo "  API     http://<워커-공인-IP>:30080/<서비스명>/..."
echo "  지표    http://<워커-사설-IP>:30101~30185/actuator/prometheus (infra EC2에서만)"
