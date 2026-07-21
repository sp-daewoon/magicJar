#!/usr/bin/env node
// magicjar-autopilot Phase 1 첫 전략 세트(6종)를 exact 사용자에게 PAUSED로만 시드한다.
// 기존 전략은 수정하지 않으며, ACTIVE 전환은 fresh backtest와 명시적 상태 전환 API를 별도로 통과해야 한다.
//
// 사용:
//   node scripts/autopilot-seed-strategies.mjs --user-id <id> --docker-context <context>
//   node scripts/autopilot-seed-strategies.mjs --user-id <id> --docker-context <context> --dry-run

import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const OWNER = 'AUTOPILOT';
const ORIGIN = 'USER_DSL'; // chk_strategy_origin 허용값(USER_DSL|PAPER_DISCOVERY). autopilot 식별은 owner='AUTOPILOT' 가 권위.
const UNIVERSE_MARKET = 'KOSPI';
const VI_ACTION_POLICY = 'ALERT_ONLY';
const PG_BIGINT_MAX = 9223372036854775807n;
// 휩쏘 완화 minHold (사용자 결재 2026-07-03 · V97__strategy_wipsaw_mitigation 정합) — 진입 후 N봉 동안
//   DSL 매도 무시(손절·익절 라인은 MinHoldGuard 우회 — 안전망 우선). INSERT 시에만 명시 주입한다.
const MIN_HOLD_BARS = 8;

// ── 유동성 앵커 (KOSPI 대형주 고유동성 8종) — 체결·슬리피지 안전, 깊은 호가 ──────────────
export const LIQUIDITY_ANCHORS = [
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
// dslExit 0.3% 버퍼 (사용자 결재 2026-07-03 · V97 정합 — 휩쏘 완화): 하향 이탈 청산은 `* 0.997`,
//   vwap-reversion 이익 실현 방향은 `* 1.003`. 재실행 upsert 가 V97 적용분을 되돌리지 않도록 시드 원문도 버퍼판 유지.
export const STRATEGIES = [
  {
    name: 'autopilot-momentum-breakout',
    nameKo: '오토파일럿 모멘텀 돌파',
    tf: '3m',
    dslEntry: '((close breaks donchian_upper(20)) AND (volume > volume_ma(20) * 1.5)) AND (close > vwap())',
    dslExit: 'close < vwap() * 0.997',
    takeProfitPct: '2.5',
    fixedStopLossPct: '1.5',
  },
  {
    name: 'autopilot-pullback-reclaim',
    nameKo: '오토파일럿 눌림목 반등',
    tf: '5m',
    dslEntry: '(((close > ma(50)) AND (ma(20) > ma(50))) AND (rsi(14) > 40)) AND (close > ma(20))',
    dslExit: 'close < ma(20) * 0.997',
    takeProfitPct: '2.0',
    fixedStopLossPct: '1.5',
  },
  {
    name: 'autopilot-fvg-bullish-reclaim',
    nameKo: '오토파일럿 FVG 매수 재진입',
    tf: '5m',
    dslEntry: '(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))',
    dslExit: 'close < fvg_bullish_bottom(50) * 0.997',
    takeProfitPct: '2.5',
    fixedStopLossPct: '2.0',
  },
  {
    name: 'autopilot-orderblock-bullish-retest',
    nameKo: '오토파일럿 오더블록 리테스트',
    tf: '5m',
    dslEntry: '(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))',
    dslExit: 'close < ob_bullish_bottom(20, 20, 50) * 0.997',
    takeProfitPct: '2.5',
    fixedStopLossPct: '2.0',
  },
  {
    name: 'autopilot-volume-spike',
    nameKo: '오토파일럿 거래량 급증',
    tf: '3m',
    dslEntry: '((volume > volume_ma(20) * 2) AND (mfi(14) > 55)) AND (close > vwap())',
    dslExit: 'close < vwap() * 0.997',
    takeProfitPct: '3.0',
    fixedStopLossPct: '2.0',
  },
  {
    name: 'autopilot-vwap-reversion',
    nameKo: '오토파일럿 VWAP 회귀',
    tf: '5m',
    dslEntry: '((close < bb_lower(20)) AND (rsi(14) < 30)) AND (close < vwap())',
    dslExit: 'close > vwap() * 1.003',
    takeProfitPct: '2.0',
    fixedStopLossPct: '2.0',
  },
];

// ── psql helper ──────────────────────────────────────────────────────────────
const FS = '|';
export function createPsqlClient(dockerContext, execFile = execFileSync) {
  const dockerArgs = ['--context', dockerContext, 'exec', '-i', 'magicjar-postgres', 'psql', '-U', 'magicjar', '-d', 'magicjar'];
  return {
    query(sql) {
      const out = execFile('docker', [...dockerArgs, '-t', '-A', `-F${FS}`, '-v', 'ON_ERROR_STOP=1'], {
        input: `${sql}\n`,
        encoding: 'utf8',
        stdio: ['pipe', 'pipe', 'pipe'],
      });
      return out
        .trim()
        .split('\n')
        .filter((line) => line.length > 0)
        .map((line) => line.split(FS));
    },
    exec(sql) {
      execFile('docker', [...dockerArgs, '-v', 'ON_ERROR_STOP=1'], {
        input: `${sql}\n`,
        encoding: 'utf8',
        stdio: ['pipe', 'ignore', 'pipe'],
      });
    },
  };
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

function validateUserId(raw) {
  if (typeof raw !== 'string' || !/^[0-9]+$/.test(raw)) throw new Error('--user-id must be a positive decimal PostgreSQL BIGINT');
  const value = BigInt(raw);
  if (value <= 0n || value > PG_BIGINT_MAX) throw new Error('--user-id must be a positive decimal PostgreSQL BIGINT');
  return value.toString();
}

export function parseArgs(argv) {
  let userId;
  let dockerContext;
  let dryRun = false;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--user-id') {
      if (userId !== undefined || i + 1 >= argv.length) throw new Error('exactly one --user-id is required');
      userId = argv[++i];
    } else if (arg === '--docker-context') {
      if (dockerContext !== undefined || i + 1 >= argv.length) throw new Error('exactly one --docker-context is required');
      dockerContext = argv[++i];
    } else if (arg === '--dry-run') {
      if (dryRun) throw new Error('--dry-run may be specified only once');
      dryRun = true;
    } else {
      throw new Error('unsupported argument');
    }
  }
  if (userId === undefined) throw new Error('exactly one --user-id is required');
  if (dockerContext === undefined) throw new Error('exactly one --docker-context is required');
  if (!/^[A-Za-z0-9._-]+$/.test(dockerContext)) throw new Error('--docker-context contains unsupported characters');
  return { userId: validateUserId(userId), dockerContext, dryRun };
}

