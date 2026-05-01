#!/usr/bin/env node
// magicJar QA 디펙 순환 루프 CLI.
// _workspace/qa/issues/<id>/{card.md, scenario-{before|after}.py, report-{before|after}.md}
// 갱신 + pyworker /screenshot/scenario 호출.
//
// ADR 0011 (2026-05-01 개정) + ADR 0030 (오케스트레이터=architect 메타 규약).
// spec docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md
//
// 사용 예:
//   node scripts/qa-run.mjs new --symptom "차트 안 보여" --target /symbol/005930
//   node scripts/qa-run.mjs scenario --issue ISSUE-008 --spec ./scenario.py --attempt before
//   node scripts/qa-run.mjs scenario --issue ISSUE-008 --spec ./scenario.py --attempt after
//   node scripts/qa-run.mjs close --issue ISSUE-008 --pr 50
//   node scripts/qa-run.mjs wontfix --issue ISSUE-008 --reason "..."
//   node scripts/qa-run.mjs list

import {
  readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync, copyFileSync,
} from 'node:fs';
import { join, basename } from 'node:path';

const QA_ROOT = '_workspace/qa';
const ISSUES_DIR = `${QA_ROOT}/issues`;
const INDEX_FILE = `${QA_ROOT}/issues.md`;
const PYWORKER_URL = process.env.PYWORKER_URL || 'http://localhost:8000';

function nowIso() { return new Date().toISOString(); }

function ensureDirs() {
  if (!existsSync(ISSUES_DIR)) mkdirSync(ISSUES_DIR, { recursive: true });
}

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) { out[a.slice(2)] = argv[++i]; }
    else { out._.push(a); }
  }
  return out;
}

function parseFrontmatter(text) {
  const m = text.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!m) return { meta: {}, body: text };
  const meta = {};
  m[1].split('\n').forEach(line => {
    const idx = line.indexOf(':');
    if (idx > 0) {
      const k = line.slice(0, idx).trim();
      const v = line.slice(idx + 1).trim();
      meta[k] = (v === 'null' || v === '') ? null : v;
    }
  });
  return { meta, body: m[2] };
}

function serializeFrontmatter(meta, body) {
  const order = [
    'id', 'status', 'reportedAt', 'reportedBy', 'symptom',
    'target', 'relatedAdr', 'relatedPr', 'fixedAt',
  ];
  const lines = [];
  for (const k of order) {
    if (meta[k] !== undefined) lines.push(`${k}: ${meta[k] === null ? 'null' : meta[k]}`);
  }
  for (const k of Object.keys(meta)) {
    if (!order.includes(k)) lines.push(`${k}: ${meta[k] === null ? 'null' : meta[k]}`);
  }
  return `---\n${lines.join('\n')}\n---\n${body}`;
}

function nextIssueId() {
  ensureDirs();
  const existing = readdirSync(ISSUES_DIR).filter(f => f.startsWith('ISSUE-'));
  // legacy ISSUE-001~007은 issues.md 단일 파일로 보존, 신 양식은 ISSUE-008부터.
  let max = 7;
  for (const dir of existing) {
    const m = dir.match(/^ISSUE-(\d+)$/);
    if (m) max = Math.max(max, parseInt(m[1], 10));
  }
  return `ISSUE-${String(max + 1).padStart(3, '0')}`;
}

function issueDir(id) { return join(ISSUES_DIR, id); }
function cardPath(id) { return join(issueDir(id), 'card.md'); }

// ---- 명령들 ----

function cmdNew(args) {
  const symptom = args.symptom || (() => { throw new Error('--symptom 필수'); })();
  const target = args.target || (() => { throw new Error('--target 필수 (URL 또는 화면명)'); })();
  const adr = args.adr || '';
  const reportedBy = args.reportedBy || 'user';

  ensureDirs();
  const id = nextIssueId();
  const dir = issueDir(id);
  mkdirSync(dir, { recursive: true });

  const meta = {
    id,
    status: 'OPEN',
    reportedAt: nowIso(),
    reportedBy,
    symptom,
    target,
    relatedAdr: adr ? `[${adr}]` : '[]',
    relatedPr: null,
    fixedAt: null,
  };
  const body = `\n## 증상\n${symptom}\n\n## 재현 단계\n(architect가 시나리오 작성 시 채움)\n\n## before 캡처\n(scenario before 실행 후 채움)\n\n## after 캡처\n(scenario after 실행 후 채움)\n\n## 시나리오 코드 (인용)\n(scenario-before.py 인용)\n`;

  writeFileSync(cardPath(id), serializeFrontmatter(meta, body), 'utf-8');
  console.log(`✓ ${id} 채번 + 디렉토리 생성`);
  console.log(`  ${dir}/card.md`);
  console.log(`  → 다음: scripts/qa-run.mjs scenario --issue ${id} --spec <path> --attempt before`);
}

