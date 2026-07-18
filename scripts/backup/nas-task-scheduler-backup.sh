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
LOCK_TTL_MS="${MAGICJAR_NAS_BACKUP_LOCK_TTL_MS:-120000}"
LOCK_RENEW_SECONDS="${MAGICJAR_NAS_BACKUP_LOCK_RENEW_SECONDS:-15}"
LOCK_KEY="magicjar:nas-backup:lock"
POSTGRES_CONTAINER="${MAGICJAR_POSTGRES_CONTAINER:-magicjar-postgres}"
REDIS_CONTAINER="${MAGICJAR_REDIS_CONTAINER:-magicjar-redis}"
MV_BIN="${MAGICJAR_MV_BIN:-mv}"
TIMESTAMP="$(TZ=Asia/Seoul date '+%Y%m%d-%H%M%S')"
SET_ROOT_CANONICAL=""
FINAL_SET=""
PARTIAL_SET=""
PG_PARTIAL=""
REDIS_PARTIAL=""
LOCK_TOKEN=""
LOCK_HELD=0
LOCK_RENEW_PID=""
PUBLISHED_UNCOMMITTED=0

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

file_mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

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

redis_lock_eval() {
  script="$1"
  "$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw EVAL "$script" 1 "$LOCK_KEY" "$LOCK_TOKEN" "$LOCK_TTL_MS"
}

assert_lock_owned() {
  result="$(redis_lock_eval 'if redis.call("get",KEYS[1])==ARGV[1] then return 1 else return 0 end')" || return 1
  [ "$result" = 1 ]
}

random_lock_token() {
  if [ -r /proc/sys/kernel/random/uuid ]; then
    token="$(tr -d '-\n' < /proc/sys/kernel/random/uuid)" || return 1
  elif command -v od >/dev/null 2>&1; then
    token="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')" || return 1
  else
    return 1
  fi
  [ "${#token}" -eq 32 ] || return 1
  case "$token" in *[!0-9a-fA-F]*) return 1 ;; esac
  printf '%s\n' "$token"
}

pin_backup_sets_directory() {
  [ ! -L "$BACKUP_ROOT" ] && [ -d "$BACKUP_ROOT" ] || fail "unsafe backup root directory"
  [ ! -L "$SET_ROOT" ] && [ -d "$SET_ROOT" ] || fail "unsafe backup sets directory"
  backup_root_canonical="$(readlink -f "$BACKUP_ROOT")" || fail "cannot resolve backup root"
  SET_ROOT_CANONICAL="$(readlink -f "$SET_ROOT")" || fail "cannot resolve backup sets directory"
  [ "$SET_ROOT_CANONICAL" = "$backup_root_canonical/sets" ] || fail "backup sets path escaped backup root"
  cd "$SET_ROOT" || fail "cannot pin backup sets directory"
  [ "$(readlink -f .)" = "$SET_ROOT_CANONICAL" ] || fail "backup sets path changed before pin"
  FINAL_SET="./$TIMESTAMP"
  PARTIAL_SET="./.$TIMESTAMP.partial.$$"
  PG_PARTIAL="$PARTIAL_SET/postgres.dump"
  REDIS_PARTIAL="$PARTIAL_SET/redis.rdb"
}

renew_lock_loop() {
  parent_pid="$1"
  renew_sleep_pid=""
  trap '[ -z "$renew_sleep_pid" ] || kill "$renew_sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
  while kill -0 "$parent_pid" 2>/dev/null; do
    sleep "$LOCK_RENEW_SECONDS" &
    renew_sleep_pid=$!
    wait "$renew_sleep_pid" || exit 0
    renew_sleep_pid=""
    kill -0 "$parent_pid" 2>/dev/null || exit 0
    result="$(redis_lock_eval 'if redis.call("get",KEYS[1])==ARGV[1] then return redis.call("pexpire",KEYS[1],ARGV[2]) else return 0 end' 2>/dev/null || printf '0')"
    if [ "$result" != 1 ]; then
      kill -TERM "$parent_pid" 2>/dev/null || true
      exit 1
    fi
  done
}

acquire_lock() {
  LOCK_TOKEN="$(random_lock_token)" || fail "cannot generate backup lease token"
  # Enable token-owned cleanup before SET so a signal immediately after Redis
  # commits the lease cannot strand it. Compare-delete cannot remove another token.
  LOCK_HELD=1
  result="$("$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw SET "$LOCK_KEY" "$LOCK_TOKEN" NX PX "$LOCK_TTL_MS")" || fail "cannot acquire Redis backup lease"
  [ "$result" = OK ] || fail "another backup is running (lease=$LOCK_KEY)"
  renew_lock_loop "$$" &
  LOCK_RENEW_PID=$!
}

