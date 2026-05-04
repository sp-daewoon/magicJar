#!/usr/bin/env bash
# magicJar — 호스트 빌드 + docker compose 재기동 한 줄 헬퍼.
#
# 왜 필요?
#   api/batch/consumer Dockerfile은 단순 `COPY build/libs/<m>-*.jar /app/app.jar`로
#   호스트 산출물을 그대로 가져온다. `docker compose build`만으로는 gradle이 안 돌아
#   옛 jar가 컨테이너에 들어간다 (Phase F R2 운영 함정 — QA ISSUE-002 P1).
#
# 사용:
#   scripts/build-and-deploy.sh                # 전체 (gradle + docker build + up -d)
#   scripts/build-and-deploy.sh api frontend   # 일부 서비스만
#   scripts/build-and-deploy.sh --gradle-only  # gradle만 (jar만 갱신)
#   scripts/build-and-deploy.sh --no-gradle    # docker compose만 (이미 jar 최신일 때)
#   scripts/build-and-deploy.sh --mock         # dev override 끄고 mock profile 부팅 (테스트 전용)
#
# 기본 동작: docker-compose.yml + docker-compose.dev.yml override + --env-file .env.
# dev.yml override가 빠지면 SPRING_PROFILES_ACTIVE=mock으로 부팅돼
# 사용자 정책(`feedback_no_mock_data.md`)을 위반하므로 default로 강제 포함.

set -euo pipefail
cd "$(dirname "$0")/.."

ALL_JVM=(api batch consumer)
ALL_SERVICES=(api-app batch-app consumer-app pyworker frontend)

GRADLE=1
DOCKER=1
MOCK=0
SERVICES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gradle-only)  DOCKER=0; shift ;;
    --no-gradle)    GRADLE=0; shift ;;
    --mock)         MOCK=1; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) SERVICES+=("$1"); shift ;;
  esac
done

# Compose 명령 합성: --env-file 명시(루트 .env가 docker/ 디렉토리 .env가 아니므로 필수)
# + dev override 기본 포함, --mock 시 제외
COMPOSE_ARGS=(--env-file .env -f docker/docker-compose.yml)
if [[ $MOCK -eq 0 ]]; then
  COMPOSE_ARGS+=(-f docker/docker-compose.dev.yml)
fi

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
  if [[ $MOCK -eq 1 ]]; then
    echo "▶ profile: MOCK (dev override 제외) — 테스트 전용"
  else
    echo "▶ profile: dev,real-kis (default)"
  fi

  echo "▶ docker compose build: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" build "${SERVICES[@]}"

  echo "▶ docker compose up -d: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" up -d "${SERVICES[@]}"

  echo "▶ healthy 대기 (~60s)…"
  sleep 30
  docker compose "${COMPOSE_ARGS[@]}" ps --format "{{.Service}}\t{{.Status}}"
fi

echo "✓ done"