async function cmdScenario(args) {
  const id = args.issue || (() => { throw new Error('--issue 필수'); })();
  const specPath = args.spec || (() => { throw new Error('--spec 필수 (.py 경로)'); })();
  const attempt = args.attempt;
  if (!['before', 'after'].includes(attempt)) {
    throw new Error('--attempt 는 before|after');
  }
  const retry = parseInt(args.retry || '1', 10);
  const viewportW = parseInt(args.width || '1440', 10);
  const viewportH = parseInt(args.height || '900', 10);

  if (!existsSync(cardPath(id))) {
    throw new Error(`${id} 카드 없음. 먼저: qa-run.mjs new`);
  }
  const specCode = readFileSync(specPath, 'utf-8');

  // 1) pyworker 호출
  console.log(`→ POST ${PYWORKER_URL}/screenshot/scenario (issueId=${id}, attempt=${attempt})`);
  const resp = await fetch(`${PYWORKER_URL}/screenshot/scenario`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      specCode, issueId: id, attempt, retry,
      viewport: { width: viewportW, height: viewportH },
      requestedBy: 'qa-run.mjs',
    }),
  });
  if (!resp.ok) {
    const t = await resp.text();
    throw new Error(`pyworker ${resp.status}: ${t}`);
  }
  const result = await resp.json();

  // 2) scenario-{attempt}.py 보존
  const specDst = join(issueDir(id), `scenario-${attempt}.py`);
  copyFileSync(specPath, specDst);

  // 3) report-{attempt}.md 작성
  const reportPath = join(issueDir(id), `report-${attempt}.md`);
  const reportLines = [
    `# ${id} — ${attempt} 보고`,
    ``,
    `- run: \`${result.runTimestamp}\` · retry ${result.retry} · 총 ${result.totalDurationMs}ms`,
    `- 실패 단계: ${result.failedAtStep === null ? '없음 (모두 PASS)' : `step ${result.failedAtStep}`}`,
    ``,
    `## 단계별 결과`,
    ``,
    `| # | 단계 | 상태 | 시간 | URL |`,
    `|---|---|---|---|---|`,
    ...result.steps.map(s => {
      const status = s.status === 'pass' ? '✅ pass' : '❌ fail';
      return `| ${s.index} | ${s.name} | ${status} | ${s.durationMs}ms | [${basename(s.storageKey)}](${s.presignedUrl}) |`;
    }),
    ``,
  ];
  if (result.failedAtStep !== null) {
    const failed = result.steps[result.failedAtStep];
    reportLines.push(`## 실패 정보\n\n\`\`\`\n${failed.error}\n\`\`\`\n`);
  }
  writeFileSync(reportPath, reportLines.join('\n'), 'utf-8');

  // 4) card.md 갱신 — INVESTIGATING 진입 (before 시점에)
  const cardText = readFileSync(cardPath(id), 'utf-8');
  const { meta, body } = parseFrontmatter(cardText);
  if (attempt === 'before') meta.status = 'INVESTIGATING';
  writeFileSync(cardPath(id), serializeFrontmatter(meta, body), 'utf-8');

  // 5) 콘솔 출력 — 사용자가 즉시 시각 검토 가능하도록 URL 나열
  console.log(`✓ ${id} ${attempt} 보고 작성: ${reportPath}`);
  console.log(`  단계 ${result.steps.length}개${result.failedAtStep !== null ? ` (실패: step ${result.failedAtStep})` : ''}`);
  console.log(`  presigned URLs:`);
  result.steps.forEach(s => console.log(`    [${s.index}] ${s.presignedUrl}`));
}