cleanup() {
  [ -z "$PARTIAL_SET" ] || rm -rf "$PARTIAL_SET"
  if [ "$PUBLISHED_UNCOMMITTED" -eq 1 ]; then
    publish_marker="$FINAL_SET/.publish-owner"
    if [ ! -L "$publish_marker" ] && [ -f "$publish_marker" ] &&
       [ "$(cat "$publish_marker" 2>/dev/null || true)" = "$LOCK_TOKEN" ]; then
      rm -rf "$FINAL_SET"
    fi
    PUBLISHED_UNCOMMITTED=0
  fi
  if [ -n "$LOCK_RENEW_PID" ]; then
    kill "$LOCK_RENEW_PID" 2>/dev/null || true
    wait "$LOCK_RENEW_PID" 2>/dev/null || true
    LOCK_RENEW_PID=""
  fi
  if [ "$LOCK_HELD" -eq 1 ]; then
    redis_lock_eval 'if redis.call("get",KEYS[1])==ARGV[1] then return redis.call("del",KEYS[1]) else return 0 end' >/dev/null 2>&1 || true
    LOCK_HELD=0
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

DOCKER_BIN="$(find_docker)" || fail "docker CLI not found"
if [ "${MV_BIN#*/}" != "$MV_BIN" ]; then
  [ -x "$MV_BIN" ] || fail "configured mv executable not found"
else
  command -v "$MV_BIN" >/dev/null 2>&1 || fail "mv executable not found"
fi
case "$REDIS_BGSAVE_TIMEOUT_SECONDS" in ''|*[!0-9]*) fail "invalid Redis BGSAVE timeout" ;; esac
case "$RETENTION_DAYS" in ''|*[!0-9]*) fail "invalid backup retention days" ;; esac
case "$MIN_FREE_KB" in ''|*[!0-9]*) fail "invalid minimum free disk space" ;; esac
case "$LOCK_TTL_MS" in ''|*[!0-9]*) fail "invalid backup lock TTL" ;; esac
case "$LOCK_RENEW_SECONDS" in ''|*[!0-9]*) fail "invalid backup lock renew interval" ;; esac
[ "$RETENTION_DAYS" -ge 1 ] || fail "backup retention must be at least one day"
[ "$MIN_FREE_KB" -ge 1 ] || fail "minimum free disk space must be positive"
[ "$LOCK_TTL_MS" -ge 30000 ] || fail "backup lock TTL must be at least 30000ms"
[ "$LOCK_RENEW_SECONDS" -ge 1 ] || fail "backup lock renew interval must be positive"
[ $((LOCK_RENEW_SECONDS * 3000)) -lt "$LOCK_TTL_MS" ] || fail "backup lock renew interval is too close to TTL"

pin_backup_sets_directory
AVAILABLE_KB="$(df -Pk . | awk 'NR==2 {print $4}')"
case "$AVAILABLE_KB" in ''|*[!0-9]*) fail "cannot determine free disk space" ;; esac
[ "$AVAILABLE_KB" -ge "$MIN_FREE_KB" ] || fail "insufficient free disk space (${AVAILABLE_KB}KB < ${MIN_FREE_KB}KB)"

validate_container "$POSTGRES_CONTAINER" postgres
validate_container "$REDIS_CONTAINER" redis
"$DOCKER_BIN" exec "$POSTGRES_CONTAINER" pg_isready -U magicjar -d magicjar >/dev/null 2>&1 || fail "PostgreSQL is not ready"
[ "$("$DOCKER_BIN" exec "$REDIS_CONTAINER" redis-cli --raw PING)" = PONG ] || fail "Redis is not ready"
acquire_lock
assert_lock_owned || fail "backup lease was lost before stale partial cleanup"
[ ! -e "$FINAL_SET" ] || fail "backup set already exists: $FINAL_SET"
# Never remove a recent partial set from a potentially active owner. Only
# old crash residue is eligible after this process owns the Redis lease.
find . -mindepth 1 -maxdepth 1 -type d -name '.*.partial.*' -mtime +1 -exec rm -rf '{}' ';'
mkdir "$PARTIAL_SET"

