#!/usr/bin/env bash
# magicJar worktree config 1회 적용 (헌법 §1-A-extended 정합)
#
# 목적: extensions.worktreeConfig=true 활성화 — worktree 간 `.git/config` 공유 차단
#       → 한 worktree의 `git config --local` 설정이 main 또는 다른 worktree에 전파되지 않도록 격리
#
# root cause (안정성 Cycle 3 — 2026-05-19, 8 sub-agent system audit evidence):
#   - 기본 상태(extensions.worktreeConfig=false)에서 worktree는 main `.git/config`를 공유
#   - 한 worktree에서 `git config user.email` 호출 시 → main `.git/config` 흡수
#   - 결과: 다른 worktree·sub-agent에 휘발성 전파 → author 결함 PR 10+건 누적
#   - 헌법 §1-A 호출만으로 차단 불가 — 본 옵션 활성화가 본질
#
# 검증: `git config extensions.worktreeConfig` = true

set -euo pipefail

# Repo root 확인 (worktree 안에서 호출되어도 main 적용 — `git rev-parse --git-common-dir`로 main .git dir 추적)
COMMON_GIT_DIR="$(git rev-parse --git-common-dir)"
if [ -z "$COMMON_GIT_DIR" ]; then
  echo "✗ git repository가 아닙니다 (git rev-parse --git-common-dir 실패)" >&2
  exit 1
fi

# extensions.worktreeConfig는 main `.git/config` 에 기록되어야 함 (worktree-local 아님)
# `git config` default는 worktree에 있으면 worktree config 또는 main config 적용 — `--file` 명시로 main 강제
MAIN_CONFIG_FILE="${COMMON_GIT_DIR}/config"

# 기존 값 확인
CURRENT_VAL="$(git config --file "$MAIN_CONFIG_FILE" --get extensions.worktreeConfig 2>/dev/null || echo "")"
if [ "$CURRENT_VAL" = "true" ]; then
  echo "✓ extensions.worktreeConfig=true 이미 적용됨 ($MAIN_CONFIG_FILE)"
  exit 0
fi

# 적용
git config --file "$MAIN_CONFIG_FILE" extensions.worktreeConfig true
echo "✓ extensions.worktreeConfig=true 적용 (헌법 §1-A-extended 정합)"
echo "  대상 파일: $MAIN_CONFIG_FILE"

# 검증
VAL="$(git config --file "$MAIN_CONFIG_FILE" --get extensions.worktreeConfig)"
if [ "$VAL" = "true" ]; then
  echo "✓ 검증 GREEN — worktree 간 git config 격리 활성"
  echo ""
  echo "다음 단계 (각 worktree에서 sub-agent 진입 시):"
  echo "  git config user.email \"bot+<agent>@magicjar.local\""
  echo "  git config user.name \"magicjar-<agent>\""
  echo "  git config --worktree user.email   # worktree-local 확인 가능 (옵션 활성 후)"
else
  echo "✗ 검증 FAIL: extensions.worktreeConfig=$VAL" >&2
  exit 1
fi
