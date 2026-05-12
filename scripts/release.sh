#!/usr/bin/env bash
set -euo pipefail

# magicJar release.sh — GitHub Actions 없이 tag → GitHub Release 자동 승격
#
# 사용 메모리 정합 (feedback_no_github_actions.md):
#   GHA workflow 폐기 → 로컬 명령으로 release 자동화
#
# 절차:
#   1. tag push 확인 (이미 push됨 가정)
#   2. release/vX.Y.Z brunch 확인 (ADR 0031 immutable baseline)
#   3. CHANGELOG.md에서 해당 버전 row 추출 → release notes
#   4. gh release create vX.Y.Z --notes "..." (결제 무관)
#
# 사용법:
#   scripts/release.sh v3.5.0
#   scripts/release.sh --backfill         # 누락된 tag 전부 backfill
#   scripts/release.sh --backfill --dry-run

usage() {
  cat <<EOF
Usage:
  scripts/release.sh <version>       # 단일 버전 GitHub Release 발행
  scripts/release.sh --backfill      # 누락된 tag 전부 backfill
  scripts/release.sh --help

Example:
  scripts/release.sh v3.5.0
  scripts/release.sh --backfill --dry-run
EOF
}

extract_changelog_row() {
  local version="$1"
  # CHANGELOG.md에서 "release: vX.Y.Z" 포함 row 추출
  # 형식: | <date> | **release: vX.Y.Z — ...** | release | ... |
  grep -E "release: ${version}\b" CHANGELOG.md | head -1 | sed 's/^|//; s/|$//' || echo ""
}

release_one() {
  local version="$1"
  local dry="${2:-false}"

  if [[ ! "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "✗ invalid version: $version (expected vX.Y.Z)"
    return 1
  fi

  # tag 존재 확인
  if ! git tag -l "$version" | grep -q "^${version}$"; then
    echo "✗ tag $version not found locally"
    return 1
  fi

  # 이미 GitHub Release에 있는지 확인
  if gh release view "$version" >/dev/null 2>&1; then
    echo "✓ ${version} GitHub Release already published (skip)"
    return 0
  fi

  # CHANGELOG row 추출
  local row
  row=$(extract_changelog_row "$version")
  local notes
  if [[ -n "$row" ]]; then
    notes="## ${version} Release Notes

${row}

---

자세한 변경 이력은 [CHANGELOG.md](./CHANGELOG.md) 참조."
  else
    notes="## ${version} Release Notes

CHANGELOG row 미발견 — [git log ${version}](../commits/${version}) 참조."
  fi

  if [[ "$dry" == "true" ]]; then
    echo "--- ${version} (dry-run) ---"
    echo "$notes"
    echo "---"
    return 0
  fi

  # gh release create
  echo "▶ ${version} GitHub Release 발행 …"
  echo "$notes" | gh release create "$version" --title "$version" --notes-file - 2>&1 | head -3
  echo "✓ ${version} 발행 완료"
}

# argv 파싱
MODE="single"
DRY_RUN="false"
VERSION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backfill) MODE="backfill"; shift ;;
    --dry-run)  DRY_RUN="true"; shift ;;
    --help|-h)  usage; exit 0 ;;
    v*.*.*)     VERSION="$1"; shift ;;
    *)          echo "unknown arg: $1"; usage; exit 1 ;;
  esac
done

if [[ "$MODE" == "single" ]]; then
  if [[ -z "$VERSION" ]]; then
    usage
    exit 1
  fi
  release_one "$VERSION" "$DRY_RUN"
else
  # backfill — 모든 tag 순회
  for tag in $(git tag -l 'v*' | sort -V); do
    release_one "$tag" "$DRY_RUN"
  done
fi
