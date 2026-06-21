#!/usr/bin/env node
// autopilot-seed-strategies.mjs — magicjar-autopilot Phase 1 첫 전략 세트(6종) 시드 (spec 2026-06-22 §Phase 1)
//
// owner=AUTOPILOT · user_id=74683223(canary) · status=PAUSED 로 6 단타 전략을 멱등 upsert 한다.
// 오케스트라가 개장 전 harness 스모크(칭 위반 거부·kill·롤백 실증) 통과 후에만 status를 ACTIVE 로 전환한다.
// **이 스크립트는 절대 ACTIVE 로 만들지 않는다** (실돈 안전 — 활성화는 orchestrator 의 명시 행위).
//
// 가설 레지스트리: _workspace/autopilot/hypotheses-2026-06-22.md
// DSL 검증: strategy-engine AutopilotSeedStrategyDslTest (파싱+정적검증+엔진매핑 GREEN — 라이브 평가 가능 식만 사용).
//
// DB 접속: 기존 ops/snapshot 스크립트 컨벤션(scripts/autopilot-snapshot.mjs)과 동일 —
//   `docker exec magicjar-postgres psql -U magicjar -d magicjar` (별도 드라이버 의존 0).
//
// 사용:
//   node scripts/autopilot-seed-strategies.mjs            # upsert 실행
//   node scripts/autopilot-seed-strategies.mjs --dry-run  # SQL·universe 출력만, DB 변경 0
//
// 멱등성: (owner='AUTOPILOT', name) 으로 식별. 이미 있으면 DSL/TP/SL/codes/intervals 를 UPDATE(upsert),
//   없으면 INSERT. **status 는 신규 INSERT 시에만 'PAUSED' 로 설정하고, 기존 행의 status 는 절대 건드리지 않는다**
//   (오케스트라가 ACTIVE 전환한 전략을 재실행이 PAUSED 로 되돌리는 사고 방지).

import { execSync } from 'node:child_process';

const DRY_RUN = process.argv.includes('--dry-run');

const CANARY_USER_ID = '74683223';
const OWNER = 'AUTOPILOT';
const ORIGIN = 'USER_DSL'; // chk_strategy_origin 허용값(USER_DSL|PAPER_DISCOVERY). autopilot 식별은 owner='AUTOPILOT' 가 권위.
const UNIVERSE_MARKET = 'KOSPI';
const VI_ACTION_POLICY = 'ALERT_ONLY';
const UNIVERSE_CAP = 24; // universe 총 코드 상한(실돈 안전 — 과도 구독 방지).

const PSQL_BASE = 'docker exec magicjar-postgres psql -U magicjar -d magicjar';

// ── 유동성 앵커 (KOSPI 대형주 고유동성 8종) — 체결·슬리피지 안전, 깊은 호가 ──────────────
const LIQUIDITY_ANCHORS = [
  '005930', // 삼성전자
  '000660', // SK하이닉스
  '035420', // NAVER
  '005380', // 현대차
  '000270', // 기아
  '105560', // KB금융
  '035720', // 카카오
  '051910', // LG화학
];

// ── 6 전략 정의 (가설 레지스트리 hypotheses-2026-06-22.md 와 1:1, DSL 은 AutopilotSeedStrategyDslTest 와 1:1) ──
const STRATEGIES = [
  {
    name: 'autopilot-momentum-breakout',
    nameKo: '오토파일럿 모멘텀 돌파',
    tf: '3m',
    dslEntry: '((close breaks donchian_upper(20)) AND (volume > volume_ma(20) * 1.5)) AND (close > vwap())',
    dslExit: 'close < vwap()',
    takeProfitPct: '2.5',
    fixedStopLossPct: '1.5',
  },
  {
    name: 'autopilot-pullback-reclaim',
    nameKo: '오토파일럿 눌림목 반등',
    tf: '5m',
    dslEntry: '(((close > ma(50)) AND (ma(20) > ma(50))) AND (rsi(14) > 40)) AND (close > ma(20))',
    dslExit: 'close < ma(20)',
    takeProfitPct: '2.0',
    fixedStopLossPct: '1.5',
  },
  {
    name: 'autopilot-fvg-bullish-reclaim',
    nameKo: '오토파일럿 FVG 매수 재진입',
    tf: '5m',
    dslEntry: '(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))',
    dslExit: 'close < fvg_bullish_bottom(50)',
    takeProfitPct: '2.5',
    fixedStopLossPct: '2.0',
  },
  {
    name: 'autopilot-orderblock-bullish-retest',
    nameKo: '오토파일럿 오더블록 리테스트',
    tf: '5m',
    dslEntry: '(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))',
    dslExit: 'close < ob_bullish_bottom(20, 20, 50)',
    takeProfitPct: '2.5',
    fixedStopLossPct: '2.0',
  },
  {
    name: 'autopilot-volume-spike',
    nameKo: '오토파일럿 거래량 급증',
    tf: '3m',
    dslEntry: '((volume > volume_ma(20) * 2) AND (mfi(14) > 55)) AND (close > vwap())',
    dslExit: 'close < vwap()',
    takeProfitPct: '3.0',
    fixedStopLossPct: '2.0',
  },
  {
    name: 'autopilot-vwap-reversion',
    nameKo: '오토파일럿 VWAP 회귀',
    tf: '5m',
    dslEntry: '((close < bb_lower(20)) AND (rsi(14) < 30)) AND (close < vwap())',
    dslExit: 'close > vwap()',
    takeProfitPct: '2.0',
    fixedStopLossPct: '2.0',
  },
];

