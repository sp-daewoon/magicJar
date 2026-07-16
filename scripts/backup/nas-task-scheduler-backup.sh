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
LOCK_FILE="$BACKUP_ROOT/.backup.lock"
LOCK_CANDIDATE="$BACKUP_ROOT/.backup.lock.candidate.$$"
LOCK_TOKEN=""
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
  checksum_line=""
  if command -v sha256sum >/dev/null 2>&1; then
    checksum_line="$(sha256sum "$1")" || return 1
  else
    checksum_line="$(shasum -a 256 "$1")" || return 1
  fi
  checksum="${checksum_line%% *}"
  [ "${#checksum}" -eq 64 ] || return 1
  case "$checksum" in *[!0-9a-fA-F]*) return 1 ;; esac
  printf '%s\n' "$checksum"
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
  [ -r "$LOCK_FILE" ] || return 1
  read -r owner_token owner_pid owner_boot owner_ticks < "$LOCK_FILE" || return 1
  [ -n "$owner_token" ] || return 1
  [ "$owner_boot" = "$(boot_id)" ] || return 1
  kill -0 "$owner_pid" 2>/dev/null || return 1
  [ "$owner_ticks" != unknown ] || return 0
  current_ticks="$(process_start_ticks "$owner_pid" 2>/dev/null || true)"
  [ -n "$current_ticks" ] && [ "$current_ticks" = "$owner_ticks" ]
}

acquire_lock() {
  owner_ticks="$(process_start_ticks "$$" 2>/dev/null || printf '%s' unknown)"
  owner_boot="$(boot_id)"
  LOCK_TOKEN="$owner_boot:$$:$owner_ticks:$(date +%s)"
  printf '%s %s %s %s\n' "$LOCK_TOKEN" "$$" "$owner_boot" "$owner_ticks" > "$LOCK_CANDIDATE"
  while ! ln "$LOCK_CANDIDATE" "$LOCK_FILE" 2>/dev/null; do
    [ -e "$LOCK_FILE" ] || fail "cannot create backup lock"
    lock_owner_is_alive && fail "another backup is running (lock=$LOCK_FILE)"
    stale="$LOCK_FILE.stale.$$"
    mv "$LOCK_FILE" "$stale" 2>/dev/null || continue
    rm -f "$stale"
    log "reclaimed stale backup lock"
  done
  rm -f "$LOCK_CANDIDATE"
  LOCK_HELD=1
}

cleanup() {
  rm -rf "$PARTIAL_SET"
  rm -f "$LOCK_CANDIDATE"
  if [ "$LOCK_HELD" -eq 1 ]; then
    current_token="$(awk 'NR==1 {print $1}' "$LOCK_FILE" 2>/dev/null || true)"
    [ "$current_token" != "$LOCK_TOKEN" ] || rm -f "$LOCK_FILE"
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
acquire_lock
[ ! -e "$FINAL_SET" ] || fail "backup set already exists: $FINAL_SET"
# Exclusive ownership is established, so every pre-existing hidden partial set
# is crash residue rather than a live backup.
find "$SET_ROOT" -mindepth 1 -maxdepth 1 -type d -name '.*.partial.*' -exec rm -rf '{}' ';'
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
PG_SHA="$(sha256_file "$PG_PARTIAL")" || fail "PostgreSQL checksum failed"
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
REDIS_SHA="$(sha256_file "$REDIS_PARTIAL")" || fail "Redis checksum failed"
printf '%s  %s\n' "$REDIS_SHA" redis.rdb > "$PARTIAL_SET/redis.rdb.sha256"
printf 'created_at_kst=%s\ncontainer=%s\ncompose_project=magicjar\ncompose_service=redis\nsha256=%s\nvalidation=fresh_bgsave_pass\n' \
  "$TIMESTAMP" "$REDIS_CONTAINER" "$REDIS_SHA" > "$PARTIAL_SET/redis.rdb.meta"

chmod 0600 "$PARTIAL_SET"/*
# One same-filesystem directory rename is the commit marker for the verified PG+Redis set.
[ ! -e "$FINAL_SET" ] || fail "backup set collision before publish: $FINAL_SET"
mv "$PARTIAL_SET" "$FINAL_SET" || fail "backup set publish failed"
for required in postgres.dump postgres.dump.sha256 postgres.dump.meta redis.rdb redis.rdb.sha256 redis.rdb.meta; do
  [ -f "$FINAL_SET/$required" ] || fail "published backup set missing $required"
done
file_count="$(find "$FINAL_SET" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ')"
dir_count="$(find "$FINAL_SET" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
[ "$file_count" -eq 6 ] && [ "$dir_count" -eq 0 ] || fail "published backup set shape invalid"

find "$SET_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime "+$RETENTION_DAYS" ! -name '.*' -exec rm -rf '{}' ';'
log "BACKUP_PASS set=$FINAL_SET postgres_sha256=$PG_SHA redis_sha256=$REDIS_SHA"
