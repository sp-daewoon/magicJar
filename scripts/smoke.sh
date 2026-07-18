#!/usr/bin/env bash
# magicJar 통합 스모크 — 3 시나리오
#
# 사전 조건:
#   docker compose -f docker/docker-compose.yml up -d
#   docker compose -f docker/docker-compose.yml ps  # 모두 healthy
#
# 종료 코드:
#   0  → 모두 PASS (SKIP은 실패로 치지 않으나 말미에 눈에 띄는 경고 블록 출력)
#   1  → 1개 이상 FAIL
#   2  → 의존 명령 부재 / 3 → stale jar
#
# 시나리오 (2026-07-03 감사 §5-⑬ 계약 정정 — 공허 시나리오 3건 실계약으로 교체):
#   1. 백테스트 실행·영속        (POST /api/backtests?userId= — BacktestsController 실계약)
#   2. 시세 tick → 시그널 경로   (Kafka 실토픽 publish + signal 테이블 psql 검증)
#   3. 뉴스 조회                 (GET /api/symbols/{code}/news — 舊 POST /api/news/scrape는 매핑 자체가 없어 폐기)

set -uo pipefail
cd "$(dirname "$0")/.."

API="${API_URL:-http://localhost:8080}"
CONSUMER="${CONSUMER_URL:-http://localhost:8082}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-magicjar-kafka}"
PG_CONTAINER="${PG_CONTAINER:-magicjar-postgres}"
SMOKE_USER_ID="${SMOKE_USER_ID:-1}"

# ───── jar staleness 가드 ─────
# api/batch/consumer Dockerfile은 `COPY build/libs/<m>-*.jar /app/app.jar`로
# 호스트의 build 산출물을 그대로 가져온다. 즉 코드 변경 후 `./gradlew bootJar`를
# 안 돌리면 컨테이너에 옛 jar가 들어가 신규 엔드포인트 404 발생.
# 검사: 각 모듈 src의 최신 .kt mtime > build/libs/*.jar mtime 이면 abort.
#
# 감사 §6-7 — 공유 라이브러리 모듈(전략 DSL·지표 등)도 앱 jar에 포함되므로
# lib 모듈 src 변경 역시 각 앱 jar 대비 freshness 검사 대상에 포함 (사각 제거).
LIB_MODULES=(domain strategy-engine indicator-impl pattern-engine persistence-shared market-trend-engine)

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
  # 공유 라이브러리 모듈 src ↔ 앱 jar 비교 (감사 §6-7 — lib 변경 시 stale jar 미탐지 사각)
  local lib
  for lib in "${LIB_MODULES[@]}"; do
    [[ -d "$lib/src/main" ]] || continue
    newest_src=$(find "$lib/src/main" \( -name "*.kt" -o -name "*.kts" \) -newer "$jar" -print -quit 2>/dev/null)
    if [[ -n "$newest_src" ]]; then
      echo "✗ $module: 공유 lib $lib 의 $newest_src 가 jar($(basename "$jar"))보다 최신." >&2
      echo "  ./gradlew :$module:bootJar 다시 실행 후 docker compose build 진행." >&2
      return 1
    fi
  done
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

# postgres 단발 query helper (-tA: tuple only, unaligned)
psql_scalar() {
  docker exec "$PG_CONTAINER" psql -U magicjar -d magicjar -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

#───── 시나리오 1: 백테스트 실행·영속 ─────
# 실계약 (BacktestsController.kt): POST /api/backtests?userId=<uid>
#   body: strategyId(Long·실존 의무)·universe·interval·from·to·initialCapital
#   201=성공 / 206=ABORTED / 503=캔들 데이터 부족(NO_CANDLE_DATA) / 400=계약 위반
scenario_1() {
  echo
  echo "[1/3] 백테스트 실행·영속 (POST /api/backtests)"
  if ! docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
    mark_skip "scenario-1 backtest" "$PG_CONTAINER 미기동 — strategyId 조회 불가"
    return 0
  fi
  local strategy_id
  strategy_id=$(psql_scalar "SELECT id FROM strategy ORDER BY id LIMIT 1")
  if [[ -z "$strategy_id" ]]; then
    mark_skip "scenario-1 backtest" "strategy 테이블 0건 — 전략 시드 후 재시도"
    return 0
  fi
  local body code
  body=$(jq -nc --argjson sid "$strategy_id" '{
    strategyId: $sid,
    universe: ["005930"],
    interval: "1d",
    from: "2025-01-01",
    to: "2025-06-30",
    initialCapital: 10000000
  }')
  code=$(curl -s -o /tmp/smoke-bt-resp.json -w '%{http_code}' \
    -X POST "$API/api/backtests?userId=$SMOKE_USER_ID" \
    -H 'Content-Type: application/json' \
    -d "$body" 2>/dev/null) || code=000
  case "$code" in
    201|200)
      local run_id
      run_id=$(jq -r '.runId // "?"' </tmp/smoke-bt-resp.json 2>/dev/null || echo "?")
      mark_pass "scenario-1 backtest 실행·영속 (HTTP $code, runId=$run_id, strategyId=$strategy_id)"
      ;;
    206)
      mark_pass "scenario-1 backtest ABORTED-partial 영속 (HTTP 206 — 계약 정상, 데이터 부분)"
      ;;
    503)
      mark_skip "scenario-1 backtest" "캔들 데이터 부족 (NO_CANDLE_DATA — backfill 후 재시도)"
      ;;
    404)
      mark_fail "scenario-1 backtest" "HTTP 404 — /api/backtests 매핑 부재 (stale jar 의심)"
      ;;
    *)
      mark_fail "scenario-1 backtest" "HTTP $code — $(head -c 200 /tmp/smoke-bt-resp.json 2>/dev/null)"
      ;;
  esac
}

