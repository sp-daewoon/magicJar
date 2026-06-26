#!/usr/bin/env node
// seed-oversold-bounce-strategy.mjs — strategy-engine 검증(2026-06-26)이 도출한 단타 "과매도 급락 반등" 전략 1종 시드.
//
// 배경: 고변동성 하락장 1m scalping 검증(ScalpingIntradayHarness/ScalpingRefineHarness, ADR 0132 INTRADAY_REALISTIC).
//   - 현행 autopilot 6전략(돌파/모멘텀 추종)은 하락장에서 손실 → mean-reversion(과매도 반등) 가설 검증.
//   - 최종 config = roc(5)<-3 AND rsi(14)<30 진입 / rsi>60 청산 / TP3% / SL1.2% / trailing1.2% / min_hold 2봉 / EOD.
//   - ★ trailing 영속(ADR0135·V96 strategy.trailing_stop_pct) 배포 후 재검증 — net-positive 핵심 레버.
//
// ★ 검증 판정(trailing 포함 재검증, 무버6 멀티레짐): IS(2~3월 n=19) net +0.22%/거래 PF 1.67 ·
//   OOS(5월 n=52) net +0.96%/거래 PF 1.80 win 53.9% — IS·OOS 모두 net-positive, trailing 1.0~2.0 plateau 양(+).
//   단 ACTIVE 승격은 최고 관리자 + ADR0133 검증 게이트가 결정한다. 본 시드는 **status=PAUSED 로만** 생성.
//
// owner=AUTOPILOT · user_id=74683223(canary) · status=PAUSED · 멱등 upsert.
// **절대 ACTIVE 로 만들지 않는다** (실돈 안전). 기존 행 status 는 보존.
//
// 사용:
//   node scripts/seed-oversold-bounce-strategy.mjs --dry-run   # SQL 출력만, DB 변경 0
//   node scripts/seed-oversold-bounce-strategy.mjs             # upsert(PAUSED) 실행

import { execSync } from 'node:child_process';

const DRY_RUN = process.argv.includes('--dry-run');
const CANARY_USER_ID = '74683223';
const OWNER = 'AUTOPILOT';
const ORIGIN = 'USER_DSL';
const UNIVERSE_MARKET = 'KOSDAQ'; // 변동성 무버 대부분 코스닥.
const VI_ACTION_POLICY = 'ALERT_ONLY';
const PSQL_BASE = 'docker exec magicjar-postgres psql -U magicjar -d magicjar';
const FS = '|';

const STRATEGY = {
  name: 'autopilot-oversold-crash-bounce',
  nameKo: '오토파일럿 과매도 급락 반등(단타)',
  tf: '1m',
  // 진입: 5봉 급락(-3%) + RSI 과매도(<30) 동시 — falling-knife 1차 필터(검증상 거래 수↓·손실↓).
  dslEntry: '(roc(5) < -3) AND (rsi(14) < 30)',
  // 청산 DSL: 반등 모멘텀 소진(rsi>60) — trailing 과 함께 winner-run 보강.
  dslExit: 'rsi(14) > 60',
  takeProfitPct: '3.0',
  fixedStopLossPct: '1.2',
  // ★ trailing 1.2% — ADR0135(V96) 영속 인프라 배포 후 검증상 net-positive 핵심 레버(IS+0.22%/OOS+0.96%, PF 1.67/1.80).
  trailingStopPct: '1.2',
  minHoldBars: 2,
  allowOvernight: false, // EOD 15:15 강제 청산(오버나잇 폭락 회피 — 단타 정합).
};

// universe: 현행 autopilot 발굴 무버 universe 와 공유(WS 슬롯 절약 · crash-bounce 가 노리는 변동성 종목).
function resolveUniverse() {
  try {
    const out = execSync(
      `${PSQL_BASE} -t -A -c "SELECT codes::text FROM strategy WHERE owner='AUTOPILOT' AND status IN ('ACTIVE','BUY_BLOCKED') AND codes IS NOT NULL ORDER BY id LIMIT 1"`,
      { encoding: 'utf8' },
    ).trim();
    if (out) return JSON.parse(out);
  } catch (e) {
    console.error(`[warn] autopilot universe 조회 실패 — fallback 사용: ${e.message.split('\n')[0]}`);
  }
  // fallback: 변동성 무버 deep-history 6종.
  return ['047040', '036930', '323280', '240810', '277810', '000650'];
}

