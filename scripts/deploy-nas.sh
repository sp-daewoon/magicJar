#!/usr/bin/env bash
# Build on the Mac and deploy the complete magicJar stack to an explicit NAS
# Docker context. This script never changes the global/current Docker context.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export MAGICJAR_DOCKER_CONTEXT="${MAGICJAR_DOCKER_CONTEXT:-nas}"
export MAGICJAR_ENV_FILE="${MAGICJAR_ENV_FILE:-$REPO_ROOT/.env}"
export MAGICJAR_COMPOSE_EXTRA_FILE="${MAGICJAR_COMPOSE_EXTRA_FILE:-docker/docker-compose.nas.yml}"
export NAS_BIND_ADDRESS="${NAS_BIND_ADDRESS:-127.0.0.1}"
export MAGICJAR_EXPECTED_DOCKER_ENDPOINT="${MAGICJAR_EXPECTED_DOCKER_ENDPOINT:-ssh://magicjar-nas-docker}"
export MAGICJAR_SOURCE_DOCKER_CONTEXT="${MAGICJAR_SOURCE_DOCKER_CONTEXT:-desktop-linux}"
PREFLIGHT_ONLY=0

case "${1:-}" in
  --preflight-only) PREFLIGHT_ONLY=1; shift ;;
  -h|--help)
    cat <<'EOF'
Usage: scripts/deploy-nas.sh [--preflight-only] [build-and-deploy options/services]

The command always targets MAGICJAR_DOCKER_CONTEXT (default: nas), applies
docker/docker-compose.nas.yml, and never changes the global Docker context.
MAGICJAR_EXPECTED_GIT_SHA (full approved SHA) is required. The source/local
consumer context (default: desktop-linux) must be reachable and stopped.
EOF
    exit 0
    ;;
esac

# shellcheck source=scripts/lib/docker-target.sh
source "$REPO_ROOT/scripts/lib/docker-target.sh"
# shellcheck source=scripts/lib/nas-deployment-safety.sh
source "$REPO_ROOT/scripts/lib/nas-deployment-safety.sh"

if [[ ! -f "$MAGICJAR_ENV_FILE" ]]; then
  echo "✗ env file 부재: $MAGICJAR_ENV_FILE" >&2
  exit 1
fi
case "$MAGICJAR_ENV_FILE" in
  /*) ;;
  *) echo "✗ MAGICJAR_ENV_FILE은 절대경로여야 함: $MAGICJAR_ENV_FILE" >&2; exit 1 ;;
esac

mj_require_release_provenance "$REPO_ROOT"

mj_require_remote_docker
actual_endpoint="$(mj_docker_endpoint)"
if [[ "$actual_endpoint" != "$MAGICJAR_EXPECTED_DOCKER_ENDPOINT" ]]; then
  echo "✗ NAS endpoint mismatch: expected=$MAGICJAR_EXPECTED_DOCKER_ENDPOINT actual=$actual_endpoint" >&2
  exit 1
fi

server_arch="$(mj_docker info --format '{{.Architecture}}')"
case "$server_arch" in
  x86_64|amd64) ;;
  *)
    echo "✗ 검증되지 않은 NAS architecture: $server_arch (현재 지원: amd64/x86_64)" >&2
    exit 1
    ;;
esac

# Print only safety metadata, never credentials or the full environment.
read_env_value() {
  local key="$1"
  python3 - "$MAGICJAR_ENV_FILE" "$key" <<'PY'
from pathlib import Path
import sys
path, key = Path(sys.argv[1]), sys.argv[2]
value = ""
for raw in path.read_text(errors="replace").splitlines():
    stripped = raw.strip()
    if not stripped or stripped.startswith("#") or "=" not in stripped:
        continue
    candidate, raw_value = stripped.split("=", 1)
    if candidate == key:
        value = raw_value.split(" #", 1)[0].strip().strip('"').strip("'")
print(value)
PY
}

mode="$(read_env_value MAGICJAR_MODE)"
allow_real="$(read_env_value MAGICJAR_ALLOW_REAL)"
profile="$(read_env_value SPRING_PROFILES_ACTIVE)"
echo "▶ safety: mode=${mode:-<unset>} allow_real=${allow_real:-<unset>} profile=${profile:-<unset>}"
echo "▶ release: git_sha=$MAGICJAR_GIT_SHA image_tag=$MAGICJAR_IMAGE_TAG"

if [[ "$profile" != "dev,real-kis" ]]; then
  echo "✗ profile mismatch: expected=dev,real-kis actual=${profile:-<unset>}" >&2
  exit 1
fi

if [[ "$mode" == "REAL" || "$allow_real" == "true" ]]; then
  if [[ "${MAGICJAR_NAS_ALLOW_REAL_DEPLOY:-false}" != "true" ]]; then
    echo "✗ NAS REAL 배포 차단 — MAGICJAR_NAS_ALLOW_REAL_DEPLOY=true 별도 승인 게이트 필요" >&2
    exit 1
  fi
fi

if [[ "$NAS_BIND_ADDRESS" != "127.0.0.1" ]]; then
  echo "⚠ frontend를 NAS 주소 $NAS_BIND_ADDRESS 에 공개합니다. 공용 인터넷 포트포워딩 금지."
else
  echo "▶ frontend는 NAS loopback 전용. 접속은 SSH tunnel 사용."
fi

if [[ $PREFLIGHT_ONLY -eq 1 ]]; then
  echo "✓ NAS preflight PASS"
  exit 0
fi

# A NAS consumer must never start while the source/local consumer is running.
mj_assert_consumer_stopped "$MAGICJAR_SOURCE_DOCKER_CONTEXT" "$MAGICJAR_DOCKER_CONTEXT"

export NO_CLAUDE_BRIDGE=1
export MAGICJAR_DEPLOY_DRAIN_BLOCK=true
exec "$REPO_ROOT/scripts/build-and-deploy.sh" --no-claude-bridge "$@"
