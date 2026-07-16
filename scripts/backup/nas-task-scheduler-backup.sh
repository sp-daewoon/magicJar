#!/bin/sh
# NAS-native PostgreSQL + Redis backup entrypoint for Synology Task Scheduler.
# No repository checkout or Docker context is required on the NAS.
set -eu
umask 077

PATH="/usr/local/bin:/usr/bin:/bin:/var/packages/ContainerManager/target/usr/bin"
export PATH

BACKUP_ROOT="${MAGICJAR_NAS_BACKUP_ROOT:-/volume1/docker/magicjar/backups}"
SET_ROOT="$BACKUP_ROOT/sets"
RETENTION_DAYS="${MAGICJAR_NAS_BACKUP_RETENTION_DAYS:-7}"
MIN_FREE_KB="${MAGICJAR_NAS_BACKUP_MIN_FREE_KB:-5242880}"
REDIS_BGSAVE_TIMEOUT_SECONDS="${MAGICJAR_REDIS_BGSAVE_TIMEOUT_SECONDS:-120}"
POSTGRES_CONTAINER="${MAGICJAR_POSTGRES_CONTAINER:-magicjar-postgres}"
REDIS_CONTAINER="${MAGICJAR_REDIS_CONTAINER:-magicjar-redis}"
TIMESTAMP="$(TZ=Asia/Seoul date '+%Y%m%d-%H%M%S')"
FINAL_SET="$SET_ROOT/$TIMESTAMP"
PARTIAL_SET="$SET_ROOT/.$TIMESTAMP.partial.$$"
PG_PARTIAL="$PARTIAL_SET/postgres.dump"
REDIS_PARTIAL="$PARTIAL_SET/redis.rdb"
LOCK_DIR="$BACKUP_ROOT/.backup.lock"
LOCK_HELD=0

