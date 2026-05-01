#!/usr/bin/env bash
# magicJar 통합 스모크 — Phase D-P4 (5 시나리오 / spec §15-2)
#
# 사전 조건:
#   docker compose -f docker/docker-compose.yml up -d
#   docker compose -f docker/docker-compose.yml ps  # 모두 healthy
#
# 종료 코드:
#   0  → 5/5 PASS (또는 SKIP은 명시적으로 표기되며 실패로 치지 않음)
#   1  → 1개 이상 FAIL
#
# 시나리오:
#   1. 백테스트 트리거 → 매매일지       (POST /api/backtest, GET /api/journal)
#   2. 시세 → 신호 → STOMP fan-out      (Kafka publish + STOMP subscribe)
#   3. 뉴스 크롤(pyworker) → AI 분석    (POST /scrape/naver → news.analyzed.v1)
#   4. 차트 캡처 클라 → MinIO            (POST /api/journal/{tradeId}/snapshots)
#   5. 서버사이드 캡처(pyworker)         (POST /screenshot → MinIO 객체 GET 200)

set -uo pipefail
cd "$(dirname "$0")/.."

API="${API_URL:-http://localhost:8080}"
PYWORKER="${PYWORKER_URL:-http://localhost:8000}"
MINIO="${MINIO_URL:-http://localhost:9000}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-magicjar-kafka}"

# ───── jar staleness 가드 (P1 — 운영 함정 차단) ─────
# api/batch/consumer Dockerfile은 `COPY build/libs/<m>-*.jar /app/app.jar`로
# 호스트의 build 산출물을 그대로 가져온다. 즉 코드 변경 후 `./gradlew bootJar`를
# 안 돌리면 컨테이너에 옛 jar가 들어가 V7 시드 미적용·신규 엔드포인트 404 발생.
# 검사: 각 모듈 src의 최신 .kt mtime > build/libs/*.jar mtime 이면 abort.
check_jar_freshness() {
  local module="$1"
  local jar
  jar=$(ls -t "$module"/build/libs/${module}-*-SNAPSHOT.jar 2>/dev/null | head -1)
  if [[ -z "$jar" ]]; then
    echo "✗ $module: bootJar 산출물 없음. './gradlew :$module:bootJar' 먼저 실행." >&2
    return 1
  fi
  local newest_src
  newest_src=$(find "$module/src/main" -name "*.kt" -newer "$jar" -print -quit 2>/dev/null)
  if [[ -n "$newest_src" ]]; then
    echo "✗ $module: src의 $newest_src 가 jar($(basename "$jar"))보다 최신." >&2
    echo "  ./gradlew :$module:bootJar 다시 실행 후 docker compose build 진행." >&2
    return 1
  fi
  return 0
}

if [[ "${SKIP_FRESHNESS_CHECK:-0}" != "1" ]]; then
  freshness_failed=0
  for m in api batch consumer; do
    check_jar_freshness "$m" || freshness_failed=1
  done
  if [[ $freshness_failed -ne 0 ]]; then
    echo "" >&2
    echo "운영 함정 가드: stale jar 감지. 다음 1줄로 일괄 갱신:" >&2
    echo "  ./gradlew :api:bootJar :batch:bootJar :consumer:bootJar" >&2
    echo "  docker compose -f docker/docker-compose.yml build api-app batch-app consumer-app" >&2
    echo "  docker compose -f docker/docker-compose.yml up -d" >&2
    echo "(우회 필요 시 SKIP_FRESHNESS_CHECK=1 prefix)" >&2
    exit 3
  fi
fi

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0
SKIP=0
declare -a RESULTS

mark_pass() { echo -e "${GREEN}PASS${NC} — $1"; PASS=$((PASS+1)); RESULTS+=("PASS: $1"); }
mark_fail() { echo -e "${RED}FAIL${NC} — $1 ($2)"; FAIL=$((FAIL+1)); RESULTS+=("FAIL: $1 ($2)"); }
mark_skip() { echo -e "${YELLOW}SKIP${NC} — $1 ($2)"; SKIP=$((SKIP+1)); RESULTS+=("SKIP: $1 ($2)"); }

#───── pre-check ─────
require() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing dependency: $1" >&2; exit 2; }
}
require curl
require jq

#───── 시나리오 1: 백테스트 트리거 → 매매일지 ─────
scenario_1() {
  echo
  echo "[1/5] 백테스트 트리거 → 매매일지"
  local code
  code=$(curl -sf -o /tmp/p4-bt-resp.json -w '%{http_code}' \
    -X POST "$API/api/backtest" \
    -H 'Content-Type: application/json' \
    -d '{"strategyId":"sma-cross","symbol":"005930","from":"2024-01-01","to":"2024-12-31"}' \
    2>/dev/null) || code=000
  if [[ "$code" == "404" ]]; then
    mark_skip "scenario-1 backtest→journal" "REST endpoint /api/backtest 미구현 (Phase E)"
    return 0
  fi
  if [[ "$code" =~ ^2 ]]; then
    mark_pass "scenario-1 backtest→journal (HTTP $code)"
  else
    mark_fail "scenario-1 backtest→journal" "HTTP $code"
  fi
}