log "creating PostgreSQL custom-format dump"
"$DOCKER_BIN" exec "$POSTGRES_CONTAINER" pg_dump -U magicjar -d magicjar -Fc > "$PG_PARTIAL"
[ -s "$PG_PARTIAL" ] || fail "PostgreSQL dump is empty"
"$DOCKER_BIN" exec -i "$POSTGRES_CONTAINER" pg_restore --list < "$PG_PARTIAL" >/dev/null || fail "pg_restore --list validation failed"
PG_SHA="$(sha256_file "$PG_PARTIAL")" || fail "PostgreSQL checksum failed"
printf '%s  %s\n' "$PG_SHA" postgres.dump > "$PARTIAL_SET/postgres.dump.sha256"
printf 'created_at_kst=%s\ncontainer=%s\ncompose_project=magicjar\ncompose_service=postgres\nsha256=%s\nvalidation=pg_restore_list_pass\n' \
  "$TIMESTAMP" "$POSTGRES_CONTAINER" "$PG_SHA" > "$PARTIAL_SET/postgres.dump.meta"
assert_lock_owned || fail "backup lease was lost after PostgreSQL capture"

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
"$DOCKER_BIN" exec "$REDIS_CONTAINER" cat /data/dump.rdb > "$REDIS_PARTIAL"
[ -s "$REDIS_PARTIAL" ] || fail "Redis snapshot is empty"
REDIS_SHA="$(sha256_file "$REDIS_PARTIAL")" || fail "Redis checksum failed"
printf '%s  %s\n' "$REDIS_SHA" redis.rdb > "$PARTIAL_SET/redis.rdb.sha256"
printf 'created_at_kst=%s\ncontainer=%s\ncompose_project=magicjar\ncompose_service=redis\nsha256=%s\nvalidation=fresh_bgsave_pass\n' \
  "$TIMESTAMP" "$REDIS_CONTAINER" "$REDIS_SHA" > "$PARTIAL_SET/redis.rdb.meta"

for required in postgres.dump postgres.dump.sha256 postgres.dump.meta redis.rdb redis.rdb.sha256 redis.rdb.meta; do
  [ ! -L "$PARTIAL_SET/$required" ] && [ -f "$PARTIAL_SET/$required" ] || fail "backup set missing non-symlink regular file $required"
  [ "$(file_mode "$PARTIAL_SET/$required")" = 600 ] || fail "backup set file mode is not 0600: $required"
done
entry_count="$(find "$PARTIAL_SET" -mindepth 1 -maxdepth 1 -print | wc -l | tr -d ' ')"
[ "$entry_count" -eq 6 ] || fail "published backup set shape invalid before publish"
assert_lock_owned || fail "backup lease was lost before publish"
[ ! -e "$FINAL_SET" ] || fail "backup set collision before publish: $FINAL_SET"
printf '%s\n' "$LOCK_TOKEN" > "$PARTIAL_SET/.publish-owner"
PUBLISHED_UNCOMMITTED=1
"$MV_BIN" "$PARTIAL_SET" "$FINAL_SET" || fail "backup set publish failed"
assert_lock_owned || fail "backup lease was lost immediately after publish"
publish_marker="$FINAL_SET/.publish-owner"
[ ! -L "$publish_marker" ] && [ -f "$publish_marker" ] || fail "backup publish ownership marker missing"
[ "$(cat "$publish_marker")" = "$LOCK_TOKEN" ] || fail "backup publish ownership marker mismatch"
entry_count="$(find "$FINAL_SET" -mindepth 1 -maxdepth 1 -print | wc -l | tr -d ' ')"
[ "$entry_count" -eq 7 ] || fail "uncommitted backup set shape invalid after publish"
for required in postgres.dump postgres.dump.sha256 postgres.dump.meta redis.rdb redis.rdb.sha256 redis.rdb.meta; do
  [ ! -L "$FINAL_SET/$required" ] && [ -f "$FINAL_SET/$required" ] || fail "unsafe published backup set entry: $required"
  [ "$(file_mode "$FINAL_SET/$required")" = 600 ] || fail "published backup set file mode is not 0600: $required"
done
rm -f "$publish_marker" || fail "backup commit marker removal failed"
entry_count="$(find "$FINAL_SET" -mindepth 1 -maxdepth 1 -print | wc -l | tr -d ' ')"
[ "$entry_count" -eq 6 ] || fail "published backup set shape invalid after commit"
PUBLISHED_UNCOMMITTED=0

assert_lock_owned || fail "backup lease was lost before retention"
find . -mindepth 1 -maxdepth 1 -type d -mtime "+$RETENTION_DAYS" ! -name '.*' -exec rm -rf '{}' ';'
log "BACKUP_PASS set=$SET_ROOT_CANONICAL/${FINAL_SET#./} postgres_sha256=$PG_SHA redis_sha256=$REDIS_SHA"
