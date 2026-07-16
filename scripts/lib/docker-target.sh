#!/usr/bin/env bash
# Shared Docker target selection. Source this file from Bash scripts.
# MAGICJAR_DOCKER_CONTEXT is intentionally opt-in: an empty value keeps the
# caller on its current/default Docker context, while NAS entrypoints always set it.

MAGICJAR_DOCKER=(docker)
if [[ -n "${MAGICJAR_DOCKER_CONTEXT:-}" ]]; then
  MAGICJAR_DOCKER+=(--context "${MAGICJAR_DOCKER_CONTEXT}")
fi

mj_docker() {
  "${MAGICJAR_DOCKER[@]}" "$@"
}

mj_docker_endpoint() {
  local context="${MAGICJAR_DOCKER_CONTEXT:-}"
  if [[ -z "$context" ]]; then
    docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null
  else
    docker context inspect "$context" --format '{{.Endpoints.docker.Host}}' 2>/dev/null
  fi
}

mj_require_remote_docker() {
  local context="${MAGICJAR_DOCKER_CONTEXT:-}"
  local endpoint

  if [[ -z "$context" ]]; then
    echo "✗ MAGICJAR_DOCKER_CONTEXT가 비어 있음 — NAS 작업은 명시적 remote context가 필수" >&2
    return 1
  fi
  endpoint="$(mj_docker_endpoint || true)"
  case "$endpoint" in
    ssh://*|tcp://*) ;;
    *)
      echo "✗ context '$context' endpoint가 remote가 아님: ${endpoint:-조회 실패}" >&2
      return 1
      ;;
  esac
  if ! mj_docker info >/dev/null 2>&1; then
    echo "✗ remote Docker 연결 실패: context=$context endpoint=$endpoint" >&2
    return 1
  fi
  echo "▶ Docker target: context=$context endpoint=$endpoint"
}

mj_assert_expected_endpoint() {
  [[ -n "${MAGICJAR_DOCKER_CONTEXT:-}" ]] || return 0
  local expected="${MAGICJAR_EXPECTED_DOCKER_ENDPOINT:-}"
  if [[ -z "$expected" && "$MAGICJAR_DOCKER_CONTEXT" == "nas" ]]; then
    expected="ssh://magicjar-nas-docker"
  fi
  if [[ -z "$expected" ]]; then
    echo "✗ remote Docker endpoint attestation 값이 없음" >&2
    return 1
  fi
  local actual
  actual="$(mj_docker_endpoint)"
  if [[ "$actual" != "$expected" ]]; then
    echo "✗ Docker endpoint mismatch: expected=$expected actual=$actual" >&2
    return 1
  fi
}
