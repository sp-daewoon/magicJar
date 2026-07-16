#!/bin/sh
# NAS-native PostgreSQL + Redis backup entrypoint for Synology Task Scheduler.
# No repository checkout or Docker context is required on the NAS.
set -eu
umask 077

PATH="/usr/local/bin:/usr/bin:/bin:/var/packages/ContainerManager/target/usr/bin:${PATH:-}"
BACKUP_ROOT="${MAGICJAR_NAS_BACKUP_ROOT:-/volume1/docker/magicjar/backups}"
PG_DIR="${BACKUP_ROOT}/postgres/daily"
REDIS_DIR="${BACKUP_ROOT}/redis/snapshots"
PG_CONTAINER="${MAGICJAR_PG_CONTAINER:-magicjar-postgres}"
REDIS_CONTAINER="${MAGICJAR_REDIS_CONTAINER:-magicjar-redis}"
DB_USER="${MAGICJAR_PG_USER:-magicjar}"
DB_NAME="${MAGICJAR_PG_DB:-magicjar}"
PG_RETENTION_DAYS="${MAGICJAR_PG_BACKUP_RETENTION_DAYS:-7}"
REDIS_RETENTION_DAYS="${MAGICJAR_REDIS_BACKUP_RETENTION_DAYS:-3}"
MIN_FREE_KB="${MAGICJAR_NAS_BACKUP_MIN_FREE_KB:-102400}"
LOCK_DIR="${BACKUP_ROOT}/.backup.lock"
STAMP="$(TZ=Asia/Seoul date '+%Y-%m-%d-%H%M%S')"
PG_FINAL="${PG_DIR}/${STAMP}.dump"
PG_PARTIAL="${PG_FINAL}.partial"
REDIS_FINAL="${REDIS_DIR}/dump-${STAMP}.rdb"
REDIS_PARTIAL="${REDIS_FINAL}.partial"

log() { printf '[%s KST] [nas-backup] %s\n' "$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S')" "$*"; }
fail() { log "ERROR: $*" >&2; exit 1; }

find_docker() {
  if [ -n "${MAGICJAR_DOCKER_BIN:-}" ]; then
    [ -x "$MAGICJAR_DOCKER_BIN" ] || return 1
    printf '%s\n' "$MAGICJAR_DOCKER_BIN"
    return 0
  fi
  for candidate in \
    /usr/local/bin/docker \
    /var/packages/ContainerManager/target/usr/bin/docker \
    /usr/bin/docker
  do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  command -v docker 2>/dev/null || return 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail "SHA-256 utility not found"
  fi
}

assert_compose_container() {
  container="$1"
  expected_service="$2"
  "$DOCKER" inspect "$container" >/dev/null 2>&1 || fail "container missing: $container"
  running="$("$DOCKER" inspect "$container" --format '{{.State.Running}}' 2>/dev/null || true)"
  project="$("$DOCKER" inspect "$container" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
  service="$("$DOCKER" inspect "$container" --format '{{index .Config.Labels "com.docker.compose.service"}}' 2>/dev/null || true)"
  [ "$running" = true ] || fail "container not running: $container"
  [ "$project" = magicjar ] || fail "unexpected Compose project for $container: $project"
  [ "$service" = "$expected_service" ] || fail "unexpected Compose service for $container: $service"
}

DOCKER="$(find_docker)" || fail "Docker CLI not found"
mkdir -p "$PG_DIR" "$REDIS_DIR"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  fail "another backup is running: $LOCK_DIR"
fi
cleanup() {
  rm -f \
    "$PG_PARTIAL" "${PG_FINAL}.sha256.partial" "${PG_FINAL}.meta.partial" \
    "$REDIS_PARTIAL" "${REDIS_FINAL}.sha256.partial" "${REDIS_FINAL}.meta.partial"
  rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup EXIT HUP INT TERM

case "$PG_RETENTION_DAYS:$REDIS_RETENTION_DAYS:$MIN_FREE_KB" in *[!0-9:]*) fail "invalid numeric setting" ;; esac
FREE_KB="$(df -Pk "$BACKUP_ROOT" | tail -1 | awk '{print $4}')"
[ "$FREE_KB" -ge "$MIN_FREE_KB" ] || fail "insufficient free space: ${FREE_KB}KB < ${MIN_FREE_KB}KB"

assert_compose_container "$PG_CONTAINER" postgres
assert_compose_container "$REDIS_CONTAINER" redis
"$DOCKER" exec "$PG_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 || fail "pg_isready failed"
[ "$("$DOCKER" exec "$REDIS_CONTAINER" redis-cli --raw PING 2>/dev/null)" = PONG ] || fail "redis PING failed"

