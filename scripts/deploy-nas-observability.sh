#!/usr/bin/env bash
# Deploy the observability overlay to the attested NAS Docker context without
# daemon-host bind mounts. Core application containers are never recreated.
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export MAGICJAR_DOCKER_CONTEXT="${MAGICJAR_DOCKER_CONTEXT:-nas}"
export MAGICJAR_EXPECTED_DOCKER_ENDPOINT="${MAGICJAR_EXPECTED_DOCKER_ENDPOINT:-ssh://magicjar-nas-docker}"
export MAGICJAR_ENV_FILE="${MAGICJAR_ENV_FILE:-$REPO_ROOT/.env}"

# shellcheck source=scripts/lib/docker-target.sh
source "$REPO_ROOT/scripts/lib/docker-target.sh"
# shellcheck source=scripts/lib/nas-deployment-safety.sh
source "$REPO_ROOT/scripts/lib/nas-deployment-safety.sh"

usage() {
  cat <<'EOF'
Usage: scripts/deploy-nas-observability.sh <preflight|up|status|down>

Environment:
  MAGICJAR_EXPECTED_GIT_SHA       full approved SHA (preflight/up)
  MAGICJAR_ENV_FILE               absolute env file
  MAGICJAR_DOCKER_CONTEXT         default: nas
  MAGICJAR_EXPECTED_DOCKER_ENDPOINT default: ssh://magicjar-nas-docker
  NAS_BIND_ADDRESS                default: 127.0.0.1

The overlay uses Compose configs.content, pinned linux/amd64 image digests,
external network magicjar_default, named volumes, and loopback-only ports.
It never recreates the core nine containers.
EOF
}

die() { printf '✗ %s\n' "$*" >&2; exit 1; }
log() { printf '▶ %s\n' "$*"; }
ok() { printf '✓ %s\n' "$*"; }

cmd="${1:-}"
case "$cmd" in
  preflight|up|status|down) ;;
  -h|--help|help|"") usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

mj_require_remote_docker
actual_endpoint="$(mj_docker_endpoint)"
[[ "$actual_endpoint" == "$MAGICJAR_EXPECTED_DOCKER_ENDPOINT" ]] ||
  die "NAS endpoint mismatch: expected=$MAGICJAR_EXPECTED_DOCKER_ENDPOINT actual=$actual_endpoint"

assert_observability_identity() {
  local name="$1" expected_service="$2" project service
  project="$(mj_docker inspect "$name" --format '{{index .Config.Labels "com.docker.compose.project"}}')"
  service="$(mj_docker inspect "$name" --format '{{index .Config.Labels "com.docker.compose.service"}}')"
  [[ "$project" == magicjar-observability && "$service" == "$expected_service" ]] ||
    die "$name identity mismatch: project=$project service=$service"
}