function lit(s) {
  if (s === null || s === undefined) return 'NULL';
  return `'${String(s).replace(/'/g, "''")}'`;
}
function jsonbArrayLit(arr) {
  return `'${JSON.stringify(arr).replace(/'/g, "''")}'::jsonb`;
}
function psqlExec(sql) {
  if (DRY_RUN) {
    console.log(`-- [dry-run]\n${sql};\n`);
    return;
  }
  execSync(`${PSQL_BASE} -v ON_ERROR_STOP=1 -c "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8', stdio: 'inherit' });
}
function psqlQuery(sql) {
  const out = execSync(`${PSQL_BASE} -t -A -F'${FS}' -c "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8' });
  return out.trim().split('\n').filter((l) => l.length > 0).map((l) => l.split(FS));
}

function main() {
  const s = STRATEGY;
  const codes = resolveUniverse();
  console.log(`oversold-crash-bounce 시드 (status=PAUSED) ${DRY_RUN ? '[DRY-RUN]' : ''}`);
  console.log(`  universe(${codes.length}): ${JSON.stringify(codes)}`);

  const codesLit = jsonbArrayLit(codes);
  const intervalsLit = jsonbArrayLit([s.tf]);
  // 복잡도(보수적 메타) — entry 지표 2 + exit 1 = 3 호출.
  const astDepth = 3, nodeCount = 9, indicatorCallCount = 3, estimatedEvalTimeMs = 12, complexityClass = 'LOW';

  const existing = psqlQuery(`SELECT id, status FROM strategy WHERE owner=${lit(OWNER)} AND name=${lit(s.name)} LIMIT 1`);
  if (existing.length > 0) {
    const [id, status] = existing[0];
    const sql =
      `UPDATE strategy SET name_ko=${lit(s.nameKo)}, dsl_entry=${lit(s.dslEntry)}, dsl_exit=${lit(s.dslExit)}, ` +
      `universe_market=${lit(UNIVERSE_MARKET)}, tf=${lit(s.tf)}, codes=${codesLit}, intervals=${intervalsLit}, ` +
      `take_profit_pct=${s.takeProfitPct}, fixed_stop_loss_pct=${s.fixedStopLossPct}, trailing_stop_pct=${s.trailingStopPct}, ` +
      `min_hold_bars=${s.minHoldBars}, ` +
      `allow_overnight=${s.allowOvernight}, scale_in_config=NULL, partial_exits=NULL, entry_policy=NULL, ` +
      `ast_depth=${astDepth}, node_count=${nodeCount}, indicator_call_count=${indicatorCallCount}, ` +
      `estimated_eval_time_ms=${estimatedEvalTimeMs}, complexity_class=${lit(complexityClass)}, ` +
      `origin=${lit(ORIGIN)}, vi_action_policy=${lit(VI_ACTION_POLICY)}, updated_at=now() WHERE id=${id}`;
    psqlExec(sql);
    console.log(`  ↻ upsert(update) id=${id} status=${status}(보존) — DSL/TP/SL/trail/min_hold/overnight 정합`);
  } else {
    const sql =
      `INSERT INTO strategy (user_id, owner, name, name_ko, dsl_entry, dsl_exit, universe_market, tf, codes, intervals, ` +
      `take_profit_pct, fixed_stop_loss_pct, trailing_stop_pct, min_hold_bars, allow_overnight, scale_in_config, partial_exits, entry_policy, ` +
      `ast_depth, node_count, indicator_call_count, estimated_eval_time_ms, complexity_class, origin, status, vi_action_policy, created_at, updated_at) ` +
      `VALUES (${CANARY_USER_ID}, ${lit(OWNER)}, ${lit(s.name)}, ${lit(s.nameKo)}, ${lit(s.dslEntry)}, ${lit(s.dslExit)}, ` +
      `${lit(UNIVERSE_MARKET)}, ${lit(s.tf)}, ${codesLit}, ${intervalsLit}, ${s.takeProfitPct}, ${s.fixedStopLossPct}, ${s.trailingStopPct}, ` +
      `${s.minHoldBars}, ${s.allowOvernight}, NULL, NULL, NULL, ${astDepth}, ${nodeCount}, ${indicatorCallCount}, ` +
      `${estimatedEvalTimeMs}, ${lit(complexityClass)}, ${lit(ORIGIN)}, 'PAUSED', ${lit(VI_ACTION_POLICY)}, now(), now())`;
    psqlExec(sql);
    console.log(`  + insert(PAUSED) [${s.name}] tf=${s.tf} TP=${s.takeProfitPct}% SL=${s.fixedStopLossPct}% trail=${s.trailingStopPct}% min_hold=${s.minHoldBars} overnight=${s.allowOvernight}`);
  }
  console.log('⚠ status=PAUSED — ACTIVE 승격은 최고 관리자 + ADR0133 검증 게이트 통과 후에만(주문·autopilot 투입 0).');
  console.log('✓ trailing_stop_pct 영속(ADR0135·V96) — 검증상 net-positive 핵심 레버 라이브 반영됨.');
}

main();
