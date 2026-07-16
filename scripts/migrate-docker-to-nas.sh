#!/usr/bin/env bash
# One-time cold cutover from the current local Docker context to the NAS context.
# Source DB is never modified; its containers/volume remain stopped and available
# as the immediate rollback anchor after a successful cutover.
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
# shellcheck source=scripts/lib/nas-deployment-safety.sh
source "$REPO_ROOT/scripts/lib/nas-deployment-safety.sh"

SOURCE_CONTEXT="${MAGICJAR_SOURCE_DOCKER_CONTEXT:-desktop-linux}"
TARGET_CONTEXT="${MAGICJAR_TARGET_DOCKER_CONTEXT:-nas}"
ENV_FILE="${MAGICJAR_ENV_FILE:-$REPO_ROOT/.env}"
NAS_OVERRIDE="$REPO_ROOT/docker/docker-compose.nas.yml"
EXPECTED_TARGET_ENDPOINT="${MAGICJAR_EXPECTED_DOCKER_ENDPOINT:-ssh://magicjar-nas-docker}"
CONFIRM=0

usage() {
  cat <<'EOF'
Usage: scripts/migrate-docker-to-nas.sh --confirm-cutover

Environment:
  MAGICJAR_SOURCE_DOCKER_CONTEXT  source context (default: desktop-linux)
  MAGICJAR_TARGET_DOCKER_CONTEXT  NAS context (default: nas)
  MAGICJAR_ENV_FILE               absolute env path (default: <repo>/.env)
  MAGICJAR_EXPECTED_GIT_SHA       approved full Git SHA (required)
  NAS_BIND_ADDRESS                frontend bind on NAS (default: 127.0.0.1)

Safety:
  - MOCK + ALLOW_REAL=false only
  - source in-flight orders and open strategy positions must both be zero
  - target magicjar_pg_data volume must not already exist
  - source apps restart only after target writers are proven stopped
  - target stop cannot be proven: fail-closed manual incident handling
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --confirm-cutover) CONFIRM=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
  esac
done

if [[ $CONFIRM -ne 1 ]]; then
  echo "✗ 실제 cutover는 --confirm-cutover 명시가 필요" >&2
  exit 1
fi
if [[ "$SOURCE_CONTEXT" == "$TARGET_CONTEXT" ]]; then
  echo "✗ source와 target context가 같음: $SOURCE_CONTEXT" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "✗ env file 부재: $ENV_FILE" >&2
  exit 1
