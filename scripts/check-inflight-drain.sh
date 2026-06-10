#!/usr/bin/env bash
# magicJar — 장중 consumer 재배포 in-flight 주문 drain 가드 (ADR 0115 §2-P2-④ / spec §5-2 · development-rules §3-7).
#
# 왜 필요?
#   in-flight 주문(REQUESTED·ACK·PARTIALLY_FILLED)이 있는 consumer 를 재배포하면 체결통지 유실 risk
#   (MOCK 은 WS 미지원이라 부팅 backfill 만이 복구 경로 — G1 직접 원인: AUTO SELL 발사 직후 재배포로 다운).
#   본 가드는 그 윈도를 줄이는 보강(완전 방지 아님 — 부팅 backfill 과 병행).
#
# 동작:
#   order_request 중 status IN (REQUESTED, ACK, PARTIALLY_FILLED, PENDING_RECONCILE) count 조회.
#   > 0 이면 경고 + exit 1 (배포 중단). 0 이면 exit 0 (배포 진행).
#
# 우회:
#   SKIP_DRAIN_CHECK=1 scripts/build-and-deploy.sh consumer   # 명시 force (drain 무시)
#
# 비활성:
#   MAGICJAR_DEPLOY_DRAIN_INFLIGHT_CHECK_ENABLED=false        # 가드 자체 off (ADR rollback)
#
# DB 좌표는 .env 의 POSTGRES_* / SPRING_DATASOURCE_* 에서 자동 해석 (psql 사용).

set -euo pipefail
cd "$(dirname "$0")/.."

# ── 토글 ──────────────────────────────────────────────────────────────────
if [[ "${MAGICJAR_DEPLOY_DRAIN_INFLIGHT_CHECK_ENABLED:-true}" == "false" ]]; then
  echo "▶ drain 가드 비활성(MAGICJAR_DEPLOY_DRAIN_INFLIGHT_CHECK_ENABLED=false) — skip"
  exit 0
fi
if [[ "${SKIP_DRAIN_CHECK:-0}" == "1" ]]; then
  echo "▶ drain 가드 우회(SKIP_DRAIN_CHECK=1) — in-flight 주문 무시하고 진행"
  exit 0
fi

# ── .env 로드 (DB 좌표) ───────────────────────────────────────────────────
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

PGHOST="${POSTGRES_HOST:-localhost}"
PGPORT="${POSTGRES_PORT:-5432}"
PGDB="${POSTGRES_DB:-magicjar}"
PGUSER="${POSTGRES_USER:-magicjar}"
PGPASSWORD="${POSTGRES_PASSWORD:-magicjar}"
export PGPASSWORD

if ! command -v psql >/dev/null 2>&1; then
  echo "▶ drain 가드 skip — psql 명령을 찾을 수 없습니다(가드 미적용으로 진행)"
  exit 0
fi

SQL="SELECT COUNT(*) FROM order_request WHERE status IN ('REQUESTED','ACK','PARTIALLY_FILLED','PENDING_RECONCILE');"

INFLIGHT="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDB" -tAc "$SQL" 2>/dev/null || echo "ERR")"

if [[ "$INFLIGHT" == "ERR" ]]; then
  echo "▶ drain 가드 — order_request 조회 실패(DB 미가동 추정) — 가드 미적용으로 진행"
  exit 0
fi

if [[ "${INFLIGHT:-0}" -gt 0 ]]; then
  echo "✗ in-flight 주문 ${INFLIGHT}건(REQUESTED·ACK·PARTIALLY_FILLED·PENDING_RECONCILE) — consumer 재배포 중단(체결통지 유실 risk)" >&2
  echo "  drain 대기 후 재시도하거나, 의도적이면 SKIP_DRAIN_CHECK=1 로 우회하세요." >&2
  exit 1
fi

echo "▶ drain 가드 통과 — in-flight 주문 0건"
exit 0
