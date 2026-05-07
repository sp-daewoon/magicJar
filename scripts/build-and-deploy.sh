#!/usr/bin/env bash
# magicJar — 호스트 빌드 + docker compose 재기동 한 줄 헬퍼.
#
# 왜 필요?
#   api/batch/consumer Dockerfile은 단순 `COPY build/libs/<m>-*.jar /app/app.jar`로
#   호스트 산출물을 그대로 가져온다. `docker compose build`만으로는 gradle이 안 돌아
#   옛 jar가 컨테이너에 들어간다 (운영 함정 가드 — smoke.sh staleness check와 짝).
#
# 사용:
#   scripts/build-and-deploy.sh                # 전체 (gradle + docker build + up -d)
#   scripts/build-and-deploy.sh api frontend   # 일부 서비스만
#   scripts/build-and-deploy.sh --gradle-only  # gradle만 (jar만 갱신)
#   scripts/build-and-deploy.sh --no-gradle    # docker compose만 (이미 jar 최신일 때)
#
# 기본 동작: docker-compose.yml + docker-compose.dev.yml override + --env-file .env.
# default profile = dev,real-kis (memory feedback_no_mock_data.md 정합).
# mock profile은 jvm test fixture 전용 — 운영/사용자 흐름에서 사용 안 함.

set -euo pipefail
cd "$(dirname "$0")/.."

ALL_JVM=(api batch consumer)
# 빌드+up 대상. dozzle·kafka-ui는 image-only(외부)라 build는 noop, up만 동작.
ALL_SERVICES=(api-app batch-app consumer-app frontend dozzle kafka-ui)

GRADLE=1
DOCKER=1
SERVICES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gradle-only)  DOCKER=0; shift ;;
    --no-gradle)    GRADLE=0; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) SERVICES+=("$1"); shift ;;
  esac
done

# Compose 명령 합성: --env-file을 **절대 경로**로 명시 (compose의 working dir이 docker/로
# 잡혀 `.env`를 못 찾는 함정 회피). --project-directory는 쓰면 build.context: ../batch
# 해석이 root 기준으로 바뀌어 `/Users/<user>/batch`로 잘못 잡힘 (사용자 보고 2026-05-05).
COMPOSE_ARGS=(--env-file "$PWD/.env" -f docker/docker-compose.yml -f docker/docker-compose.dev.yml)

# 서비스 미지정 시 전체
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  SERVICES=("${ALL_SERVICES[@]}")
fi

# 1) gradle bootJar — JVM 모듈만 (api-app→api, batch-app→batch, consumer-app→consumer)
if [[ $GRADLE -eq 1 ]]; then
  jvm_modules=()
  for s in "${SERVICES[@]}"; do
    case "$s" in
      api-app)      jvm_modules+=(:api:bootJar) ;;
      batch-app)    jvm_modules+=(:batch:bootJar) ;;
      consumer-app) jvm_modules+=(:consumer:bootJar) ;;
    esac
  done
  if [[ ${#jvm_modules[@]} -gt 0 ]]; then
    echo "▶ gradle: ${jvm_modules[*]}"
    ./gradlew "${jvm_modules[@]}"
  else
    echo "▶ gradle: (JVM 서비스 없음 — skip)"
  fi
fi

# 2) docker compose build + up -d
if [[ $DOCKER -eq 1 ]]; then
  echo "▶ profile: dev,real-kis (default — mock 부팅 path 폐기)"

  echo "▶ docker compose build: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" build "${SERVICES[@]}"

  echo "▶ docker compose up -d: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" up -d "${SERVICES[@]}"

  echo "▶ healthy 대기 (~60s)…"
  sleep 30
  docker compose "${COMPOSE_ARGS[@]}" ps --format "{{.Service}}\t{{.Status}}"
fi

echo "✓ done"