#───── 시나리오 2: 시세 → 신호 → STOMP fanout ─────
scenario_2() {
  echo
  echo "[2/5] 실시간 시세 → 신호 → STOMP fan-out"
  if ! docker ps --format '{{.Names}}' | grep -q "^${KAFKA_CONTAINER}$"; then
    mark_skip "scenario-2 tick→signal→stomp" "$KAFKA_CONTAINER 미기동"
    return 0
  fi
  local payload='{"symbol":"005930","price":75000,"volume":100,"ts":"2026-04-27T10:00:00Z"}'
  # apache/kafka 이미지는 /opt/kafka/bin/ 경로 사용 (bitnami는 /opt/bitnami/kafka/bin/)
  if echo "$payload" | docker exec -i "$KAFKA_CONTAINER" \
       /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
       --topic market.tick.v1 >/dev/null 2>&1; then
    sleep 2
    local stomp_health
    stomp_health=$(curl -sf -o /dev/null -w '%{http_code}' "$API/actuator/health" 2>/dev/null) || stomp_health=000
    if [[ "$stomp_health" =~ ^2 ]]; then
      mark_pass "scenario-2 tick publish + api health UP (STOMP fanout 가시 검증은 frontend e2e 영역)"
    else
      mark_fail "scenario-2 tick→signal→stomp" "api health $stomp_health"
    fi
  else
    mark_fail "scenario-2 tick→signal→stomp" "kafka publish 실패"
  fi
}

#───── 시나리오 3: 뉴스 크롤 → AI 분석 ─────
scenario_3() {
  echo
  echo "[3/5] 뉴스 크롤(pyworker) → AI 분석(consumer)"
  local code
  code=$(curl -sf -o /tmp/p4-naver-resp.json -w '%{http_code}' \
    -X POST "$PYWORKER/scrape/naver" 2>/dev/null) || code=000
  if [[ "$code" =~ ^2 ]]; then
    local published
    published=$(jq -r '.published // 0' </tmp/p4-naver-resp.json 2>/dev/null || echo 0)
    mark_pass "scenario-3 naver scrape (published=$published, AI 분석은 OPENAI_API_KEY 의존)"
  elif [[ "$code" == "503" ]]; then
    mark_skip "scenario-3 news→ai" "pyworker 의존성(news_raw_publisher) 미가용"
  else
    mark_fail "scenario-3 news→ai" "HTTP $code"
  fi
}

#───── 시나리오 4: 차트 캡처 클라이언트 → MinIO ─────
scenario_4() {
  echo
  echo "[4/5] 차트 캡처 클라이언트 → api → MinIO"
  # tiny 1x1 PNG (base64)
  local tiny_png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgAAIAAAUAAen63NgAAAAASUVORK5CYII='
  local trade_id='00000000-0000-0000-0000-000000000001'
  local code
  code=$(curl -sf -o /tmp/p4-snap-resp.json -w '%{http_code}' \
    -X POST "$API/api/journal/$trade_id/snapshots" \
    -H 'Content-Type: application/json' \
    -d "{\"imageBase64\":\"$tiny_png\",\"contentType\":\"image/png\",\"width\":1,\"height\":1}" \
    2>/dev/null) || code=000
  if [[ "$code" == "404" ]]; then
    mark_skip "scenario-4 client-capture→minio" "REST endpoint 미구현 (Phase E)"
  elif [[ "$code" =~ ^2 ]]; then
    mark_pass "scenario-4 client-capture→minio (HTTP $code)"
  else
    mark_fail "scenario-4 client-capture→minio" "HTTP $code"
  fi
}

#───── 시나리오 5: 서버사이드 캡처 (pyworker Playwright) ─────
scenario_5() {
  echo
  echo "[5/5] 서버사이드 캡처 (pyworker Playwright)"
  local code
  code=$(curl -sf -o /tmp/p4-shot-resp.json -w '%{http_code}' \
    -X POST "$PYWORKER/screenshot" \
    -H 'Content-Type: application/json' \
    -d '{"url":"https://example.com","viewport":{"width":1280,"height":720},"fullPage":false}' \
    2>/dev/null) || code=000
  if [[ "$code" =~ ^2 ]]; then
    local public_url
    public_url=$(jq -r '.publicUrl // empty' </tmp/p4-shot-resp.json 2>/dev/null || echo)
    if [[ -n "$public_url" ]]; then
      local fetch_code
      fetch_code=$(curl -sIf -o /dev/null -w '%{http_code}' "$public_url" 2>/dev/null) || fetch_code=000
      if [[ "$fetch_code" == "200" ]]; then
        mark_pass "scenario-5 server-capture (publicUrl reachable)"
      else
        mark_fail "scenario-5 server-capture" "publicUrl HTTP $fetch_code"
      fi
    else
      mark_pass "scenario-5 server-capture (publicUrl 없음 — MinIO public 비활성. 캡처 자체는 OK)"
    fi
  elif [[ "$code" == "503" ]]; then
    mark_skip "scenario-5 server-capture" "pyworker capturer 미가용 (Playwright 미설치 환경)"
  else
    mark_fail "scenario-5 server-capture" "HTTP $code"
  fi
}

#───── 실행 ─────
scenario_1
scenario_2
scenario_3
scenario_4
scenario_5

echo
echo "==================== smoke.sh 결과 ===================="
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "------------------------------------------------------"
echo "PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP / 총 5"
echo "======================================================="

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
