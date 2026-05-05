#!/usr/bin/env bash
# autonomy.sh — architect 자율 머지 위임 토글 CLI
#
# usage:
#   scripts/autonomy.sh on [seconds]   # 기본 3600s = 1h, 범위 60~7200
#   scripts/autonomy.sh off
#   scripts/autonomy.sh status
#
# 토글 ON 상태에서만 .claude/hooks/autonomy-gate.sh가 `gh pr merge`를 자동 ALLOW.
# 만료 후엔 자동으로 ASK로 fallback.

set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
TOGGLE_FILE="${PROJECT_DIR}/_workspace/.autonomy_until"
DEFAULT_TTL=3600
MIN_TTL=60
MAX_TTL=7200

cmd="${1:-status}"

case "$cmd" in
  on)
    ttl="${2:-$DEFAULT_TTL}"
    if ! [[ "$ttl" =~ ^[0-9]+$ ]] || [ "$ttl" -lt "$MIN_TTL" ] || [ "$ttl" -gt "$MAX_TTL" ]; then
      echo "ERROR: ttl 범위 ${MIN_TTL}~${MAX_TTL}s (받음: $ttl)" >&2
      exit 1
    fi
    mkdir -p "$(dirname "$TOGGLE_FILE")"
    until_epoch=$(($(date +%s) + ttl))
    echo "$until_epoch" > "$TOGGLE_FILE"
    until_str=$(date -r "$until_epoch" '+%Y-%m-%d %H:%M:%S %Z')
    echo "✅ autonomy ON — 만료: $until_str (TTL ${ttl}s)"
    echo "   토글 파일: $TOGGLE_FILE"
    echo "   ⚠️  머지는 architect agent에게만 위임. 메인 세션 직접 호출 금지."
    ;;
  off)
    if [ -f "$TOGGLE_FILE" ]; then
      rm -f "$TOGGLE_FILE"
      echo "✅ autonomy OFF — 토글 파일 삭제"
    else
      echo "ℹ️  이미 OFF (토글 파일 없음)"
    fi
    ;;
  status)
    if [ ! -f "$TOGGLE_FILE" ]; then
      echo "OFF — 토글 파일 없음"
      exit 0
    fi
    until_epoch=$(head -n1 "$TOGGLE_FILE" 2>/dev/null | tr -d '[:space:]')
    if ! [[ "$until_epoch" =~ ^[0-9]+$ ]]; then
      echo "BROKEN — 토글 파일 내용 비정상: $until_epoch"
      exit 2
    fi
    now=$(date +%s)
    if [ "$now" -ge "$until_epoch" ]; then
      echo "EXPIRED — 만료됨 (epoch=$until_epoch)"
      exit 0
    fi
    remain=$((until_epoch - now))
    until_str=$(date -r "$until_epoch" '+%Y-%m-%d %H:%M:%S %Z')
    echo "ON — 만료: $until_str (남은 $((remain / 60))분 ${remain}s)"
    ;;
  *)
    echo "usage: $0 {on [seconds] | off | status}" >&2
    exit 64
    ;;
esac