log "PostgreSQL backup start: $PG_FINAL"
if ! "$DOCKER" exec "$PG_CONTAINER" pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$PG_PARTIAL"; then
  fail "pg_dump failed"
fi
[ -s "$PG_PARTIAL" ] || fail "empty PostgreSQL dump"
"$DOCKER" exec -i "$PG_CONTAINER" pg_restore --list < "$PG_PARTIAL" >/dev/null || fail "pg_restore list validation failed"
PG_SHA="$(sha256_file "$PG_PARTIAL")"
printf '%s  %s\n' "$PG_SHA" "$(basename "$PG_FINAL")" > "${PG_FINAL}.sha256.partial"
printf 'created_at_kst=%s\ncontainer=%s\ndatabase=%s\nsha256=%s\n' "$STAMP" "$PG_CONTAINER" "$DB_NAME" "$PG_SHA" > "${PG_FINAL}.meta.partial"

log "Redis snapshot start: $REDIS_FINAL"
BEFORE_SAVE="$("$DOCKER" exec "$REDIS_CONTAINER" redis-cli --raw LASTSAVE | tr -d '\r')"
"$DOCKER" exec "$REDIS_CONTAINER" redis-cli --raw BGSAVE >/dev/null || fail "Redis BGSAVE start failed"
DEADLINE=$(( $(date '+%s') + 120 ))
while :; do
  INFO="$("$DOCKER" exec "$REDIS_CONTAINER" redis-cli --raw INFO persistence | tr -d '\r')"
  IN_PROGRESS="$(printf '%s\n' "$INFO" | awk -F: '$1=="rdb_bgsave_in_progress"{print $2}')"
  STATUS="$(printf '%s\n' "$INFO" | awk -F: '$1=="rdb_last_bgsave_status"{print $2}')"
  LAST_SAVE="$("$DOCKER" exec "$REDIS_CONTAINER" redis-cli --raw LASTSAVE | tr -d '\r')"
  [ "$IN_PROGRESS" = 0 ] && [ "$STATUS" = ok ] && [ "$LAST_SAVE" -ge "$BEFORE_SAVE" ] && break
  [ "$(date '+%s')" -lt "$DEADLINE" ] || fail "Redis BGSAVE timeout"
  sleep 2
done
"$DOCKER" cp "$REDIS_CONTAINER:/data/dump.rdb" "$REDIS_PARTIAL" >/dev/null || fail "Redis snapshot copy failed"
[ -s "$REDIS_PARTIAL" ] || fail "empty Redis snapshot"
REDIS_SHA="$(sha256_file "$REDIS_PARTIAL")"
printf '%s  %s\n' "$REDIS_SHA" "$(basename "$REDIS_FINAL")" > "${REDIS_FINAL}.sha256.partial"
printf 'created_at_kst=%s\ncontainer=%s\nsha256=%s\n' "$STAMP" "$REDIS_CONTAINER" "$REDIS_SHA" > "${REDIS_FINAL}.meta.partial"

# Publish only after both PostgreSQL and Redis validation have completed.
mv "$PG_PARTIAL" "$PG_FINAL"
mv "${PG_FINAL}.sha256.partial" "${PG_FINAL}.sha256"
mv "${PG_FINAL}.meta.partial" "${PG_FINAL}.meta"
mv "$REDIS_PARTIAL" "$REDIS_FINAL"
mv "${REDIS_FINAL}.sha256.partial" "${REDIS_FINAL}.sha256"
mv "${REDIS_FINAL}.meta.partial" "${REDIS_FINAL}.meta"
chmod 0600 "$PG_FINAL" "${PG_FINAL}.sha256" "${PG_FINAL}.meta" "$REDIS_FINAL" "${REDIS_FINAL}.sha256" "${REDIS_FINAL}.meta"

find "$PG_DIR" -type f -name '*.dump' -mtime "+$PG_RETENTION_DAYS" | while IFS= read -r old; do
  rm -f "$old" "$old.sha256" "$old.meta"
  log "PostgreSQL retention delete: $old"
done
find "$REDIS_DIR" -type f -name '*.rdb' -mtime "+$REDIS_RETENTION_DAYS" | while IFS= read -r old; do
  rm -f "$old" "$old.sha256" "$old.meta"
  log "Redis retention delete: $old"
done

PG_SIZE="$(du -k "$PG_FINAL" | awk '{print $1}')"
REDIS_SIZE="$(du -k "$REDIS_FINAL" | awk '{print $1}')"
log "BACKUP_PASS pg=$(basename "$PG_FINAL") pg_kb=$PG_SIZE pg_sha256=$PG_SHA redis=$(basename "$REDIS_FINAL") redis_kb=$REDIS_SIZE redis_sha256=$REDIS_SHA"
