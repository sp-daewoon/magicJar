#!/bin/sh
# magicJar pyworker entrypoint.
# Dockerfile은 ENTRYPOINT를 직접 uvicorn으로 잡기 때문에 이 스크립트는 옵션이지만,
# 향후 마이그레이션·환경 검증 hook을 위해 남겨둔다.

set -e

if [ -n "${PRE_RUN_CMD:-}" ]; then
  echo "[entrypoint] PRE_RUN_CMD: ${PRE_RUN_CMD}"
  sh -c "${PRE_RUN_CMD}"
fi

exec "$@"