fi
case "$ENV_FILE" in
  /*) ;;
  *) echo "✗ MAGICJAR_ENV_FILE은 절대경로여야 함: $ENV_FILE" >&2; exit 1 ;;
esac
mj_require_release_provenance "$REPO_ROOT"

SRC_DOCKER=(docker --context "$SOURCE_CONTEXT")
TGT_DOCKER=(docker --context "$TARGET_CONTEXT")
TARGET_BASE_COMPOSE=(--env-file "$ENV_FILE" -f "$REPO_ROOT/docker/docker-compose.yml" -f "$REPO_ROOT/docker/docker-compose.dev.yml")
TARGET_COMPOSE=("${TARGET_BASE_COMPOSE[@]}" -f "$NAS_OVERRIDE")
SOURCE_WRITERS=(magicjar-api magicjar-batch magicjar-consumer)
SOURCE_NON_WRITERS=(magicjar-frontend magicjar-dozzle magicjar-kafka-ui magicjar-kafka magicjar-postgres magicjar-redis)
SOURCE_CORE=("${SOURCE_WRITERS[@]}" "${SOURCE_NON_WRITERS[@]}")

src_docker() { "${SRC_DOCKER[@]}" "$@"; }
tgt_docker() { "${TGT_DOCKER[@]}" "$@"; }
tgt_compose() { tgt_docker compose "${TARGET_COMPOSE[@]}" "$@"; }

read_env_value() {
  python3 - "$ENV_FILE" "$1" <<'PY'
from pathlib import Path
import sys
value = ""
for raw in Path(sys.argv[1]).read_text(errors="replace").splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, candidate = line.split("=", 1)
    if key == sys.argv[2]:
        value = candidate.split(" #", 1)[0].strip().strip('"').strip("'")
print(value)
PY
}

mode="$(read_env_value MAGICJAR_MODE)"
allow_real="$(read_env_value MAGICJAR_ALLOW_REAL)"
profile="$(read_env_value SPRING_PROFILES_ACTIVE)"
if [[ "$mode" != "MOCK" || "$allow_real" != "false" || "$profile" != "dev,real-kis" ]]; then
  echo "✗ migration 안전 게이트: MODE=MOCK + ALLOW_REAL=false + profile=dev,real-kis 필요" >&2
  exit 1
fi

for context in "$SOURCE_CONTEXT" "$TARGET_CONTEXT"; do
  docker context inspect "$context" >/dev/null 2>&1 || {
    echo "✗ Docker context 부재: $context" >&2
    exit 1
  }
done
src_docker info >/dev/null
target_endpoint="$(docker context inspect "$TARGET_CONTEXT" --format '{{.Endpoints.docker.Host}}')"
case "$target_endpoint" in
  ssh://*|tcp://*) ;;
  *) echo "✗ target context가 remote endpoint가 아님: $target_endpoint" >&2; exit 1 ;;
esac
if [[ "$target_endpoint" != "$EXPECTED_TARGET_ENDPOINT" ]]; then
  echo "✗ target endpoint mismatch: expected=$EXPECTED_TARGET_ENDPOINT actual=$target_endpoint" >&2
  exit 1
fi
tgt_docker info >/dev/null || { echo "✗ NAS Docker 연결 실패: $target_endpoint" >&2; exit 1; }

echo "▶ source=$SOURCE_CONTEXT target=$TARGET_CONTEXT endpoint=$target_endpoint"
echo "▶ safety: mode=$mode allow_real=$allow_real profile=$profile"
echo "▶ release: git_sha=$MAGICJAR_GIT_SHA image_tag=$MAGICJAR_IMAGE_TAG"

if ! src_docker ps --format '{{.Names}}' | grep -q '^magicjar-postgres$'; then
  echo "✗ source magicjar-postgres가 running 아님" >&2
  exit 1
fi
for container in "${SOURCE_CORE[@]}"; do
  if [[ "$(src_docker inspect "$container" --format '{{.State.Running}}' 2>/dev/null || true)" != "true" ]]; then
    echo "✗ source core container가 running 아님: $container" >&2
    exit 1
  fi
done
if tgt_docker volume ls --format '{{.Name}}' | grep -Eq '^magicjar_(pg|kafka|redis)_data$'; then
  echo "✗ target state volume이 이미 존재 — 자동 덮어쓰기/삭제 금지, 수동 조사 필요" >&2
  exit 1
fi
if tgt_docker ps -a --format '{{.Names}}' | grep -q '^magicjar-'; then
  echo "✗ target에 magicjar 컨테이너가 이미 존재 — 자동 재사용 금지, 수동 조사 필요" >&2
  exit 1
fi

inflight="$(src_docker exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT COUNT(*) FROM order_request WHERE status IN ('REQUESTED','ACK','PARTIALLY_FILLED','PENDING_RECONCILE');")"
positions="$(src_docker exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT COUNT(*) FROM strategy_position WHERE quantity > 0;")"
if [[ "$inflight" != "0" || "$positions" != "0" ]]; then
  echo "✗ cutover 차단: in-flight=$inflight open_positions=$positions" >&2
  exit 1
fi

export MAGICJAR_ENV_FILE="$ENV_FILE"
export NAS_BIND_ADDRESS="${NAS_BIND_ADDRESS:-127.0.0.1}"
BACKUP_DIR="$REPO_ROOT/_workspace/backups/postgres/migration"
mkdir -p "$BACKUP_DIR"
DUMP_FILE="$BACKUP_DIR/pre-nas-cutover-$(date '+%Y-%m-%d-%H%M%S').dump"
CHECKSUM_FILE="${DUMP_FILE}.sha256"
COUNTS_FILE="${DUMP_FILE}.counts"
SOURCE_COUNTS="$(mktemp -t magicjar-source-counts.XXXXXX)"
TARGET_COUNTS="$(mktemp -t magicjar-target-counts.XXXXXX)"
SOURCE_APPS_STOPPED=0
CUTOVER_COMPLETE=0
TARGET_TMP_DUMP="/tmp/magicjar-nas-cutover.dump"

collect_counts() {
  local side="$1" output="$2"
  local -a cmd
  local table escaped count
  if [[ "$side" == "source" ]]; then cmd=("${SRC_DOCKER[@]}"); else cmd=("${TGT_DOCKER[@]}"); fi
  : > "$output"
  while IFS= read -r table; do
    [[ -n "$table" ]] || continue
    escaped="${table//\"/\"\"}"
    count="$("${cmd[@]}" exec magicjar-postgres psql -U magicjar -d magicjar -Atc "SELECT COUNT(*) FROM \"$escaped\";")"
    printf '%s=%s\n' "$table" "$count" >> "$output"
  done < <("${cmd[@]}" exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
    "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;")
}

restart_source_after_verified_target_stop() {
  if ! tgt_compose stop api-app batch-app consumer-app frontend >/dev/null 2>&1; then
    echo "🚨 target writer 정지 실패 — source 자동 재기동 금지, 수동 incident 대응 필요" >&2
    return 1
  fi
  if ! mj_assert_writers_stopped "$TARGET_CONTEXT"; then
    echo "🚨 target writer 정지 증명 실패 — source 자동 재기동 금지" >&2
    return 1
  fi
  if ! src_docker start "${SOURCE_CORE[@]}" >/dev/null 2>&1; then
    echo "🚨 target 정지는 확인했으나 기존 source container 재기동 실패 — 수동 incident 대응 필요" >&2
    return 1
  fi
  local container state deadline
  for container in "${SOURCE_CORE[@]}"; do
    deadline=$((SECONDS + 180))
    while :; do
      state="$(src_docker inspect "$container" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
      case "$state" in
        healthy|running) break ;;
        unhealthy|exited)
          echo "🚨 source container 복구 실패: $container=$state" >&2
          return 1
          ;;
      esac
      if (( SECONDS >= deadline )); then
        echo "🚨 source container 복구 timeout: $container=$state" >&2
        return 1
      fi
      sleep 3
    done
  done
  echo "✓ target writer 정지 확인 후 기존 source core container 복귀" >&2
}

cleanup() {
  local rc=$?
  tgt_docker exec magicjar-postgres rm -f "$TARGET_TMP_DUMP" >/dev/null 2>&1 || true
  rm -f "$SOURCE_COUNTS" "$TARGET_COUNTS"
  if [[ $rc -ne 0 && $SOURCE_APPS_STOPPED -eq 1 && $CUTOVER_COMPLETE -eq 0 ]]; then
    echo "⚠ cutover 실패 — target writer 정지 증명 후에만 source 복귀" >&2
    restart_source_after_verified_target_stop || true
  fi
  trap - EXIT
  exit "$rc"
}
trap cleanup EXIT

# Build and transfer images before downtime starts.
echo "▶ prebuild: fresh JVM artifacts"
./gradlew :api:bootJar :batch:bootJar :consumer:bootJar
echo "▶ prebuild: NAS native images"
tgt_compose build api-app batch-app consumer-app frontend

# Freeze every writer before the final dump. Infrastructure remains available.
echo "▶ source writers stop: api/batch/consumer"
SOURCE_APPS_STOPPED=1
src_docker stop "${SOURCE_WRITERS[@]}"

# Re-check after stop so a last-second in-flight transition cannot be missed.
inflight="$(src_docker exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT COUNT(*) FROM order_request WHERE status IN ('REQUESTED','ACK','PARTIALLY_FILLED','PENDING_RECONCILE');")"
positions="$(src_docker exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT COUNT(*) FROM strategy_position WHERE quantity > 0;")"
if [[ "$inflight" != "0" || "$positions" != "0" ]]; then
  echo "✗ source 정지 후 final flat gate 실패: in-flight=$inflight open_positions=$positions — rollback" >&2
  exit 1
fi

echo "▶ final pg_dump: $DUMP_FILE"
src_docker exec magicjar-postgres pg_dump -U magicjar -Fc magicjar > "$DUMP_FILE"
if [[ ! -s "$DUMP_FILE" ]]; then
  echo "✗ dump file이 비어 있음" >&2
  exit 1
fi
src_docker cp "$DUMP_FILE" "magicjar-postgres:$TARGET_TMP_DUMP"
src_docker exec magicjar-postgres pg_restore --list "$TARGET_TMP_DUMP" >/dev/null
src_docker exec magicjar-postgres rm -f "$TARGET_TMP_DUMP"
(
  cd "$(dirname "$DUMP_FILE")"
  shasum -a 256 "$(basename "$DUMP_FILE")" > "$(basename "$CHECKSUM_FILE")"
  shasum -a 256 -c "$(basename "$CHECKSUM_FILE")" >/dev/null
)
collect_counts source "$SOURCE_COUNTS"
cp "$SOURCE_COUNTS" "$COUNTS_FILE"

# Create only the target DB first; applications must not start before restore.
echo "▶ target postgres create"
tgt_compose up -d postgres
deadline=$((SECONDS + 120))
until [[ "$(tgt_docker inspect magicjar-postgres --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)" == "healthy" ]]; do
  if (( SECONDS >= deadline )); then
    echo "✗ target postgres health timeout" >&2
    exit 1
  fi
  sleep 3
done

echo "▶ restore current PostgreSQL data on NAS"
tgt_docker cp "$DUMP_FILE" "magicjar-postgres:$TARGET_TMP_DUMP"
local_dump_sha="$(awk '{print $1}' "$CHECKSUM_FILE")"
target_dump_sha="$(tgt_docker exec magicjar-postgres sha256sum "$TARGET_TMP_DUMP" | awk '{print $1}')"
if [[ "$local_dump_sha" != "$target_dump_sha" ]]; then
  echo "✗ target dump checksum mismatch: local=$local_dump_sha target=$target_dump_sha" >&2
  exit 1
fi
tgt_docker exec magicjar-postgres pg_restore --clean --if-exists --no-owner --exit-on-error \
  -U magicjar -d magicjar "$TARGET_TMP_DUMP"

collect_counts target "$TARGET_COUNTS"
if ! diff -u "$SOURCE_COUNTS" "$TARGET_COUNTS"; then
  echo "✗ source/target table row counts 불일치 — source 복귀" >&2
  exit 1
fi
source_flyway="$(src_docker exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1;")"
target_flyway="$(tgt_docker exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1;")"
target_flyway_failed="$(tgt_docker exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE success=false;")"
if [[ "$source_flyway" != "$target_flyway" || "$target_flyway_failed" != "0" ]]; then
  echo "✗ Flyway mismatch: source=$source_flyway target=$target_flyway" >&2
  exit 1
fi
echo "▶ DB parity PASS: all public table counts, Flyway V$target_flyway"

# Images are already built. Start the NAS stack through the canonical health gate.
MAGICJAR_DOCKER_CONTEXT="$TARGET_CONTEXT" \
MAGICJAR_COMPOSE_EXTRA_FILE="$NAS_OVERRIDE" \
MAGICJAR_ENV_FILE="$ENV_FILE" \
NAS_BIND_ADDRESS="$NAS_BIND_ADDRESS" \
MAGICJAR_GIT_SHA="$MAGICJAR_GIT_SHA" \
MAGICJAR_IMAGE_TAG="$MAGICJAR_IMAGE_TAG" \
MAGICJAR_DEPLOY_DRAIN_BLOCK=true \
  "$REPO_ROOT/scripts/build-and-deploy.sh" --no-gradle --no-build --no-claude-bridge

runtime_mode="$(tgt_docker inspect magicjar-consumer --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MAGICJAR_MODE=//p' | head -n1)"
runtime_allow="$(tgt_docker inspect magicjar-consumer --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MAGICJAR_ALLOW_REAL=//p' | head -n1)"
runtime_profile="$(tgt_docker inspect magicjar-consumer --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^SPRING_PROFILES_ACTIVE=//p' | head -n1)"
if [[ "$runtime_mode" != "MOCK" || "$runtime_allow" != "false" || "$runtime_profile" != "dev,real-kis" ]]; then
  echo "✗ NAS runtime safety mismatch: mode=$runtime_mode allow_real=$runtime_allow" >&2
  exit 1
fi

# Only now stop the remaining local services. Containers and pg_data stay intact.
echo "▶ target healthy + DB parity PASS — local stack stop (rollback anchor 보존)"
src_docker stop "${SOURCE_NON_WRITERS[@]}"
for container in "${SOURCE_CORE[@]}"; do
  if [[ "$(src_docker inspect "$container" --format '{{.State.Running}}')" != "false" ]]; then
    echo "✗ source core container stop 증명 실패: $container" >&2
    exit 1
  fi
done
CUTOVER_COMPLETE=1

echo "✓ NAS cutover complete"
echo "  source context stopped: $SOURCE_CONTEXT (volume preserved)"
echo "  target context active : $TARGET_CONTEXT"
echo "  backup                : $DUMP_FILE"
echo "  checksum              : $CHECKSUM_FILE"
echo "  row counts            : $COUNTS_FILE"
echo "  frontend bind          : $NAS_BIND_ADDRESS:${FRONTEND_HOST_PORT:-3000}"