#───── 시나리오 2: 시세 tick → 시그널 경로 ─────
# 실토픽 kafka.consumer.evt.market.tick.v1 (KafkaTopics.Market.TICK — 舊 market.tick.v1 오기 정정).
# 검증 (감사 §5-⑬ — health 공허 검증 폐기):
#   ① consumer /actuator/health UP  ② signal 테이블 최근 24h row psql 확인.
# 시그널 0건은 장외·ACTIVE 전략 0개에서도 정상이므로 FAIL이 아닌 SKIP(경고) 처리.
scenario_2() {
  echo
  echo "[2/3] 실시간 시세 tick → 시그널 경로"
  if ! docker ps --format '{{.Names}}' | grep -q "^${KAFKA_CONTAINER}$"; then
    mark_skip "scenario-2 tick→signal" "$KAFKA_CONTAINER 미기동"
    return 0
  fi
  local payload='{"symbol":"005930","price":75000,"volume":100,"ts":"2026-04-27T10:00:00Z"}'
  if ! echo "$payload" | docker exec -i "$KAFKA_CONTAINER" \
       /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
       --topic kafka.consumer.evt.market.tick.v1 >/dev/null 2>&1; then
    mark_fail "scenario-2 tick→signal" "kafka publish 실패"
    return 0
  fi
  sleep 2
  local consumer_health
  consumer_health=$(curl -sf -o /dev/null -w '%{http_code}' "$CONSUMER/actuator/health" 2>/dev/null) || consumer_health=000
  if [[ ! "$consumer_health" =~ ^2 ]]; then
    mark_fail "scenario-2 tick→signal" "consumer health $consumer_health"
    return 0
  fi
  if ! docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
    mark_skip "scenario-2 tick→signal" "$PG_CONTAINER 미기동 — signal 테이블 검증 불가 (tick publish + consumer UP까지 확인)"
    return 0
  fi
  local signal_cnt
  signal_cnt=$(psql_scalar "SELECT COUNT(*) FROM signal WHERE created_at >= NOW() - INTERVAL '24 hours'")
  if [[ -z "$signal_cnt" ]]; then
    mark_fail "scenario-2 tick→signal" "signal 테이블 조회 실패 (psql)"
  elif [[ "$signal_cnt" -gt 0 ]]; then
    mark_pass "scenario-2 tick publish + consumer UP + 최근 24h 시그널 ${signal_cnt}건 (경로 생존 evidence)"
  else
    mark_skip "scenario-2 tick→signal" "최근 24h 시그널 0건 — 장외/ACTIVE 전략 0개면 정상, 장중 반복 0건이면 §감사 3-⑧ alert 참조"
  fi
}

#───── 시나리오 3: 뉴스 조회 ─────
# 舊 POST /api/news/scrape 는 매핑 자체가 존재하지 않아 영구 SKIP(공허)이었다 — 폐기.
# 실존 GET /api/symbols/{code}/news (SymbolNewsController)로 교체:
#   Naver 키 미설정이어도 items:[] 200 정상 (mock 합성 0 정합) / 401 = 온보딩 미완.
scenario_3() {
  echo
  echo "[3/3] 뉴스 조회 (GET /api/symbols/005930/news)"
  local code
  code=$(curl -s -o /tmp/smoke-news-resp.json -w '%{http_code}' \
    "$API/api/symbols/005930/news?page=0&size=5" 2>/dev/null) || code=000
  case "$code" in
    200)
      local total
      total=$(jq -r '.totalElements // 0' </tmp/smoke-news-resp.json 2>/dev/null || echo 0)
      mark_pass "scenario-3 news 조회 (HTTP 200, totalElements=$total — 키 미설정 시 0건 정상)"
      ;;
    401)
      mark_skip "scenario-3 news 조회" "온보딩 미완 (OnboardingGuardFilter 401)"
      ;;
    404)
      mark_skip "scenario-3 news 조회" "종목 005930 미시드 (instrument sync 후 재시도)"
      ;;
    *)
      mark_fail "scenario-3 news 조회" "HTTP $code"
      ;;
  esac
}

#───── 실행 ─────
scenario_1
scenario_2
scenario_3

echo
echo "==================== smoke.sh 결과 ===================="
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "------------------------------------------------------"
echo "PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP / 총 3"
echo "======================================================="

# 감사 §5-⑬ — SKIP은 "조용한 통과"가 아니라 눈에 띄는 경고다.
# SKIP 2연속 릴리스면 FAIL로 승격 검토 (release 절차 checklist 항목).
if [[ "$SKIP" -gt 0 ]]; then
  echo
  echo -e "${YELLOW}⚠⚠⚠  SKIP ${SKIP}건 — 검증되지 않은 시나리오가 있습니다  ⚠⚠⚠${NC}"
  echo -e "${YELLOW}     SKIP 사유를 해소하고 재실행하세요. 릴리스 2연속 동일 SKIP이면 FAIL로 취급합니다.${NC}"
fi

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
