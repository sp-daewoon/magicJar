#!/usr/bin/env bash
# Provision the NAS-native backup entrypoint at a stable Synology path.
# Task registration remains in DSM Task Scheduler; this script never edits cron.
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export MAGICJAR_DOCKER_CONTEXT="${MAGICJAR_DOCKER_CONTEXT:-nas}"
export MAGICJAR_EXPECTED_DOCKER_ENDPOINT="${MAGICJAR_EXPECTED_DOCKER_ENDPOINT:-ssh://magicjar-nas-docker}"
TARGET_DIR="${MAGICJAR_NAS_OPS_DIR:-/volume1/docker/magicjar/ops}"
BACKUP_ROOT="${MAGICJAR_NAS_BACKUP_ROOT:-/volume1/docker/magicjar/backups}"
SOURCE="$REPO_ROOT/scripts/backup/nas-task-scheduler-backup.sh"
TARGET="$TARGET_DIR/nas-task-scheduler-backup.sh"

# shellcheck source=scripts/lib/docker-target.sh
source "$REPO_ROOT/scripts/lib/docker-target.sh"
# shellcheck source=scripts/lib/nas-deployment-safety.sh
source "$REPO_ROOT/scripts/lib/nas-deployment-safety.sh"

usage() {
  cat <<'EOF'
Usage: scripts/install-nas-backup-entrypoint.sh

Provisions /volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh through
the attested NAS Docker API. It does not modify Synology crontab or register a
DSM task. Register the printed command in DSM Task Scheduler at 03:00 KST.

MAGICJAR_EXPECTED_GIT_SHA (full approved SHA) is required.
EOF
}

case "${1:-}" in -h|--help|help) usage; exit 0 ;; "") ;; *) usage >&2; exit 2 ;; esac
[[ -f "$SOURCE" ]] || { echo "✗ source missing: $SOURCE" >&2; exit 1; }
[[ "$TARGET_DIR" == /volume1/docker/magicjar/ops ]] || { echo "✗ unsafe target dir: $TARGET_DIR" >&2; exit 1; }
[[ "$BACKUP_ROOT" == /volume1/docker/magicjar/backups ]] || { echo "✗ unsafe backup root: $BACKUP_ROOT" >&2; exit 1; }

mj_require_release_provenance "$REPO_ROOT"
SNAPSHOT_DIR="$(mktemp -d -t magicjar-backup-installer.XXXXXX)"
cleanup_snapshot() { rm -rf "$SNAPSHOT_DIR"; }
trap cleanup_snapshot EXIT
trap 'cleanup_snapshot; exit 129' HUP
trap 'cleanup_snapshot; exit 130' INT
trap 'cleanup_snapshot; exit 143' TERM
SOURCE_SNAPSHOT="$SNAPSHOT_DIR/nas-task-scheduler-backup.sh"
COMPOSE_SNAPSHOT="$SNAPSHOT_DIR/docker-compose.nas.yml"
git show "$MAGICJAR_EXPECTED_GIT_SHA:scripts/backup/nas-task-scheduler-backup.sh" > "$SOURCE_SNAPSHOT" || {
  echo "✗ approved backup entrypoint blob unavailable" >&2
  exit 1
}
git show "$MAGICJAR_EXPECTED_GIT_SHA:docker/docker-compose.nas.yml" > "$COMPOSE_SNAPSHOT" || {
  echo "✗ approved NAS Compose blob unavailable" >&2
  exit 1
}
mj_require_remote_docker
actual_endpoint="$(mj_docker_endpoint)"
[[ "$actual_endpoint" == "$MAGICJAR_EXPECTED_DOCKER_ENDPOINT" ]] || {
  echo "✗ NAS endpoint mismatch: expected=$MAGICJAR_EXPECTED_DOCKER_ENDPOINT actual=$actual_endpoint" >&2
  exit 1
}

[[ "$(mj_docker inspect magicjar-postgres --format '{{.State.Running}}')" == true ]] || {
  echo "✗ magicjar-postgres is not running" >&2
  exit 1
}
[[ "$(mj_docker inspect magicjar-postgres --format '{{index .Config.Labels "com.docker.compose.project"}}')" == magicjar ]] || {
  echo "✗ magicjar-postgres Compose project mismatch" >&2
  exit 1
}
[[ "$(mj_docker inspect magicjar-postgres --format '{{index .Config.Labels "com.docker.compose.service"}}')" == postgres ]] || {
  echo "✗ magicjar-postgres Compose service mismatch" >&2
  exit 1
}
expected_image="$(python3 - "$COMPOSE_SNAPSHOT" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
match = re.search(r"(?ms)^  postgres:\n(?:(?:    .*|\s*)\n)*?    image:\s*(\S+)\s*$", text)
if not match or "@sha256:" not in match.group(1):
    raise SystemExit(1)
