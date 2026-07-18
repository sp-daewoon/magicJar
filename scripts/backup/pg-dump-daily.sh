#!/usr/bin/env bash
#
# pg-dump-daily.sh — magicJar PostgreSQL daily full backup
#
# ADR 0096 §2-1 정합 — 매일 03:00 KST + 7일 retention
# 사용자 cron 등록 가이드: docs/operations/backup-setup.md
#
# 사용:
#   ./scripts/backup/pg-dump-daily.sh
#
# cron 등록 예시 (사용자 macOS crontab -e):
#   0 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/pg-dump-daily.sh >> _workspace/backups/postgres/cron.log 2>&1
#
# 환경:
#   - docker compose postgres 컨테이너 `magicjar-postgres` running 의무
#   - 호스트 디스크 여유 공간 ≥ 100MB (baseline ~3MB × 7일 = 21MB)
#   - (선택) MAGICJAR_BACKUP_SLACK_WEBHOOK 또는 SLACK_WEBHOOK_URL — 실패 시 Slack 알림
#     (감사 §7 백업 — silent 실패 방지. 미설정이면 조용히 skip, 백업 동작 자체는 불변)

set -Eeuo pipefail

# ────────────────────────────────────────────────────────────
# 설정 (ADR 0096 §2-1·§2-4 LOCK)
# ────────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_DIR="${REPO_ROOT}/_workspace/backups/postgres/daily"
CONTAINER_NAME="${MAGICJAR_PG_CONTAINER:-magicjar-postgres}"
DB_USER="${MAGICJAR_PG_USER:-magicjar}"
DB_NAME="${MAGICJAR_PG_DB:-magicjar}"
RETENTION_DAYS="${MAGICJAR_PG_BACKUP_RETENTION_DAYS:-7}"

# KST timestamp (ADR 0096 §2-1)
TODAY="$(date '+%Y-%m-%d')"
BACKUP_FILE="${BACKUP_DIR}/${TODAY}.dump"

# ────────────────────────────────────────────────────────────
# 진단 — postgres 컨테이너 running 검증
# ────────────────────────────────────────────────────────────
log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S KST')] [pg-dump-daily] $*"
}

# 실패 Slack 알림 (감사 §7 백업) — webhook env 미설정 시 조용히 skip (백업 로직 영향 0).
# curl 실패도 swallow — 알림 실패가 exit code 를 오염시키지 않음.
notify_slack_failure() {
  local reason="$1"
  local webhook="${MAGICJAR_BACKUP_SLACK_WEBHOOK:-${SLACK_WEBHOOK_URL:-}}"
  [[ -z "$webhook" ]] && return 0
  command -v curl >/dev/null 2>&1 || return 0
  curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
    -d "{\"text\":\":rotating_light: [pg-dump-daily] PostgreSQL 백업 실패 — ${reason} (host: $(hostname), $(date '+%Y-%m-%d %H:%M KST'))\"}" \
    "$webhook" >/dev/null 2>&1 || true
}

err() {
  log "ERROR: $*" >&2
  notify_slack_failure "$*"
}

if ! command -v docker >/dev/null 2>&1; then
  err "docker 명령 부재 — Docker Desktop 설치 + 실행 필요"
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  err "${CONTAINER_NAME} 컨테이너 running 0 — 'docker compose -f docker/docker-compose.infra.yml up -d' 또는 'scripts/build-and-deploy.sh' 실행 후 재시도"
  exit 1
fi

if ! docker exec "${CONTAINER_NAME}" pg_isready -U "${DB_USER}" -d "${DB_NAME}" >/dev/null 2>&1; then
  err "${CONTAINER_NAME} pg_isready FAIL — DB 결함 의심 (R-4 runbook 진입)"
  exit 1
fi

# ────────────────────────────────────────────────────────────
# 산출물 디렉토리 보장 (ADR 0096 §2-4)
# ────────────────────────────────────────────────────────────
mkdir -p "${BACKUP_DIR}"

# ────────────────────────────────────────────────────────────
# pg_dump custom format — pg_restore --clean --create 호환
# ────────────────────────────────────────────────────────────
log "백업 시작: ${BACKUP_FILE}"
log "  컨테이너: ${CONTAINER_NAME}, DB: ${DB_NAME}, User: ${DB_USER}"

if docker exec "${CONTAINER_NAME}" pg_dump -U "${DB_USER}" -Fc "${DB_NAME}" > "${BACKUP_FILE}"; then
  SIZE=$(du -h "${BACKUP_FILE}" | cut -f1)
  log "백업 완료: ${BACKUP_FILE} (${SIZE})"
else
  err "pg_dump FAIL — partial 파일 삭제"
  rm -f "${BACKUP_FILE}"
  exit 1
fi

# ────────────────────────────────────────────────────────────
# retention rotation — RETENTION_DAYS 초과 파일 삭제 (ADR 0096 §2-1)
# ────────────────────────────────────────────────────────────
log "retention rotation: ${RETENTION_DAYS}일 초과 파일 정리"
DELETED=0
while IFS= read -r OLD_FILE; do
  rm -f "${OLD_FILE}"
  log "  삭제: ${OLD_FILE}"
  DELETED=$((DELETED + 1))
done < <(find "${BACKUP_DIR}" -name "*.dump" -type f -mtime "+${RETENTION_DAYS}")

log "retention 완료: ${DELETED}건 삭제"

# ────────────────────────────────────────────────────────────
# 결과 요약
# ────────────────────────────────────────────────────────────
log "현재 backup 목록:"
ls -lh "${BACKUP_DIR}"/*.dump 2>/dev/null | tail -10 | while IFS= read -r LINE; do
  log "  ${LINE}"
done

log "exit 0 — 정상 완료"
exit 0