# Incident-safe status/down: no repository env file or Grafana credential is required.
if [[ "$cmd" == status || "$cmd" == down ]]; then
  existing=()
  for pair in magicjar-prometheus:prometheus magicjar-grafana:grafana magicjar-alertmanager:alertmanager; do
    name="${pair%%:*}"
    service="${pair##*:}"
    if mj_docker inspect "$name" >/dev/null 2>&1; then
      assert_observability_identity "$name" "$service"
      existing+=("$name")
    fi
  done
  if [[ "$cmd" == status ]]; then
    ((${#existing[@]})) || { ok "observability containers absent"; exit 0; }
    mj_docker ps -a --filter label=com.docker.compose.project=magicjar-observability \
      --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
    exit 0
  fi
  if ((${#existing[@]})); then
    mj_docker stop -t 30 "${existing[@]}" >/dev/null
    mj_docker rm "${existing[@]}" >/dev/null
  fi
  ok "observability containers removed; named volumes retained"
  exit 0
fi

[[ -f "$MAGICJAR_ENV_FILE" ]] || die "env file 부재: $MAGICJAR_ENV_FILE"
[[ "$MAGICJAR_ENV_FILE" = /* ]] || die "MAGICJAR_ENV_FILE은 절대경로여야 함"

server_arch="$(mj_docker info --format '{{.Architecture}}')"
case "$server_arch" in amd64|x86_64) ;; *) die "unsupported NAS architecture: $server_arch" ;; esac

read_env_value() {
  python3 - "$MAGICJAR_ENV_FILE" "$1" <<'PY'
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

password="$(read_env_value GRAFANA_ADMIN_PASSWORD)"
grafana_user="$(read_env_value GRAFANA_ADMIN_USER)"
grafana_user="${grafana_user:-admin}"
slack="$(read_env_value ALERTMANAGER_SLACK_WEBHOOK)"
[[ -n "$password" ]] || die "GRAFANA_ADMIN_PASSWORD 미설정"
case "$password" in admin|password|changeme) die "GRAFANA_ADMIN_PASSWORD unsafe default" ;; esac
[[ "$password" != "$grafana_user" ]] || die "GRAFANA_ADMIN_PASSWORD must differ from admin user"
(( ${#password} >= 20 )) || die "GRAFANA_ADMIN_PASSWORD must be at least 20 characters"

export GRAFANA_ADMIN_PASSWORD="$password"
export GRAFANA_ADMIN_USER="$grafana_user"
export ALERTMANAGER_SLACK_WEBHOOK="$slack"
export NAS_BIND_ADDRESS=127.0.0.1
export MAGICJAR_OBSERVABILITY_REVISION="${MAGICJAR_EXPECTED_GIT_SHA:-$(git rev-parse HEAD)}"

rendered="$(mktemp -t magicjar-nas-observability.XXXXXX.yml)"
verify_dir=""
cleanup() {
  rm -f "$rendered"
  [[ -z "$verify_dir" ]] || rm -rf "$verify_dir"
}
trap cleanup EXIT
python3 "$REPO_ROOT/scripts/render-nas-observability-compose.py" --output "$rendered"

obs_compose() {
  mj_docker compose --env-file "$MAGICJAR_ENV_FILE" -f "$rendered" "$@"
}

validate_model() {
  local json
  json="$(obs_compose config --format json)"
  python3 -c '
import json, sys
m=json.load(sys.stdin)
expected={"prometheus","grafana","alertmanager"}
assert set(m["services"]) == expected, set(m["services"])
for name, service in m["services"].items():
    assert service.get("restart") == "unless-stopped", name
    assert service.get("stop_grace_period") == "30s", name
    assert service["labels"]["org.opencontainers.image.revision"] != "status-only", name
    for volume in service.get("volumes", []):
        assert volume.get("type") == "volume", (name, volume)
    for port in service.get("ports", []):
        assert port.get("host_ip") == "127.0.0.1", (name, port)
assert m["services"]["grafana"]["environment"]["GF_AUTH_ANONYMOUS_ENABLED"] == "false"
assert m["networks"]["magicjar_core"]["external"] is True
prom=m["configs"]["prometheus_config"]["content"]
assert "host.docker.internal" not in prom
assert "environment: nas" in prom and "cluster: magicjar-nas" in prom
' <<<"$json"
}

require_core_runtime() {
  mj_docker network inspect magicjar_default >/dev/null 2>&1 || die "core network magicjar_default 부재"
  local name state health
  for name in magicjar-api magicjar-batch magicjar-consumer magicjar-frontend magicjar-postgres magicjar-kafka magicjar-redis magicjar-kafka-ui magicjar-dozzle; do
    state="$(mj_docker inspect "$name" --format '{{.State.Status}}' 2>/dev/null || true)"
    health="$(mj_docker inspect "$name" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)"
    [[ "$state" == "running" ]] || die "$name runtime not ready: state=$state health=$health"
    [[ "$health" == "none" || "$health" == "healthy" ]] || die "$name health not ready: $health"
  done
}

snapshot_core_runtime() {
  local name
  for name in magicjar-api magicjar-batch magicjar-consumer magicjar-frontend magicjar-postgres magicjar-kafka magicjar-redis magicjar-kafka-ui magicjar-dozzle; do
    mj_docker inspect "$name" --format '{{.Name}}|{{.Id}}|{{.State.StartedAt}}'
  done
}

wait_healthy() {
  local name deadline state health
  for name in magicjar-prometheus magicjar-grafana magicjar-alertmanager; do
    deadline=$((SECONDS + 180))
    while (( SECONDS < deadline )); do
      state="$(mj_docker inspect "$name" --format '{{.State.Status}}' 2>/dev/null || true)"
      health="$(mj_docker inspect "$name" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)"
      [[ "$state" == "running" && "$health" == "healthy" ]] && break
      sleep 3
    done
    [[ "$state" == "running" && "$health" == "healthy" ]] || return 1
    ok "$name healthy"
  done
}

verify_grafana_auth() {
  # Credentials remain inside the container environment and HTTP stdin; they
  # are never expanded into the host command line or emitted to logs.
  mj_docker exec magicjar-grafana sh -ec '
    auth="$(printf "%s:%s" "$GF_SECURITY_ADMIN_USER" "$GF_SECURITY_ADMIN_PASSWORD" | base64 | tr -d "\n")"
    status="$(
      { printf "GET /api/user HTTP/1.0\r\nHost: 127.0.0.1\r\nAuthorization: Basic %s\r\nConnection: close\r\n\r\n" "$auth"; } |
        nc -w 5 127.0.0.1 3000 |
        sed -n "1p" |
        tr -d "\r"
    )"
    [ "$status" = "HTTP/1.1 200 OK" ] || [ "$status" = "HTTP/1.0 200 OK" ]
  ' >/dev/null
  ok "GRAFANA_AUTH_PASS"
}

verify_targets() {
  local targets rules
  targets="$(mj_docker exec magicjar-prometheus wget -qO- http://localhost:9090/api/v1/targets)"
  python3 -c '
import json, sys
p=json.load(sys.stdin)
active=p["data"]["activeTargets"]
wanted={"magicjar-api-docker","magicjar-batch-docker","magicjar-consumer-docker","magicjar-alertmanager","prometheus-self"}
state={t["labels"].get("job"):t.get("health") for t in active}
missing=wanted-set(state)
down={job:state.get(job) for job in wanted if state.get(job)!="up"}
assert not missing and not down, {"missing":sorted(missing),"down":down}
print("TARGETS_PASS " + " ".join(f"{job}=up" for job in sorted(wanted)))
' <<<"$targets"
  rules="$(mj_docker exec magicjar-prometheus wget -qO- http://localhost:9090/api/v1/rules)"
  python3 -c '
import json, sys
p=json.load(sys.stdin)
groups=p["data"].get("groups", [])
assert groups, "no Prometheus rule groups loaded"
print(f"RULES_PASS groups={len(groups)}")
' <<<"$rules"
}

verify_config_integrity() {
  local container target source expected actual
  verify_dir="$(mktemp -d -t magicjar-observability-configs.XXXXXX)"
  while IFS='|' read -r container target source; do
    mj_docker cp "$container:$target" "$verify_dir/actual" >/dev/null
    expected="$(shasum -a 256 "$REPO_ROOT/$source" | awk '{print $1}')"
    actual="$(shasum -a 256 "$verify_dir/actual" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || {
      echo "✗ config checksum mismatch: $container:$target" >&2
      return 1
    }
  done <<'EOF'
magicjar-prometheus|/etc/prometheus/prometheus.yml|docker/observability/prometheus/prometheus.nas.yml
magicjar-prometheus|/etc/prometheus/rules/magicjar-alerts.yml|docker/observability/prometheus/rules/magicjar-alerts.yml
magicjar-grafana|/etc/grafana/provisioning/datasources/prometheus.yml|docker/observability/grafana/provisioning/datasources/prometheus.yml
magicjar-grafana|/etc/grafana/provisioning/dashboards/magicjar.yml|docker/observability/grafana/provisioning/dashboards/magicjar.yml
magicjar-grafana|/var/lib/grafana/dashboards/magicjar-jvm.json|docker/observability/grafana/dashboards/magicjar-jvm.json
magicjar-grafana|/var/lib/grafana/dashboards/magicjar-slo.json|docker/observability/grafana/dashboards/magicjar-slo.json
magicjar-grafana|/var/lib/grafana/dashboards/magicjar-kis-ratelimit.json|docker/observability/grafana/dashboards/magicjar-kis-ratelimit.json
magicjar-alertmanager|/etc/alertmanager/alertmanager.yml|docker/observability/alertmanager/alertmanager.yml
magicjar-alertmanager|/etc/alertmanager/alertmanager-noslack.yml|docker/observability/alertmanager/alertmanager-noslack.yml
EOF
  rm -rf "$verify_dir"
  verify_dir=""
  ok "CONFIG_INTEGRITY_PASS files=9"
}

case "$cmd" in
  preflight)
    mj_require_release_provenance "$REPO_ROOT"
    validate_model
    require_core_runtime
    ok "NAS observability preflight PASS"
    ;;
  up)
    mj_require_release_provenance "$REPO_ROOT"
    validate_model
    require_core_runtime
    core_before="$(snapshot_core_runtime)"
    [[ -n "$slack" ]] || printf '⚠ ALERTMANAGER_SLACK_WEBHOOK 미설정 — blackhole/DEGRADED\n'

    existing_count=0
    for pair in magicjar-prometheus:prometheus magicjar-grafana:grafana magicjar-alertmanager:alertmanager; do
      name="${pair%%:*}"
      service="${pair##*:}"
      if mj_docker inspect "$name" >/dev/null 2>&1; then
        existing_count=$((existing_count + 1))
        assert_observability_identity "$name" "$service"
        revision="$(mj_docker inspect "$name" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')"
        [[ "$revision" == "$MAGICJAR_EXPECTED_GIT_SHA" ]] ||
          die "$name revision mismatch; explicit down/LKG plan required before upgrade"
      fi
    done
    if (( existing_count > 0 && existing_count < 3 )); then
      die "partial observability stack detected; inspect then use incident-safe down"
    fi
    if (( existing_count == 3 )); then
      wait_healthy || die "existing observability stack unhealthy; no containers changed"
      verify_grafana_auth || die "existing Grafana admin credential mismatch; no containers changed"
      verify_config_integrity || die "existing observability config mismatch; no containers changed"
      verify_targets || die "existing observability target/rule verification failed; no containers changed"
      core_after="$(snapshot_core_runtime)"
      [[ "$core_after" == "$core_before" ]] || die "core runtime identity changed during verification"
      ok "CORE_INVARIANCE_PASS containers=9"
      ok "NAS observability runtime PASS (idempotent verification; notification route: $([[ -n "$slack" ]] && echo configured || echo PENDING))"
      exit 0
    fi

    while IFS= read -r image; do
      log "pull pinned image: ${image%%@*}"
      mj_docker pull "$image" >/dev/null
    done < <(python3 "$REPO_ROOT/scripts/render-nas-observability-compose.py" --images)
    if ! obs_compose up -d; then
      obs_compose down >/dev/null 2>&1 || true
      die "observability compose up 실패; containers removed, volumes retained"
    fi
    if ! wait_healthy; then
      obs_compose ps >&2 || true
      obs_compose down >/dev/null 2>&1 || true
      die "observability health timeout; containers removed, volumes retained"
    fi
    if ! verify_grafana_auth || ! verify_config_integrity || ! verify_targets; then
      obs_compose down >/dev/null 2>&1 || true
      die "observability verification failed; containers removed, volumes retained"
    fi
    core_after="$(snapshot_core_runtime)"
    if [[ "$core_after" != "$core_before" ]]; then
      obs_compose down >/dev/null 2>&1 || true
      die "core runtime identity changed; observability rolled back"
    fi
    ok "CORE_INVARIANCE_PASS containers=9"
    ok "NAS observability runtime PASS (notification route: $([[ -n "$slack" ]] && echo configured || echo PENDING))"
    ;;
esac