function targetNamesSql() {
  return STRATEGIES.map((strategy) => `(${lit(strategy.name)})`).join(', ');
}

export function buildPreflightSql(rawUserId) {
  const userId = validateUserId(rawUserId);
  return `WITH target_names(name) AS (VALUES ${targetNamesSql()}), ` +
    `scoped AS (` +
    `SELECT s.name, s.status, s.allow_overnight FROM strategy s JOIN target_names t ON t.name = s.name ` +
    `WHERE s.user_id = ${userId} AND s.owner = ${lit(OWNER)}` +
    `) SELECT ` +
    `(SELECT count(*) FROM user_account WHERE user_id = ${lit(userId)}), ` +
    `(SELECT count(*) FROM user_kis_credential WHERE user_id = ${lit(userId)} AND account_type = 'REAL'), ` +
    `count(*), count(*) FILTER (WHERE status <> 'PAUSED'), ` +
    `count(*) - count(DISTINCT name), count(*) FILTER (WHERE allow_overnight) FROM scoped;`;
}

function insertSql(strategy, userId) {
  const complexity = estimateComplexity(strategy.dslEntry, strategy.dslExit);
  return `INSERT INTO strategy (` +
    `user_id, owner, name, name_ko, dsl_entry, dsl_exit, universe_market, tf, codes, intervals, ` +
    `take_profit_pct, fixed_stop_loss_pct, min_hold_bars, scale_in_config, partial_exits, entry_policy, ` +
    `ast_depth, node_count, indicator_call_count, estimated_eval_time_ms, complexity_class, ` +
    `origin, status, allow_overnight, vi_action_policy, created_at, updated_at` +
    `) SELECT ` +
    `${userId}, ${lit(OWNER)}, ${lit(strategy.name)}, ${lit(strategy.nameKo)}, ${lit(strategy.dslEntry)}, ${lit(strategy.dslExit)}, ` +
    `${lit(UNIVERSE_MARKET)}, ${lit(strategy.tf)}, ${jsonbArrayLit(LIQUIDITY_ANCHORS)}, ${jsonbArrayLit([strategy.tf])}, ` +
    `${strategy.takeProfitPct}, ${strategy.fixedStopLossPct}, ${MIN_HOLD_BARS}, NULL, NULL, NULL, ` +
    `${complexity.astDepth}, ${complexity.nodeCount}, ${complexity.indicatorCallCount}, ${complexity.estimatedEvalTimeMs}, ` +
    `${lit(complexity.complexityClass)}, ${lit(ORIGIN)}, 'PAUSED', false, ${lit(VI_ACTION_POLICY)}, now(), now() ` +
    `WHERE NOT EXISTS (` +
    `SELECT 1 FROM strategy WHERE user_id = ${userId} AND owner = ${lit(OWNER)} AND name = ${lit(strategy.name)}` +
    `);`;
}

