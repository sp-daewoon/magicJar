#!/usr/bin/env bash
# autonomy-gate.sh — `gh pr merge` PreToolUse hook
#
# 토글 파일(_workspace/.autonomy_until)에 미만료 epoch가 들어 있으면
# {"permissionDecision":"allow"}를 출력해 ASK 프롬프트를 우회.
# 그 외 모든 경우는 exit 0 (출력 없음) → 기본 정책(ASK)으로 fallback.
#
# 운영 규약: 토글 ON 상태에서 머지 호출은 architect agent만 수행.
# 메인 세션은 절대 직접 호출 금지 (docs/architecture/development-rules.md §6).

set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
TOGGLE_FILE="${PROJECT_DIR}/_workspace/.autonomy_until"

# 토글 없음 → 기본 정책
[ -f "$TOGGLE_FILE" ] || exit 0

UNTIL=$(head -n1 "$TOGGLE_FILE" 2>/dev/null | tr -d '[:space:]')
[[ "$UNTIL" =~ ^[0-9]+$ ]] || exit 0

NOW=$(date +%s)
[ "$NOW" -lt "$UNTIL" ] || exit 0

REMAIN=$((UNTIL - NOW))
REMAIN_MIN=$((REMAIN / 60))

cat <<EOF
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "allow",
    "permissionDecisionReason": "architect 자율 위임 활성 (남은 ${REMAIN_MIN}분, 만료 epoch=${UNTIL})"
  }
}
EOF