print(match.group(1))
PY
)" || { echo "✗ approved PostgreSQL digest unresolved from NAS Compose" >&2; exit 1; }
configured_image="$(mj_docker inspect magicjar-postgres --format '{{.Config.Image}}')"
[[ "$configured_image" == "$expected_image" ]] || { echo "✗ magicjar-postgres approved image reference mismatch" >&2; exit 1; }
image="$(mj_docker inspect magicjar-postgres --format '{{.Image}}')"
expected_image_id="$(mj_docker image inspect "$expected_image" --format '{{.Id}}')"
[[ "$image" =~ ^sha256:[a-f0-9]{64}$ && "$image" == "$expected_image_id" ]] || {
  echo "✗ magicjar-postgres running image ID does not match approved digest" >&2
  exit 1
}

local_sha="$(shasum -a 256 "$SOURCE_SNAPSHOT" | awk '{print $1}')"
provision_result="$(mj_docker run --rm --pull never --network none --read-only \
  --entrypoint sh --cap-drop ALL --security-opt no-new-privileges -i \
  -v /volume1:/nas-volume1:ro \
  -v /volume1/docker/magicjar:/nas-magicjar \
  "$image" -ceu '
    expected="$1"
    readonly_root=/nas-volume1/docker/magicjar
    root=/nas-magicjar
    ops="$root/ops"
    backups="$root/backups"
    sets="$backups/sets"
    for path in /nas-volume1/docker "$readonly_root"; do
      [ ! -L "$path" ] && [ -d "$path" ] || { echo "unsafe NAS path: $path" >&2; exit 1; }
      [ "$(readlink -f "$path")" = "$path" ] || { echo "non-canonical NAS path: $path" >&2; exit 1; }
    done
    [ "$(stat -c "%d:%i" "$readonly_root")" = "$(stat -c "%d:%i" "$root")" ] || {
      echo "NAS magicjar mount identity mismatch" >&2
      exit 1
    }
    for path in "$root" "$ops" "$backups" "$sets"; do
      if [ -e "$path" ]; then
        [ ! -L "$path" ] && [ -d "$path" ] || { echo "unsafe NAS path: $path" >&2; exit 1; }
      else
        mkdir "$path"
      fi
      [ "$(readlink -f "$path")" = "$path" ] || { echo "non-canonical NAS path: $path" >&2; exit 1; }
    done
    ops_canonical="$ops"
    cd "$ops"
    [ "$(readlink -f .)" = "$ops_canonical" ] || { echo "ops path changed before pin" >&2; exit 1; }
    final=./nas-task-scheduler-backup.sh
    previous=./nas-task-scheduler-backup.sh.prev
    tmp="$(mktemp ./.nas-task-scheduler-backup.sh.tmp.XXXXXX)"
    prev_tmp="$(mktemp ./.nas-task-scheduler-backup.sh.prev.tmp.XXXXXX)"
    trap '\''rm -f "$tmp" "$prev_tmp"'\'' EXIT HUP INT TERM
    cat > "$tmp"
    checksum_line="$(sha256sum "$tmp")" || exit 1
    actual="${checksum_line%% *}"
    [ "$actual" = "$expected" ] || { echo "provisioned checksum mismatch" >&2; exit 1; }
    chmod 0700 "$tmp"
    if [ -e "$final" ]; then
      [ ! -L "$final" ] && [ -f "$final" ] || { echo "unsafe existing entrypoint" >&2; exit 1; }
      cp -p "$final" "$prev_tmp"
      [ ! -e "$previous" ] || { [ ! -L "$previous" ] && [ -f "$previous" ]; } || { echo "unsafe previous entrypoint" >&2; exit 1; }
      mv -T "$prev_tmp" "$previous"
    fi
    mv -T "$tmp" "$final"
    [ ! -L "$final" ] && [ -f "$final" ] || { echo "installed entrypoint is not a regular file" >&2; exit 1; }
    [ "$(readlink -f .)" = "$ops_canonical" ] || { echo "ops path changed during install" >&2; exit 1; }
    [ "$(readlink -f "$final")" = "$ops_canonical/nas-task-scheduler-backup.sh" ] || { echo "installed entrypoint path changed" >&2; exit 1; }
    post_line="$(sha256sum "$final")" || exit 1
    post_sha="${post_line%% *}"
    [ "$post_sha" = "$expected" ] || { echo "installed checksum mismatch" >&2; exit 1; }
    mode="$(stat -c "%a" "$final")"
    [ "$mode" = 700 ] || { echo "installed mode mismatch" >&2; exit 1; }
    trap - EXIT HUP INT TERM
    printf "%s %s\n" "$post_sha" "$mode"
  ' sh "$local_sha" \
  < "$SOURCE_SNAPSHOT")"
read -r remote_sha remote_mode <<<"$provision_result"
[[ "$local_sha" == "$remote_sha" ]] || { echo "✗ provisioned checksum mismatch" >&2; exit 1; }
[[ "$remote_mode" == 700 ]] || { echo "✗ provisioned mode mismatch" >&2; exit 1; }

cat <<EOF
✓ NAS backup entrypoint provisioned
  path     : $TARGET
  sha256   : $remote_sha
  schedule : daily 03:00 KST
  DSM task : $TARGET >> $TARGET_DIR/backup.log 2>&1

PENDING: DSM Task Scheduler registration and one scheduler-triggered restore drill.
EOF
