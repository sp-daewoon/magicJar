#!/usr/bin/env node
// autopilot-snapshot.mjs — magicjar-autopilot 루프 시작 전 상태 스냅샷 (spec 2026-06-22 §2-0-D · Phase 0 Task 4)
//
// autopilot_state(안전 하니스 단일 권위 행) + owner=AUTOPILOT 전략/포지션 + 현재 git SHA 를 JSON 으로 캡처한다.
// 루프가 무엇을 망가뜨렸는지 되감을 기준점(롤백 앵커 v3.14.0 + DB 스냅샷)을 남기는 게 목적.
//
// 사용:
//   node scripts/autopilot-snapshot.mjs            # _workspace/autopilot/snapshots/<ts>.json 저장 + stdout 요약
//   node scripts/autopilot-snapshot.mjs --stdout   # 파일 저장 없이 JSON 만 stdout
//
// DB 접속: 기존 ops 스크립트 컨벤션(scripts/ops-daily-snapshot.sh)과 동일하게
//   `docker exec magicjar-postgres psql -U magicjar -d magicjar` 로 read-only 조회한다(별도 드라이버 의존 0).
//
// read-only — 본 스크립트는 어떤 DB mutation 도 하지 않는다.

import { execSync } from 'node:child_process';
import { writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const STDOUT_ONLY = process.argv.includes('--stdout');
const PSQL = 'docker exec magicjar-postgres psql -U magicjar -d magicjar -t -A -F$\'\\t\'';

// psql 한 쿼리 → 행 배열(탭 분리). 실패 시 빈 배열 + 경고(스냅샷은 best-effort).
function query(sql) {
  try {
    const out = execSync(`${PSQL} -c "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8' });
    return out
      .trim()
      .split('\n')
      .filter((l) => l.length > 0)
      .map((l) => l.split('\t'));
  } catch (e) {
    console.error(`[warn] 쿼리 실패(best-effort skip): ${sql.slice(0, 60)}... → ${e.message.split('\n')[0]}`);
    return [];
  }
}

function gitSha() {
  try {
    return execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

function gitDescribe() {
  try {
    return execSync('git describe --tags --always 2>/dev/null', { encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

// ── autopilot_state 단일 행 ──────────────────────────────────────────────────
const stateRows = query(
  "SELECT kill_active, pool_pct, per_trade_pct, max_positions, daily_loss_pct, cum_loss_pct, " +
    "cum_realized_loss, daily_realized_loss, lifetime_realized_loss, last_reset_date " +
    "FROM autopilot_state WHERE id = 1",
);
const state =
  stateRows.length > 0
    ? {
        killActive: stateRows[0][0] === 't',
        poolPct: stateRows[0][1],
        perTradePct: stateRows[0][2],
        maxPositions: Number(stateRows[0][3]),
        dailyLossPct: stateRows[0][4],
        cumLossPct: stateRows[0][5],
        cumRealizedLoss: stateRows[0][6],
        dailyRealizedLoss: stateRows[0][7],
        lifetimeRealizedLoss: stateRows[0][8],
        lastResetDate: stateRows[0][9],
      }
    : null;

// ── owner=AUTOPILOT 전략 ──────────────────────────────────────────────────────
const strategies = query(
  "SELECT id, name, status FROM strategy WHERE owner = 'AUTOPILOT' AND status <> 'DELETED' ORDER BY id",
).map(([id, name, status]) => ({ id: Number(id), name, status }));

// ── owner=AUTOPILOT 보유 포지션(quantity>0) ──────────────────────────────────
const positions = query(
  "SELECT sp.strategy_id, sp.code, sp.quantity, sp.avg_price, sp.realized_pnl " +
    "FROM strategy_position sp JOIN strategy s ON s.id = sp.strategy_id " +
    "WHERE s.owner = 'AUTOPILOT' AND sp.quantity > 0 ORDER BY sp.strategy_id, sp.code",
).map(([strategyId, code, quantity, avgPrice, realizedPnl]) => ({
  strategyId: Number(strategyId),
  code,
  quantity: Number(quantity),
  avgPrice,
  realizedPnl,
}));

// ── 최근 미해제 kill 저널(있으면) ────────────────────────────────────────────
const activeKill = query(
  "SELECT id, occurred_at, reason, lifetime_realized_loss FROM autopilot_kill_journal " +
    "WHERE resumed_at IS NULL ORDER BY occurred_at DESC LIMIT 1",
).map(([id, occurredAt, reason, lifetime]) => ({ id: Number(id), occurredAt, reason, lifetimeRealizedLoss: lifetime }));

const snapshot = {
  capturedAt: new Date().toISOString(),
  rollbackAnchor: 'v3.14.0',
  git: { sha: gitSha(), describe: gitDescribe() },
  autopilotState: state,
  autopilotStrategies: strategies,
  autopilotPositions: positions,
  activeKillJournal: activeKill[0] ?? null,
};

if (STDOUT_ONLY) {
  process.stdout.write(JSON.stringify(snapshot, null, 2) + '\n');
} else {
  const dir = join(process.cwd(), '_workspace', 'autopilot', 'snapshots');
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  const ts = snapshot.capturedAt.replace(/[:.]/g, '-');
  const file = join(dir, `${ts}.json`);
  writeFileSync(file, JSON.stringify(snapshot, null, 2) + '\n');
  console.log(`✓ autopilot 스냅샷 저장: ${file}`);
  console.log(
    `  git=${snapshot.git.describe} kill=${state?.killActive ?? 'no-row'} ` +
      `전략=${strategies.length}건 보유=${positions.length}건 lifetime손실=${state?.lifetimeRealizedLoss ?? 'n/a'}`,
  );
  if (activeKill[0]) {
    console.log(`  ⚠ 미해제 kill 저널 #${activeKill[0].id} — reason=${activeKill[0].reason}`);
  }
}