// ── psql helper ──────────────────────────────────────────────────────────────
// 필드 구분자는 파이프(|) — 종목코드·상태·id 에 등장하지 않는 안전 문자(tab 의 shell escape 함정 회피).
const FS = '|';
function psqlQuery(sql) {
  const cmd = `${PSQL_BASE} -t -A -F'${FS}' -c "${sql.replace(/"/g, '\\"')}"`;
  try {
    const out = execSync(cmd, { encoding: 'utf8' });
    return out
      .trim()
      .split('\n')
      .filter((l) => l.length > 0)
      .map((l) => l.split(FS));
  } catch (e) {
    console.error(`[error] 쿼리 실패: ${sql.slice(0, 80)}... → ${e.message.split('\n')[0]}`);
    throw e;
  }
}

function psqlExec(sql) {
  if (DRY_RUN) {
    console.log(`-- [dry-run] ${sql}`);
    return;
  }
  const cmd = `${PSQL_BASE} -v ON_ERROR_STOP=1 -c "${sql.replace(/"/g, '\\"')}"`;
  execSync(cmd, { encoding: 'utf8', stdio: 'inherit' });
}

// SQL string literal 안전 이스케이프(작은따옴표 doubling).
function lit(s) {
  if (s === null || s === undefined) return 'NULL';
  return `'${String(s).replace(/'/g, "''")}'`;
}

// JSONB 문자열 배열 리터럴.
function jsonbArrayLit(arr) {
  return `'${JSON.stringify(arr).replace(/'/g, "''")}'::jsonb`;
}