function cmdClose(args) {
  const id = args.issue || (() => { throw new Error('--issue 필수'); })();
  const pr = args.pr || null;
  if (!existsSync(cardPath(id))) throw new Error(`${id} 카드 없음`);
  const text = readFileSync(cardPath(id), 'utf-8');
  const { meta, body } = parseFrontmatter(text);
  meta.status = 'FIXED';
  meta.fixedAt = nowIso();
  if (pr) meta.relatedPr = `#${String(pr).replace(/^#/, '')}`;
  writeFileSync(cardPath(id), serializeFrontmatter(meta, body), 'utf-8');
  refreshIndex();
  console.log(`✓ ${id} FIXED${pr ? ` (PR ${meta.relatedPr})` : ''}`);
}

function cmdWontfix(args) {
  const id = args.issue || (() => { throw new Error('--issue 필수'); })();
  const reason = args.reason || (() => { throw new Error('--reason 필수'); })();
  if (!existsSync(cardPath(id))) throw new Error(`${id} 카드 없음`);
  const text = readFileSync(cardPath(id), 'utf-8');
  const { meta, body } = parseFrontmatter(text);
  meta.status = 'WONTFIX';
  const newBody = body + `\n## WONTFIX 사유\n${nowIso()} — ${reason}\n`;
  writeFileSync(cardPath(id), serializeFrontmatter(meta, newBody), 'utf-8');
  refreshIndex();
  console.log(`✓ ${id} WONTFIX: ${reason}`);
}

function cmdList() {
  ensureDirs();
  const dirs = readdirSync(ISSUES_DIR).filter(d => d.startsWith('ISSUE-'));
  if (!dirs.length) { console.log('(이슈 디렉토리 없음)'); return; }
  for (const d of dirs.sort()) {
    if (!existsSync(cardPath(d))) continue;
    const { meta } = parseFrontmatter(readFileSync(cardPath(d), 'utf-8'));
    const sym = meta.symptom ? meta.symptom.slice(0, 60) : '';
    const icon = {
      OPEN: '🟡', INVESTIGATING: '🟠', FIXED: '✅',
      WONTFIX: '⚪', DEFERRED: '⏸️',
    }[meta.status] || '?';
    console.log(`${icon} ${meta.id}  [${meta.status}]  ${sym}`);
  }
}

function refreshIndex() {
  ensureDirs();
  const dirs = readdirSync(ISSUES_DIR).filter(d => d.startsWith('ISSUE-')).sort();
  const rows = [];
  for (const d of dirs) {
    if (!existsSync(cardPath(d))) continue;
    const { meta } = parseFrontmatter(readFileSync(cardPath(d), 'utf-8'));
    const sym = (meta.symptom || '').replace(/\|/g, '\\|').slice(0, 80);
    const pr = meta.relatedPr || '';
    rows.push(`| [${meta.id}](issues/${meta.id}/card.md) | ${meta.status} | ${sym} | ${pr} |`);
  }
  if (!existsSync(INDEX_FILE)) return; // task 12 전엔 skip
  const indexText = readFileSync(INDEX_FILE, 'utf-8');
  const marker = '<!-- AUTO-INDEX:START -->';
  const endMarker = '<!-- AUTO-INDEX:END -->';
  if (!indexText.includes(marker) || !indexText.includes(endMarker)) return;
  const before = indexText.split(marker)[0];
  const after = indexText.split(endMarker)[1] || '';
  const newIndex =
    `${before}${marker}\n\n| ID | 상태 | 증상 | PR |\n|---|---|---|---|\n` +
    `${rows.join('\n')}\n\n${endMarker}${after}`;
  writeFileSync(INDEX_FILE, newIndex, 'utf-8');
}

// ---- dispatch ----

const args = parseArgs(process.argv.slice(3));
const cmd = process.argv[2];
(async () => {
  try {
    switch (cmd) {
      case 'new':     cmdNew(args); break;
      case 'scenario': await cmdScenario(args); break;
      case 'close':   cmdClose(args); break;
      case 'wontfix': cmdWontfix(args); break;
      case 'list':    cmdList(); break;
      default:
        console.error('Usage: qa-run.mjs <new|scenario|close|wontfix|list> [args...]');
        process.exit(1);
    }
  } catch (e) {
    console.error(`✗ ${e.message}`);
    process.exit(1);
  }
})();