export function buildTransactionalSeedSql(rawUserId) {
  const userId = validateUserId(rawUserId);
  const names = `ARRAY[${STRATEGIES.map((strategy) => lit(strategy.name)).join(', ')}]`;
  const prerequisites = `DO $seed$ BEGIN ` +
    `PERFORM 1 FROM user_account WHERE user_id = ${lit(userId)} FOR UPDATE; ` +
    `IF NOT FOUND THEN RAISE EXCEPTION 'seed user account prerequisite failed'; END IF; ` +
    `PERFORM 1 FROM user_kis_credential WHERE user_id = ${lit(userId)} AND account_type = 'REAL' FOR UPDATE; ` +
    `IF NOT FOUND THEN RAISE EXCEPTION 'seed REAL credential prerequisite failed'; END IF; ` +
    `IF EXISTS (SELECT 1 FROM strategy WHERE user_id = ${userId} AND owner = ${lit(OWNER)} AND name = ANY(${names}) ` +
    `GROUP BY name HAVING count(*) > 1) THEN RAISE EXCEPTION 'duplicate autopilot seed target'; END IF; ` +
    `IF EXISTS (SELECT 1 FROM strategy WHERE user_id = ${userId} AND owner = ${lit(OWNER)} AND name = ANY(${names}) ` +
    `AND status <> 'PAUSED') THEN RAISE EXCEPTION 'non-PAUSED autopilot seed target'; END IF; ` +
    `IF EXISTS (SELECT 1 FROM strategy WHERE user_id = ${userId} AND owner = ${lit(OWNER)} AND name = ANY(${names}) ` +
    `AND allow_overnight) THEN RAISE EXCEPTION 'overnight-enabled autopilot seed target'; END IF; ` +
    `END $seed$;`;
  const verify = `DO $verify$ BEGIN ` +
    `IF (SELECT count(*) FROM strategy WHERE user_id = ${userId} AND owner = ${lit(OWNER)} AND name = ANY(${names}) ` +
    `AND status = 'PAUSED' AND allow_overnight = false) <> ${STRATEGIES.length} ` +
    `THEN RAISE EXCEPTION 'autopilot seed postcondition failed'; END IF; END $verify$;`;
  return [
    'BEGIN;',
    `SET LOCAL lock_timeout = '5s';`,
    `SELECT pg_advisory_xact_lock(hashtextextended('magicjar-autopilot-seed-strategies', 0));`,
    `LOCK TABLE strategy IN SHARE ROW EXCLUSIVE MODE;`,
    prerequisites,
    ...STRATEGIES.map((strategy) => insertSql(strategy, userId)),
    verify,
    'COMMIT;',
  ].join('\n');
}

export function runSeed({ args, client, logger = console }) {
  const { userId, dockerContext, dryRun } = parseArgs(args);
  const db = client ?? createPsqlClient(dockerContext);
  let rows;
  try {
    rows = db.query(buildPreflightSql(userId));
  } catch {
    logger.error('[error] autopilot seed preflight failed');
    throw new Error('autopilot seed preflight failed');
  }
  if (rows.length !== 1 || rows[0].length !== 6) throw new Error('autopilot seed preflight returned an invalid shape');
  const [userCount, credentialCount, targetCount, nonPausedCount, duplicateCount, overnightCount] = rows[0].map(Number);
  if (userCount !== 1) throw new Error('exact user account prerequisite failed');
  if (credentialCount !== 1) throw new Error('exact REAL credential prerequisite failed');
  if (duplicateCount !== 0) throw new Error('duplicate autopilot seed target detected');
  if (nonPausedCount !== 0) throw new Error('non-PAUSED autopilot seed target detected');
  if (overnightCount !== 0) throw new Error('overnight-enabled autopilot seed target detected');
  if (!Number.isInteger(targetCount) || targetCount < 0 || targetCount > STRATEGIES.length) {
    throw new Error('autopilot seed target count is invalid');
  }

  const insertCount = STRATEGIES.length - targetCount;
  logger.log(`autopilot PAUSED seed existing=${targetCount} insert=${insertCount} ${dryRun ? '[DRY-RUN]' : ''}`);
  if (!dryRun) {
    try {
      db.exec(buildTransactionalSeedSql(userId));
    } catch {
      logger.error('[error] autopilot seed transaction failed');
      throw new Error('autopilot seed transaction failed');
    }
  }
  logger.log(dryRun ? 'dry-run complete: DB mutation 0' : 'seed complete: all targets remain PAUSED');
  return { userId, dockerContext, dryRun, existingCount: targetCount, insertCount };
}

function main() {
  try {
    runSeed({ args: process.argv.slice(2) });
  } catch (error) {
    console.error(`[error] ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