log() { printf '%s %s\n' "$(TZ=Asia/Seoul date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
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
    /var/packages/Docker/target/usr/bin/docker; do
    [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  command -v docker 2>/dev/null || return 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

boot_id() {
  [ -r /proc/sys/kernel/random/boot_id ] && cat /proc/sys/kernel/random/boot_id || printf '%s\n' unknown
}

process_start_ticks() {
  pid="$1"
  [ -r "/proc/$pid/stat" ] || return 1
  awk '{print $22}' "/proc/$pid/stat"
}

lock_owner_is_alive() {
  [ -r "$LOCK_DIR/owner" ] || return 1
  read -r owner_pid owner_boot owner_ticks < "$LOCK_DIR/owner" || return 1
  [ "$owner_boot" = "$(boot_id)" ] || return 1
  kill -0 "$owner_pid" 2>/dev/null || return 1
  current_ticks="$(process_start_ticks "$owner_pid" 2>/dev/null || true)"
  [ -n "$current_ticks" ] && [ "$current_ticks" = "$owner_ticks" ]
}

acquire_lock() {
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    lock_owner_is_alive && fail "another backup is running (lock=$LOCK_DIR)"
    stale="$LOCK_DIR.stale.$$"
    mv "$LOCK_DIR" "$stale" 2>/dev/null || fail "backup lock changed while reclaiming"
    rm -rf "$stale"
    mkdir "$LOCK_DIR" 2>/dev/null || fail "backup lock acquisition raced"
    log "reclaimed stale backup lock"
  fi
  owner_ticks="$(process_start_ticks "$$" 2>/dev/null || printf '%s' unknown)"
  printf '%s %s %s\n' "$$" "$(boot_id)" "$owner_ticks" > "$LOCK_DIR/owner"
  LOCK_HELD=1
}

cleanup() {
  rm -rf "$PARTIAL_SET"
  if [ "$LOCK_HELD" -eq 1 ]; then
    rm -rf "$LOCK_DIR"
    LOCK_HELD=0
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

validate_container() {
  container="$1"
  expected_service="$2"
  "$DOCKER_BIN" inspect "$container" >/dev/null 2>&1 || fail "$container container not found"
  [ "$("$DOCKER_BIN" inspect "$container" --format '{{.State.Running}}')" = true ] || fail "$container is not running"
  project="$("$DOCKER_BIN" inspect "$container" --format '{{index .Config.Labels "com.docker.compose.project"}}')"
  service="$("$DOCKER_BIN" inspect "$container" --format '{{index .Config.Labels "com.docker.compose.service"}}')"
  [ "$project" = magicjar ] || fail "$container has unexpected Compose project"
  [ "$service" = "$expected_service" ] || fail "$container has unexpected Compose service"
}

DOCKER_BIN="$(find_docker)" || fail "docker CLI not found"
case "$REDIS_BGSAVE_TIMEOUT_SECONDS" in ''|*[!0-9]*) fail "invalid Redis BGSAVE timeout" ;; esac
mkdir -p "$SET_ROOT"
[ ! -e "$FINAL_SET" ] || fail "backup set already exists: $FINAL_SET"
acquire_lock
mkdir "$PARTIAL_SET"

AVAILABLE_KB="$(df -Pk "$BACKUP_ROOT" | awk 'NR==2 {print $4}')"
case "$AVAILABLE_KB" in ''|*[!0-9]*) fail "cannot determine free disk space" ;; esac
[ "$AVAILABLE_KB" -ge "$MIN_FREE_KB" ] || fail "insufficient free disk space (${AVAILABLE_KB}KB < ${MIN_FREE_KB}KB)"

validate_container "$POSTGRES_CONTAINER" postgres
validate_container "$REDIS_CONTAINER" redis
"$DOCKER_BIN" exec "$POSTGRES_CONTAINER" pg_isready -U magicjar -d magicjar >/dev/null 2>&1 || fail "PostgreSQL is not ready"
[ "$("$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw PING)" = PONG ] || fail "Redis is not ready"

log "creating PostgreSQL custom-format dump"
"$DOCKER_BIN" exec "$POSTGRES_CONTAINER" pg_dump -U magicjar -d magicjar -Fc > "$PG_PARTIAL"
[ -s "$PG_PARTIAL" ] || fail "PostgreSQL dump is empty"
"$DOCKER_BIN" exec -i "$POSTGRES_CONTAINER" pg_restore --list < "$PG_PARTIAL" >/dev/null || fail "pg_restore --list validation failed"
PG_SHA="$(sha256_file "$PG_PARTIAL")"
printf '%s  %s\n' "$PG_SHA" postgres.dump > "$PARTIAL_SET/postgres.dump.sha256"
printf 'created_at_kst=%s\ncontainer=%s\ncompose_project=magicjar\ncompose_service=postgres\nsha256=%s\nvalidation=pg_restore_list_pass\n' \
  "$TIMESTAMP" "$POSTGRES_CONTAINER" "$PG_SHA" > "$PARTIAL_SET/postgres.dump.meta"

log "creating fresh Redis RDB snapshot"
BEFORE_SAVE="$("$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw LASTSAVE)"
case "$BEFORE_SAVE" in ''|*[!0-9]*) fail "invalid Redis LASTSAVE before BGSAVE" ;; esac
BGSAVE_RESPONSE="$("$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw BGSAVE)"
[ "$BGSAVE_RESPONSE" = "Background saving started" ] || fail "Redis BGSAVE was not started"
DEADLINE=$(( $(date +%s) + REDIS_BGSAVE_TIMEOUT_SECONDS ))
while :; do
  INFO="$("$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw INFO persistence | tr -d '\r')"
  IN_PROGRESS="$(printf '%s\n' "$INFO" | awk -F: '$1=="rdb_bgsave_in_progress" {print $2}')"
  LAST_STATUS="$(printf '%s\n' "$INFO" | awk -F: '$1=="rdb_last_bgsave_status" {print $2}')"
  AFTER_SAVE="$("$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw LASTSAVE)"
  if [ "$IN_PROGRESS" = 0 ] && [ "$LAST_STATUS" = ok ] && [ "$AFTER_SAVE" -gt "$BEFORE_SAVE" ] 2>/dev/null; then
    break
  fi
  [ "$(date +%s)" -lt "$DEADLINE" ] || fail "Redis BGSAVE did not produce a fresh snapshot within ${REDIS_BGSAVE_TIMEOUT_SECONDS}s"
  sleep 1
done
"$DOCKER_BIN" cp "$REDIS_CONTAINER:/data/dump.rdb" "$REDIS_PARTIAL"
[ -s "$REDIS_PARTIAL" ] || fail "Redis snapshot is empty"
REDIS_SHA="$(sha256_file "$REDIS_PARTIAL")"
printf '%s  %s\n' "$REDIS_SHA" redis.rdb > "$PARTIAL_SET/redis.rdb.sha256"
printf 'created_at_kst=%s\ncontainer=%s\ncompose_project=magicjar\ncompose_service=redis\nsha256=%s\nvalidation=fresh_bgsave_pass\n' \
  "$TIMESTAMP" "$REDIS_CONTAINER" "$REDIS_SHA" > "$PARTIAL_SET/redis.rdb.meta"

chmod 0600 "$PARTIAL_SET"/*
# One same-filesystem directory rename is the commit marker for the verified PG+Redis set.
mv "$PARTIAL_SET" "$FINAL_SET"

find "$SET_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime "+$RETENTION_DAYS" ! -name '.*' -exec rm -rf '{}' ';'
log "BACKUP_PASS set=$FINAL_SET postgres_sha256=$PG_SHA redis_sha256=$REDIS_SHA"