// 결정론 복잡도 산정(경량) — indicator 호출수 = 식별자 함수 호출 패턴 count, node_count = 토큰 근사.
// 정밀 AST 산정은 백엔드 KisYamlDslParser.validateStatic 권위(consumer 재파싱). 여기선 합리적 메타값.
function estimateComplexity(entry, exit) {
  const text = `${entry} ${exit ?? ''}`;
  const indicatorCalls = (text.match(/[a-zA-Z_][a-zA-Z0-9_]*\s*\(/g) || []).length;
  const operators = (text.match(/AND|OR|>|<|==|breaks|\*/g) || []).length;
  const nodeCount = indicatorCalls * 2 + operators * 2 + 4;
  const indicatorCallCount = indicatorCalls;
  const estimatedEvalTimeMs = indicatorCallCount * 4;
  // KisYamlDslParser.validateStatic 임계와 정합한 보수적 분류.
  let complexityClass = 'LOW';
  if (indicatorCallCount > 15 || nodeCount > 100) complexityClass = 'MEDIUM';
  if (indicatorCallCount > 30 || nodeCount > 500) complexityClass = 'HIGH';
  // depth: 본 6식은 최대 4단 중첩 → 보수적 6.
  const astDepth = 6;
  return { astDepth, nodeCount, indicatorCallCount, estimatedEvalTimeMs, complexityClass };
}

// ── universe 구성: 앵커 + discovery_scorecard 최신 snapshot TOP3/preset ──────────
function buildUniverse() {
  const codes = [...LIQUIDITY_ANCHORS];
  let discoveryAdded = [];
  try {
    const rows = psqlQuery(
      "SELECT DISTINCT instrument_code FROM discovery_scorecard " +
        "WHERE snapshot_date = (SELECT max(snapshot_date) FROM discovery_scorecard) " +
        "AND rank <= 3 ORDER BY instrument_code",
    );
    discoveryAdded = rows.map((r) => r[0]).filter((c) => c && /^[0-9A-Z]{4,12}$/.test(c) && !codes.includes(c));
  } catch (e) {
    console.error(`[warn] discovery_scorecard 조회 실패 — 앵커만으로 universe 구성: ${e.message.split('\n')[0]}`);
  }
  for (const c of discoveryAdded) {
    if (codes.length >= UNIVERSE_CAP) break;
    codes.push(c);
  }
  return { codes, anchorCount: LIQUIDITY_ANCHORS.length, discoveryCount: codes.length - LIQUIDITY_ANCHORS.length };
}

// ── upsert 한 전략 ────────────────────────────────────────────────────────────
function upsertStrategy(s, codes) {
  const c = estimateComplexity(s.dslEntry, s.dslExit);
  // intervals = 전략 tf 단일. codes = universe 공통.
  const intervals = [s.tf];

  // 멱등: 존재 여부 확인.
  const existing = psqlQuery(
    `SELECT id, status FROM strategy WHERE owner = ${lit(OWNER)} AND name = ${lit(s.name)} LIMIT 1`,
  );

  const codesLit = jsonbArrayLit(codes);
  const intervalsLit = jsonbArrayLit(intervals);

  if (existing.length > 0) {
    const [id, status] = existing[0];
    // UPDATE — DSL/TP/SL/codes/intervals/복잡도 정합. **status 는 절대 건드리지 않는다**(ACTIVE 전환 보존).
    const sql =
      `UPDATE strategy SET ` +
      `name_ko = ${lit(s.nameKo)}, ` +
      `dsl_entry = ${lit(s.dslEntry)}, ` +
      `dsl_exit = ${lit(s.dslExit)}, ` +
      `universe_market = ${lit(UNIVERSE_MARKET)}, ` +
      `tf = ${lit(s.tf)}, ` +
      `codes = ${codesLit}, ` +
      `intervals = ${intervalsLit}, ` +
      `take_profit_pct = ${s.takeProfitPct}, ` +
      `fixed_stop_loss_pct = ${s.fixedStopLossPct}, ` +
      `scale_in_config = NULL, ` +
      `partial_exits = NULL, ` +
      `entry_policy = NULL, ` +
      `ast_depth = ${c.astDepth}, ` +
      `node_count = ${c.nodeCount}, ` +
      `indicator_call_count = ${c.indicatorCallCount}, ` +
      `estimated_eval_time_ms = ${c.estimatedEvalTimeMs}, ` +
      `complexity_class = ${lit(c.complexityClass)}, ` +
      `origin = ${lit(ORIGIN)}, ` +
      `vi_action_policy = ${lit(VI_ACTION_POLICY)}, ` +
      `updated_at = now() ` +
      `WHERE id = ${id}`;
    psqlExec(sql);
    console.log(`  ↻ upsert(update) [${s.name}] id=${id} status=${status}(보존) tf=${s.tf} TP=${s.takeProfitPct}% SL=${s.fixedStopLossPct}%`);
  } else {
    // INSERT — 신규는 status='PAUSED' 강제(절대 ACTIVE 아님).
    const sql =
      `INSERT INTO strategy (` +
      `user_id, owner, name, name_ko, dsl_entry, dsl_exit, universe_market, tf, codes, intervals, ` +
      `take_profit_pct, fixed_stop_loss_pct, scale_in_config, partial_exits, entry_policy, ` +
      `ast_depth, node_count, indicator_call_count, estimated_eval_time_ms, complexity_class, ` +
      `origin, status, vi_action_policy, created_at, updated_at` +
      `) VALUES (` +
      `${CANARY_USER_ID}, ${lit(OWNER)}, ${lit(s.name)}, ${lit(s.nameKo)}, ${lit(s.dslEntry)}, ${lit(s.dslExit)}, ` +
      `${lit(UNIVERSE_MARKET)}, ${lit(s.tf)}, ${codesLit}, ${intervalsLit}, ` +
      `${s.takeProfitPct}, ${s.fixedStopLossPct}, NULL, NULL, NULL, ` +
      `${c.astDepth}, ${c.nodeCount}, ${c.indicatorCallCount}, ${c.estimatedEvalTimeMs}, ${lit(c.complexityClass)}, ` +
      `${lit(ORIGIN)}, 'PAUSED', ${lit(VI_ACTION_POLICY)}, now(), now()` +
      `)`;
    psqlExec(sql);
    console.log(`  + insert(PAUSED) [${s.name}] tf=${s.tf} TP=${s.takeProfitPct}% SL=${s.fixedStopLossPct}% complexity=${c.complexityClass}`);
  }
}

// ── main ──────────────────────────────────────────────────────────────────────
function main() {
  console.log(`magicjar-autopilot 첫 전략 세트 시드 — owner=${OWNER} user_id=${CANARY_USER_ID} status=PAUSED ${DRY_RUN ? '[DRY-RUN]' : ''}`);
  const { codes, anchorCount, discoveryCount } = buildUniverse();
  console.log(`universe: ${codes.length}종 (유동성앵커 ${anchorCount} + 발굴후보 ${discoveryCount}, cap=${UNIVERSE_CAP})`);
  console.log(`  codes=${JSON.stringify(codes)}`);
  console.log(`전략 ${STRATEGIES.length}건 upsert:`);
  for (const s of STRATEGIES) {
    upsertStrategy(s, codes);
  }
  console.log('');
  console.log(`완료. ${DRY_RUN ? '(dry-run — DB 변경 0)' : '6전략 owner=AUTOPILOT status=PAUSED 시드됨.'}`);
  console.log('⚠ ACTIVE 전환은 orchestrator 가 harness 스모크(칭 위반 거부·kill·롤백 실증) 통과 후에만 수행.');
  console.log('⚠ consumer 평가 hot path 가 owner=USER 만 조회 — ACTIVE 전환 전 owner=AUTOPILOT 평가 wiring 필요(가설 레지스트리 §발견 참조).');
}

main();
