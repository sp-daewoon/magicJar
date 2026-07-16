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
validate_nas_path() {
  local value="$1"
  [[ "$value" == /volume1/docker/magicjar/ops || "$value" == /volume1/docker/magicjar/backups ]]
}
validate_nas_path "$TARGET_DIR" || { echo "✗ unsafe target dir: $TARGET_DIR" >&2; exit 1; }
validate_nas_path "$BACKUP_ROOT" || { echo "✗ unsafe backup root: $BACKUP_ROOT" >&2; exit 1; }

mj_require_release_provenance "$REPO_ROOT"
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
image="$(mj_docker inspect magicjar-postgres --format '{{.Config.Image}}')"
[[ -n "$image" ]] || { echo "✗ magicjar-postgres image unresolved" >&2; exit 1; }

mj_docker run --rm --pull never \
  -v /volume1/docker/magicjar:/nas-root \
  "$image" sh -ceu '
    for path in /nas-root/ops /nas-root/backups; do
      [ ! -L "$path" ] || { echo "symlink path rejected: $path" >&2; exit 1; }
      mkdir -p "$path"
      [ "$(readlink -f "$path")" = "$path" ] || { echo "non-canonical path rejected: $path" >&2; exit 1; }
    done
  '

local_sha="$(shasum -a 256 "$SOURCE" | awk '{print $1}')"
mj_docker run --rm --pull never -i \
  -v "$TARGET_DIR:/target" \
  -v "$BACKUP_ROOT:/backups" \
  "$image" sh -ceu '
    expected="$1"
    final=/target/nas-task-scheduler-backup.sh
    tmp="/target/.nas-task-scheduler-backup.sh.tmp.$$"
    prev_tmp="/target/.nas-task-scheduler-backup.sh.prev.tmp.$$"
    trap '\''rm -f "$tmp" "$prev_tmp"'\'' EXIT HUP INT TERM
    mkdir -p /target /backups/sets
    cat > "$tmp"
    checksum_line="$(sha256sum "$tmp")" || exit 1
    actual="${checksum_line%% *}"
    [ "$actual" = "$expected" ] || { echo "provisioned checksum mismatch" >&2; exit 1; }
    chmod 0700 "$tmp"
    if [ -f "$final" ]; then
      cp -p "$final" "$prev_tmp"
      mv "$prev_tmp" /target/nas-task-scheduler-backup.sh.prev
    fi
    mv "$tmp" "$final"
    trap - EXIT HUP INT TERM
  ' sh "$local_sha" \
  < "$SOURCE"

remote_sha="$(mj_docker run --rm --pull never -v "$TARGET_DIR:/target:ro" "$image" sha256sum /target/nas-task-scheduler-backup.sh | awk '{print $1}')"
[[ "$local_sha" == "$remote_sha" ]] || { echo "✗ provisioned checksum mismatch" >&2; exit 1; }
remote_mode="$(mj_docker run --rm --pull never -v "$TARGET_DIR:/target:ro" "$image" stat -c '%a' /target/nas-task-scheduler-backup.sh)"
[[ "$remote_mode" == 700 ]] || { echo "✗ provisioned mode mismatch" >&2; exit 1; }

cat <<EOF
✓ NAS backup entrypoint provisioned
  path     : $TARGET
  sha256   : $remote_sha
  schedule : daily 03:00 KST
  DSM task : $TARGET >> $TARGET_DIR/backup.log 2>&1

PENDING: DSM Task Scheduler registration and one scheduler-triggered restore drill.
EOF
