#!/usr/bin/env bash
# Fail-closed release provenance and single-consumer guards for NAS deployment.

mj_require_release_provenance() {
  local repo_root="${1:?repo root required}"
  local expected="${MAGICJAR_EXPECTED_GIT_SHA:-}"
  local actual status ignored_sources

  if [[ -z "$expected" ]]; then
    echo "✗ MAGICJAR_EXPECTED_GIT_SHA가 비어 있음 — 승인 SHA 명시 필수" >&2
    return 1
  fi
  status="$(git -C "$repo_root" status --porcelain --untracked-files=all)"
  if [[ -n "$status" ]]; then
    echo "✗ worktree가 clean하지 않음 — tracked/untracked 변경 0건만 배포 가능" >&2
    return 1
  fi
  ignored_sources="$(git -C "$repo_root" ls-files --others --ignored --exclude-standard -- \
    ':(glob)**/src/**' ':(glob)frontend/public/**')"
  if [[ -n "$ignored_sources" ]]; then
    echo "✗ ignored source input이 존재함 — Git SHA provenance 보장 불가" >&2
    return 1
  fi
  actual="$(git -C "$repo_root" rev-parse HEAD)"
  if [[ "$actual" != "$expected" ]]; then
    echo "✗ source SHA mismatch: expected=$expected actual=$actual" >&2
    return 1
  fi
  export MAGICJAR_GIT_SHA="$actual"
  export MAGICJAR_IMAGE_TAG="${actual:0:12}"
}

mj_assert_consumer_stopped() {
  local source_context="${1:?source context required}"
  local target_context="${2:?target context required}"
  local names

  if [[ "$source_context" == "$target_context" ]]; then
    echo "✗ source와 target Docker context가 같음: $source_context" >&2
    return 1
  fi
  docker context inspect "$source_context" >/dev/null 2>&1 || {
    echo "✗ source Docker context 부재: $source_context" >&2
    return 1
  }
  docker --context "$source_context" info >/dev/null 2>&1 || {
    echo "✗ source Docker context 연결 실패: $source_context" >&2
    return 1
  }
  if ! names="$(docker --context "$source_context" ps --format '{{.Names}}')"; then
    echo "✗ source consumer 상태 조회 실패: $source_context" >&2
    return 1
  fi
  if grep -q '^magicjar-consumer$' <<<"$names"; then
    echo "✗ source consumer가 실행 중 — NAS consumer 기동 시 split-brain 위험: $source_context" >&2
    return 1
  fi
}

mj_assert_writers_stopped() {
  local context="${1:?context required}"
  local names
  if ! docker --context "$context" info >/dev/null 2>&1; then
    echo "✗ writer 상태를 검증할 Docker context 연결 실패: $context" >&2
    return 1
  fi
  if ! names="$(docker --context "$context" ps --format '{{.Names}}')"; then
    echo "✗ writer 상태 조회 실패: $context" >&2
    return 1
  fi
  if grep -Eq '^magicjar-(api|batch|consumer)$' <<<"$names"; then
    echo "✗ writer가 여전히 실행 중: $context" >&2
    return 1
  fi
}
