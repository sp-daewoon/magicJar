# magicJar Master HTML Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `_workspace/master.html` 단일 파일 자동 생성 시스템 구축 — 12 카테고리 + banner를 본질 맞춤 입도로 한눈에 보이는 마스터 지식 인덱스.

**Architecture:** Node ESM single script (`scripts/master-index.mjs`, 의존 0) → 파일 system + git + gh CLI 데이터 추출 → 인라인 HTML+CSS+vanilla JS 단일 파일 렌더. `/master-index` slash command 수동 trigger + `scripts/release.sh` 자동 통합.

**Tech Stack:** Node ESM (`.mjs`) · Bash (slash command) · vanilla HTML/CSS/JS (의존 0) · gh CLI (release 추출) · git (timeline).

**Spec:** `_workspace/specs/2026-05-17-master-html-design.md` (307 lines, 14 §)

**TDD policy (magicJar 정합):** magicJar 룰북 4번 "코드 먼저, 테스트 마지막 일괄" 정합 — Phase 1·2 = 코드 + smoke verify, Phase 3 = unit test 일괄. 본 plan은 writing-plans 스킬 기본 TDD 흐름을 사용자 메모리 [[feedback_qa_on_demand]] + 룰북 우선으로 override.

**Phase 분리 (3 PR 직렬):**
- **Phase 1** — script 뼈대 + 핵심 6 카테고리 (A·B·C·H·E·F) + layout + slash command (8 task)
- **Phase 2** — 나머지 6 카테고리 (D·G·I·J·K·L) + banner + 인터랙션 (7 task)
- **Phase 3** — unit test 일괄 + release.sh 통합 + ADR 0079 발행 (5 task)

**Author:** orchestrator 모자 (`bot+orchestrator@magicjar.local`) — 운영 도구, sub-agent 영역 침범 0.

**Branch:** Phase별 `chore/master-html-phase-{1,2,3}` 직렬 (병렬 X — Phase 의존성: 2가 1 산출물 확장, 3이 2 산출물 검증).

---

## File Structure

| 파일 | 책임 | Phase |
|---|---|---|
| `scripts/master-index.mjs` | 메인 생성 script — 12 카테고리 + banner 추출·렌더 | 1·2 |
| `scripts/master-index.test.mjs` | unit test (fixture·idempotent) | 3 |
| `.claude/commands/master-index.md` | slash command 진입점 | 1 |
| `_workspace/master.html` | 생성물 (idempotent regen) | 1 첫 생성, 2·3 갱신 |
| `_workspace/master-banner.json` | banner 사용자 override 파일 (선택) | 2 |
| `scripts/release.sh` | 1 line 추가 (master-index 자동 호출) | 3 |
| `_workspace/arch/adr/0079-master-html-standardization.md` | ADR 0079 발행 | 3 |
| `_workspace/arch/adr/README.md` | §13 변경 이력 row 추가 | 3 |
| `CHANGELOG.md` | row 1건 추가 per Phase | 1·2·3 |

**핵심 boundary:**
- `master-index.mjs` 안에서 12 카테고리는 `categories[]` 배열 + 각 항목 `{ code, group, name, extract, render }` 인터페이스 — 카테고리 추가 시 코드 1 항목 추가만으로 확장
- HTML 렌더는 `renderShell(banner, categories)` 단일 함수 — banner + 그룹탭 + 하위탭 + 콘텐츠 wrapping
- 카테고리 데이터 추출은 각자 독립 fn — graceful fallback ("데이터 없음")

---

# Phase 1 — Script 뼈대 + 핵심 6 카테고리 + Layout + Slash Command

### Task 1.1: Script 뼈대 + 카테고리 register 패턴

**Files:**
- Create: `scripts/master-index.mjs`

- [ ] **Step 1: 뼈대 작성 (shebang + imports + main 구조)**

```js
#!/usr/bin/env node
// scripts/master-index.mjs
//
// magicJar 마스터 시각화 HTML 생성 — _workspace/master.html 단일 파일 출력.
// spec: _workspace/specs/2026-05-17-master-html-design.md
// 의존 0 (Node native만). gh CLI 1건 호출 (release 추출, 실패 시 fallback).
//
// 사용:
//   node scripts/master-index.mjs                    # 정상 생성
//   node scripts/master-index.mjs --dry-run          # 생성 안 하고 console 출력
//
// 호출:
//   1. /master-index slash command (.claude/commands/master-index.md)
//   2. scripts/release.sh 자동 통합 (sync-readme-releases.mjs 직후)

import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '..');
const OUT = join(ROOT, '_workspace/master.html');

// 카테고리 register — 코드·이름·그룹·추출·렌더 4 책임
// extract: () => data (graceful: 누락 시 { error: '데이터 없음' } 반환)
// render: (data) => HTML string (inner content only, shell은 renderShell이 wrap)
const categories = [];

function registerCategory(code, group, name, extract, render) {
  categories.push({ code, group, name, extract, render });
}

async function main() {
  const dryRun = process.argv.includes('--dry-run');

  // 카테고리 데이터 일괄 추출 (병렬)
  const extracted = await Promise.all(
    categories.map(async (c) => {
      try {
        return { ...c, data: await c.extract() };
      } catch (e) {
        console.warn(`[master-index] ${c.code} extract 실패: ${e.message}`);
        return { ...c, data: { error: '데이터 없음', detail: e.message } };
      }
    })
  );

  const banner = await extractBanner();  // Phase 2에서 구현
  const html = renderShell(banner, extracted);

  if (dryRun) {
    console.log(html.substring(0, 500) + '...');
    console.log(`[dry-run] HTML 크기: ${(html.length / 1024).toFixed(1)} KB`);
    return;
  }

  writeFileSync(OUT, html, 'utf-8');
  console.log(`✓ ${OUT} 생성 완료 (${(html.length / 1024).toFixed(1)} KB, ${extracted.length} 카테고리)`);

  // 무결성 self-check
  selfCheck(html);
}

// Phase 2에서 구현 — 일단 stub
async function extractBanner() {
  return { release: 'TBD', cycle: 'TBD', next: 'TBD', timeline: [] };
}

// Phase 1에서 구현 (Task 1.7)
function renderShell(banner, categories) {
  return '<!-- shell TBD -->';
}

function selfCheck(html) {
  const checks = [
    [html.includes('<title>magicJar Master Index'), 'title 누락'],
    [html.length < 1024 * 1024, `파일 크기 ${(html.length / 1024).toFixed(0)} KB > 1MB`],
    // 12 카테고리 + 2 그룹 = 14 탭 data-tab 존재 (Phase 1은 6 + 2 = 8, 임계 완화 후 Phase 2에서 강화)
    [(html.match(/data-tab=/g) || []).length >= 8, `data-tab 8개 미만`],
  ];
  const failed = checks.filter(([ok]) => !ok);
  if (failed.length > 0) {
    console.error('[master-index] 무결성 self-check 실패:');
    failed.forEach(([, msg]) => console.error(`  ✗ ${msg}`));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('[master-index] 치명 실패:', e);
  process.exit(1);
});
```

- [ ] **Step 2: 실행 권한 + smoke run**

```bash
chmod +x scripts/master-index.mjs
node scripts/master-index.mjs --dry-run
```

Expected: `[dry-run] HTML 크기: 0.0 KB` (renderShell stub이라 짧음). 에러 0건.

- [ ] **Step 3: commit**

```bash
git config user.name "magicjar-orchestrator"
git config user.email "bot+orchestrator@magicjar.local"
git checkout -b chore/master-html-phase-1
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index.mjs 뼈대 — 카테고리 register 패턴 + main"
```

---

### Task 1.2: 카테고리 A (운영 가이드) + B (정책 Living)

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: helper functions (단순 markdown 파싱)**

`scripts/master-index.mjs` 상단 import 직후 추가:

```js
// markdown helpers (정규식 기반, AI 호출 0)
function firstH2(content) {
  const m = content.match(/^##\s+(.+)$/m);
  return m ? m[1].trim() : null;
}
function firstParagraph(content, maxLen = 200) {
  // frontmatter + heading 모두 건너뛰고 첫 진짜 문장 단락
  const stripped = content
    .replace(/^---[\s\S]*?---\s*/, '')  // frontmatter
    .replace(/^#.+$/gm, '')              // headings
    .trim();
  const para = stripped.split(/\n\s*\n/)[0]?.replace(/\s+/g, ' ').trim() || '';
  return para.length > maxLen ? para.substring(0, maxLen) + '…' : para;
}
function fileMeta(path) {
  if (!existsSync(path)) return null;
  const st = statSync(path);
  const content = readFileSync(path, 'utf-8');
  return {
    path,
    size: st.size,
    lines: content.split('\n').length,
    mtime: st.mtime.toISOString().substring(0, 10),
    content,
  };
}
```

- [ ] **Step 2: 카테고리 A 등록**

`registerCategory` 호출부에 추가:

```js
registerCategory('A', '권위', '운영 가이드', async () => {
  const paths = ['README.md', 'CLAUDE.md', '_workspace/INDEX.md', 'docs/architecture/master-architecture.md'];
  return paths.map((p) => {
    const meta = fileMeta(join(ROOT, p));
    return meta
      ? { path: p, title: firstH2(meta.content) || p, lines: meta.lines, mtime: meta.mtime }
      : { path: p, error: '데이터 없음' };
  });
}, (data) => `
  <div class="cat-cards">
    ${data.map((f) => `
      <div class="card">
        <div class="card-title">📄 ${f.path}</div>
        <div class="card-sub">${f.title || ''}</div>
        <div class="card-meta">${f.lines || '-'} lines · 갱신 ${f.mtime || '-'}</div>
      </div>
    `).join('')}
  </div>
`);
```

- [ ] **Step 3: 카테고리 B 등록**

```js
registerCategory('B', '권위', '정책 Living', async () => {
  const dir = join(ROOT, 'docs/architecture/policies');
  if (!existsSync(dir)) return { error: '데이터 없음' };
  const baseRule = ['development-rules.md', 'code-convention.md', 'candle-data-policy-v2.md', 'external-api-policy.md', 'tracks-roadmap.md'];
  const policiesFiles = readdirSync(dir).filter((f) => f.endsWith('.md') && f !== 'README.md');
  const all = [
    ...policiesFiles.map((f) => ({ path: `docs/architecture/policies/${f}`, name: f.replace('.md', '') })),
    ...baseRule.map((f) => ({ path: `docs/architecture/${f}`, name: f.replace('.md', '') })),
  ];
  return all.map((p) => {
    const meta = fileMeta(join(ROOT, p.path));
    return meta
      ? { ...p, title: firstH2(meta.content) || p.name, summary: firstParagraph(meta.content, 120), mtime: meta.mtime }
      : { ...p, error: '데이터 없음' };
  });
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  return `
    <div class="cat-cards">
      ${data.map((p) => `
        <div class="card">
          <div class="card-title">📍 ${p.name}</div>
          <div class="card-sub">${p.summary || ''}</div>
          <div class="card-meta">📄 ${p.path} · 갱신 ${p.mtime || '-'}</div>
        </div>
      `).join('')}
    </div>
  `;
});
```

- [ ] **Step 4: smoke run**

```bash
node scripts/master-index.mjs --dry-run
```

Expected: 콘솔에 A·B 카테고리 데이터가 추출된 HTML fragment 일부 출력. 에러 0건.

- [ ] **Step 5: commit**

```bash
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 카테고리 A·B (운영 가이드·정책 Living) 추출+렌더"
```

---

### Task 1.3: 카테고리 C (ADR) — 74건 frontmatter parsing + sortable 표

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: ADR frontmatter parser**

helpers 추가:

```js
function parseAdrFile(content, filename) {
  // 파일명에서 번호 추출: 0078-internal-asset-extraction-strategy.md → 78
  const numMatch = filename.match(/^(\d{4})-/);
  const number = numMatch ? parseInt(numMatch[1], 10) : null;
  if (!number) return null;

  // 첫 # 헤더에서 title 추출
  const titleMatch = content.match(/^#\s+(.+)$/m);
  const title = titleMatch ? titleMatch[1].trim().replace(/^ADR\s+\d+\s*[—:-]\s*/, '') : filename;

  // status 추출 (frontmatter 또는 본문 ## Status 또는 헤더 라인의 Accepted/Proposed 등)
  let status = 'Unknown';
  const fmStatus = content.match(/^---[\s\S]*?status:\s*(\w+)[\s\S]*?---/);
  const bodyStatus = content.match(/\*\*Status\*\*:?\s*(\w+)/i) || content.match(/##\s+Status[\s\S]*?\n\s*(\w+)/);
  if (fmStatus) status = fmStatus[1];
  else if (bodyStatus) status = bodyStatus[1];
  else if (title.match(/superseded/i)) status = 'Superseded';
  else if (title.match(/proposed/i)) status = 'Proposed';
  else status = 'Accepted';

  // 날짜 추출 (frontmatter date 또는 본문 발행일)
  let date = '-';
  const fmDate = content.match(/^---[\s\S]*?date:\s*(\d{4}-\d{2}-\d{2})[\s\S]*?---/);
  const bodyDate = content.match(/(?:발행일|date)[:\s]+(\d{4}-\d{2}-\d{2})/i);
  if (fmDate) date = fmDate[1];
  else if (bodyDate) date = bodyDate[1];

  return { number, title, status, date, filename };
}
```

- [ ] **Step 2: 카테고리 C 등록**

```js
registerCategory('C', '권위', 'ADR', async () => {
  const dir = join(ROOT, '_workspace/arch/adr');
  if (!existsSync(dir)) return { error: '데이터 없음' };
  const files = readdirSync(dir).filter((f) => f.match(/^\d{4}-.*\.md$/));
  const adrs = files
    .map((f) => parseAdrFile(readFileSync(join(dir, f), 'utf-8'), f))
    .filter(Boolean)
    .sort((a, b) => b.number - a.number);
  return { recent: adrs.slice(0, 5), all: adrs, total: adrs.length };
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  const statusColor = (s) => ({ Accepted: '#4caf50', Proposed: '#ff9800', Superseded: '#9e9e9e' })[s] || '#888';
  return `
    <h3>최근 ADR 5건</h3>
    <div class="adr-recent">
      ${data.recent.map((a) => `
        <div class="adr-card" style="border-left:3px solid ${statusColor(a.status)}">
          <div class="adr-number">${String(a.number).padStart(4, '0')}</div>
          <div class="adr-title">${a.title}</div>
          <div class="adr-meta">${a.status} · ${a.date}</div>
        </div>
      `).join('')}
    </div>
    <h3>전체 ${data.total}건 (검색·정렬 가능)</h3>
    <input class="cat-search" data-cat="C" placeholder="ADR 검색…" />
    <table class="adr-table" data-sortable>
      <thead>
        <tr><th data-sort="number">번호</th><th data-sort="title">제목</th><th data-sort="status">status</th><th data-sort="date">날짜</th></tr>
      </thead>
      <tbody>
        ${data.all.map((a) => `
          <tr data-search="${(a.number + ' ' + a.title + ' ' + a.status).toLowerCase()}">
            <td>${String(a.number).padStart(4, '0')}</td>
            <td><a href="_workspace/arch/adr/${a.filename}">${a.title}</a></td>
            <td><span style="color:${statusColor(a.status)}">${a.status}</span></td>
            <td>${a.date}</td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;
});
```

- [ ] **Step 3: smoke run**

```bash
node scripts/master-index.mjs --dry-run | head -50
```

Expected: ADR 카테고리 데이터에 `0078`·`0076` 등 최근 ADR이 추출됨.

- [ ] **Step 4: commit**

```bash
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 카테고리 C (ADR) — 74건 frontmatter parsing + sortable 표"
```

---

### Task 1.4: 카테고리 H (backlog) — 57 § parsing + filter

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: backlog § parser**

```js
function parseBacklogSections(content) {
  // ^## §N. <제목> 패턴
  const sections = [];
  const lines = content.split('\n');
  let current = null;
  for (const line of lines) {
    const match = line.match(/^##\s+§(\d+)\.\s+(.+)$/);
    if (match) {
      if (current) sections.push(current);
      const title = match[2].trim();
      // status 추정: 제목에 "✅ 처리 완료" 또는 본문 Proposed 키워드
      let status = 'Proposed';
      if (title.match(/✅|처리 완료|완료/)) status = 'Done';
      current = {
        number: parseInt(match[1], 10),
        title: title.replace(/^[✅\s]+/, '').replace(/^처리 완료\s*[—-]\s*/, ''),
        status,
        body: '',
        date: null,
      };
    } else if (current) {
      current.body += line + '\n';
    }
  }
  if (current) sections.push(current);

  // 본문에서 Proposed YYYY-MM-DD 추출
  for (const s of sections) {
    const dateMatch = s.body.match(/Proposed\s+(\d{4}-\d{2}-\d{2})/);
    if (dateMatch) s.date = dateMatch[1];
  }

  return sections;
}
```

- [ ] **Step 2: 카테고리 H 등록**

```js
registerCategory('H', '시계열', 'backlog', async () => {
  const path = join(ROOT, '_workspace/backlog.md');
  if (!existsSync(path)) return { error: '데이터 없음' };
  const sections = parseBacklogSections(readFileSync(path, 'utf-8'));
  const proposed = sections.filter((s) => s.status === 'Proposed');
  const done = sections.filter((s) => s.status === 'Done');
  // 최근 3건 = 가장 최근 Proposed 날짜 기준
  const recent = proposed
    .filter((s) => s.date)
    .sort((a, b) => (b.date || '').localeCompare(a.date || ''))
    .slice(0, 3);
  return { total: sections.length, proposed, done, recent, all: sections };
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  return `
    <div class="backlog-filters">
      <button data-filter="all">전체 ${data.total}</button>
      <button data-filter="proposed">Proposed ${data.proposed.length}</button>
      <button data-filter="recent">최근 3건</button>
      <button data-filter="done">완료 ${data.done.length} (toggle)</button>
    </div>
    <input class="cat-search" data-cat="H" placeholder="backlog 검색…" />
    <div class="backlog-list">
      ${data.all.map((s) => {
        const isRecent = data.recent.some((r) => r.number === s.number);
        const color = s.status === 'Done' ? '#666' : isRecent ? '#f44336' : '#ff9800';
        return `
          <div class="backlog-item" data-status="${s.status}" data-recent="${isRecent}" data-search="${(s.number + ' ' + s.title).toLowerCase()}" style="border-left:4px solid ${color}">
            <div class="backlog-title">§${s.number}. ${s.title}${isRecent ? ' <span class="badge-new">🆕 신규</span>' : ''}</div>
            <div class="backlog-meta">${s.status}${s.date ? ' · ' + s.date : ''}</div>
          </div>
        `;
      }).join('')}
    </div>
  `;
});
```

- [ ] **Step 3: smoke run**

```bash
node scripts/master-index.mjs --dry-run | grep -E "(backlog|§5[56])" | head -5
```

Expected: §56 (OPSP8996) + §57 (Naver 429) 신규 highlight 포함.

- [ ] **Step 4: commit**

```bash
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 카테고리 H (backlog) — 57 § parsing + filter"
```

---

### Task 1.5: 카테고리 E (baseline) + F (devlog)

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: 카테고리 E·F 등록**

```js
registerCategory('E', '시계열', 'baseline', async () => {
  const dir = join(ROOT, '_workspace/baseline');
  if (!existsSync(dir)) return { error: '데이터 없음' };
  const versions = readdirSync(dir).filter((d) => d.match(/^v\d+\.\d+\.\d+$/));
  versions.sort((a, b) => b.localeCompare(a));
  return versions.map((v) => {
    const htmlDir = join(dir, v, 'html');
    if (!existsSync(htmlDir)) return { version: v, files: [] };
    const files = readdirSync(htmlDir).filter((f) => f.endsWith('.html'));
    return { version: v, files: files.sort() };
  });
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  return `
    <div class="cat-cards">
      ${data.map((b) => `
        <div class="card baseline-card">
          <div class="card-title">📊 ${b.version}</div>
          <div class="card-files">
            ${b.files.map((f) => `<a href="_workspace/baseline/${b.version}/html/${f}">${f.replace('.html', '')}</a>`).join(' · ')}
          </div>
        </div>
      `).join('')}
    </div>
  `;
});

registerCategory('F', '시계열', 'devlog', async () => {
  const dir = join(ROOT, '_workspace/devlog');
  if (!existsSync(dir)) return { error: '데이터 없음' };
  const files = readdirSync(dir)
    .filter((f) => f.match(/^\d{4}-\d{2}-\d{2}(-\d+)?\.html$/))
    .sort((a, b) => b.localeCompare(a));
  const indexHtml = existsSync(join(dir, 'index.html'));
  return { latest: files[0], rest: files.slice(1), indexHtml };
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  if (!data.latest) return `<div class="empty">devlog 없음</div>`;
  return `
    <div class="devlog-latest">
      <a href="_workspace/devlog/${data.latest}">
        <div class="card-title">📔 ${data.latest} <span class="badge">최신</span></div>
      </a>
    </div>
    <div class="devlog-rest">
      ${data.rest.map((f) => `<a href="_workspace/devlog/${f}" class="card-mini">📔 ${f.replace('.html', '')}</a>`).join('')}
      ${data.indexHtml ? '<a href="_workspace/devlog/index.html" class="card-mini">📚 전체 index</a>' : ''}
    </div>
  `;
});
```

- [ ] **Step 2: smoke run + commit**

```bash
node scripts/master-index.mjs --dry-run
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 카테고리 E·F (baseline·devlog)"
```

---

### Task 1.6: Layout (그룹탭 + 하위탭 + 콘텐츠) + 신규 디자인

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: `renderShell` 구현 + CSS + JS**

`renderShell` stub 교체:

```js
function renderShell(banner, cats) {
  const groups = ['권위', '시계열'];
  const groupColors = { '권위': { primary: '#2a4d3a', accent: '#4caf50' }, '시계열': { primary: '#4d3a2a', accent: '#ff9800' } };

  const catsByGroup = Object.fromEntries(groups.map((g) => [g, cats.filter((c) => c.group === g)]));
  const firstCat = cats[0]?.code || '';

  return `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>magicJar Master Index</title>
<style>
  body { background: #0f1419; color: #e0e0e0; font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 0; padding: 16px; font-size: 13px; line-height: 1.5; }
  h1 { margin: 0 0 12px 0; font-size: 20px; color: #fff; }
  .banner { background: linear-gradient(135deg, #1a1a2e, #16213e); padding: 14px; border-radius: 6px; margin-bottom: 16px; }
  .banner-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 10px; }
  .banner-card { background: #0f3460; padding: 10px; border-radius: 4px; }
  .banner-card.release { border-left: 3px solid ${groupColors['권위'].accent}; }
  .banner-card.cycle { border-left: 3px solid ${groupColors['시계열'].accent}; }
  .banner-card.next { border-left: 3px solid #9c27b0; }
  .banner-card .label { color: #888; font-size: 10px; text-transform: uppercase; }
  .banner-card .value { font-weight: bold; font-size: 15px; margin: 2px 0; color: #fff; }
  .banner-card .sub { color: #aaa; font-size: 11px; }
  .banner-timeline { background: #0f1a2e; padding: 10px; border-radius: 4px; border: 1px solid #333; font-size: 11px; }
  .group-tabs { display: flex; gap: 4px; margin-bottom: 8px; }
  .group-tab { background: #1a1a1a; padding: 8px 16px; border-radius: 4px 4px 0 0; cursor: pointer; font-weight: bold; }
  .group-tab.active[data-group="권위"] { background: ${groupColors['권위'].primary}; color: #fff; }
  .group-tab.active[data-group="시계열"] { background: ${groupColors['시계열'].primary}; color: #fff; }
  .sub-tabs { display: flex; gap: 2px; margin-bottom: 12px; background: #1a1a1a; padding: 6px; border-radius: 0 4px 4px 4px; }
  .sub-tab { padding: 6px 12px; cursor: pointer; border-radius: 3px; font-size: 12px; color: #aaa; }
  .sub-tab.active[data-group="권위"] { background: ${groupColors['권위'].primary}; color: #fff; }
  .sub-tab.active[data-group="시계열"] { background: ${groupColors['시계열'].primary}; color: #fff; }
  .cat-content { background: #1a1a1a; padding: 16px; border-radius: 4px; min-height: 400px; }
  .cat-content[data-active="false"] { display: none; }
  .cat-cards { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
  .card { background: #2a2a2a; padding: 12px; border-radius: 3px; }
  .card-title { font-weight: bold; margin-bottom: 4px; }
  .card-sub { color: #aaa; font-size: 11px; }
  .card-meta { color: #888; font-size: 10px; margin-top: 4px; }
  .empty { color: #666; text-align: center; padding: 40px; }
  .cat-search { width: 100%; background: #0f0f0f; border: 1px solid #333; color: #fff; padding: 6px 10px; margin: 8px 0; border-radius: 3px; }
  table { width: 100%; border-collapse: collapse; font-size: 11px; }
  th, td { padding: 6px; text-align: left; border-bottom: 1px solid #333; }
  th { background: #0f0f0f; cursor: pointer; }
  a { color: #4fc3f7; text-decoration: none; }
  a:hover { text-decoration: underline; }
  .adr-recent { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; margin-bottom: 14px; }
  .adr-card { background: #1f3a2c; padding: 8px; border-radius: 3px; }
  .adr-number { font-weight: bold; font-size: 14px; }
  .adr-title { color: #aaa; font-size: 10px; margin: 4px 0; }
  .adr-meta { color: #666; font-size: 9px; }
  .backlog-filters { display: flex; gap: 6px; margin-bottom: 8px; }
  .backlog-filters button { background: #2a2a2a; border: 1px solid #444; color: #ccc; padding: 6px 12px; border-radius: 3px; cursor: pointer; font-size: 11px; }
  .backlog-filters button.active { background: ${groupColors['시계열'].primary}; border-color: ${groupColors['시계열'].accent}; }
  .backlog-list { max-height: 600px; overflow-y: auto; }
  .backlog-item { background: #3a2c1f; padding: 10px; border-radius: 3px; margin-bottom: 6px; }
  .backlog-item[data-hidden="true"] { display: none; }
  .backlog-title { font-weight: bold; font-size: 12px; }
  .backlog-meta { color: #aaa; font-size: 10px; margin-top: 2px; }
  .badge-new { background: #f44336; padding: 2px 6px; border-radius: 3px; font-size: 9px; }
  .badge { background: #ff9800; padding: 2px 6px; border-radius: 3px; font-size: 9px; color: #000; }
  .devlog-latest { background: #3a2c1f; padding: 14px; border-radius: 3px; border-left: 4px solid #ff9800; margin-bottom: 8px; }
  .devlog-rest { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px; }
  .card-mini { background: #3a2c1f; padding: 8px; border-radius: 3px; font-size: 11px; text-align: center; }
  .card-files a { font-size: 10px; color: #4fc3f7; }
</style>
</head>
<body>
<h1>🪄 magicJar Master Index</h1>
<div class="banner">
  <div class="banner-cards">
    <div class="banner-card release">
      <div class="label">현재 Release</div>
      <div class="value">${banner.release}</div>
      <div class="sub">${banner.releaseDate || ''}</div>
    </div>
    <div class="banner-card cycle">
      <div class="label">현재 사이클 단계</div>
      <div class="value">${banner.cycle}</div>
      <div class="sub">${banner.cycleSub || ''}</div>
    </div>
    <div class="banner-card next">
      <div class="label">다음 마일스톤</div>
      <div class="value">${banner.next}</div>
      <div class="sub">${banner.nextSub || ''}</div>
    </div>
  </div>
  <div class="banner-timeline">
    <div class="label">📍 이번 작업 사이클 timeline</div>
    <div>${(banner.timeline || []).join(' → ')}</div>
  </div>
</div>

<div class="group-tabs">
  ${groups.map((g, i) => `<div class="group-tab ${i === 0 ? 'active' : ''}" data-group="${g}" data-tab="group-${g}">${g === '권위' ? '📐' : '🕐'} ${g}</div>`).join('')}
</div>

${groups.map((g, i) => `
  <div class="sub-tabs" data-group-content="${g}" ${i === 0 ? '' : 'style="display:none"'}>
    ${catsByGroup[g].map((c, j) => `<div class="sub-tab ${j === 0 ? 'active' : ''}" data-group="${g}" data-cat="${c.code}" data-tab="cat-${c.code}">${c.code} ${c.name}</div>`).join('')}
  </div>
`).join('')}

${cats.map((c, idx) => `
  <div class="cat-content" data-cat="${c.code}" data-active="${idx === 0 ? 'true' : 'false'}">
    ${c.render(c.data)}
  </div>
`).join('')}

<script>
// 그룹탭 전환
document.querySelectorAll('.group-tab').forEach((t) => {
  t.addEventListener('click', () => {
    const g = t.dataset.group;
    document.querySelectorAll('.group-tab').forEach((x) => x.classList.toggle('active', x === t));
    document.querySelectorAll('[data-group-content]').forEach((c) => {
      c.style.display = c.dataset.groupContent === g ? '' : 'none';
    });
    // 그룹 내 첫 카테고리 활성화
    const firstCat = document.querySelector(\`.sub-tab[data-group="\${g}"]\`);
    if (firstCat) firstCat.click();
    location.hash = '#' + g;
  });
});

// 하위탭 전환
document.querySelectorAll('.sub-tab').forEach((t) => {
  t.addEventListener('click', () => {
    const g = t.dataset.group;
    document.querySelectorAll(\`.sub-tab[data-group="\${g}"]\`).forEach((x) => x.classList.toggle('active', x === t));
    document.querySelectorAll('.cat-content').forEach((c) => {
      c.dataset.active = c.dataset.cat === t.dataset.cat ? 'true' : 'false';
    });
    location.hash = '#' + g + '/' + t.dataset.cat;
  });
});

// URL hash 복원
if (location.hash) {
  const [g, cat] = location.hash.substring(1).split('/');
  const gt = document.querySelector(\`.group-tab[data-group="\${g}"]\`);
  if (gt) gt.click();
  if (cat) {
    const st = document.querySelector(\`.sub-tab[data-cat="\${cat}"]\`);
    if (st) st.click();
  }
}

// 검색 (Phase 2에서 sort 추가)
document.querySelectorAll('.cat-search').forEach((input) => {
  input.addEventListener('input', (e) => {
    const q = e.target.value.toLowerCase();
    const cat = e.target.dataset.cat;
    const items = document.querySelectorAll(\`.cat-content[data-cat="\${cat}"] [data-search]\`);
    items.forEach((it) => {
      it.style.display = it.dataset.search.includes(q) ? '' : 'none';
    });
  });
});
</script>
</body>
</html>`;
}
```

- [ ] **Step 2: smoke run + 실제 파일 생성 확인**

```bash
node scripts/master-index.mjs
ls -la _workspace/master.html
echo "--- title + tab 개수 ---"
grep -c "data-tab=" _workspace/master.html
```

Expected: master.html 생성, data-tab 8개 이상 (Phase 1 6 카테고리 + 2 그룹).

- [ ] **Step 3: 브라우저 열어 확인 (수동)**

```bash
open _workspace/master.html
```

확인 사항:
- banner 영역 (TBD 값 표시 — Phase 2에서 실제 값)
- 권위 그룹탭 active → A·B·C·H·E·F 6 하위탭 표시
- 하위탭 클릭 시 콘텐츠 전환
- URL hash 변경

- [ ] **Step 4: commit**

```bash
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index renderShell — 그룹탭+하위탭+다크 디자인 (그린/앰버 accent)"
```

---

### Task 1.7: Slash command 생성

**Files:**
- Create: `.claude/commands/master-index.md`

- [ ] **Step 1: slash command 파일 생성**

```markdown
---
description: magicJar 마스터 HTML 갱신 — `_workspace/master.html` 재생성. 사용자 수동 trigger.
---

# /master-index

`scripts/master-index.mjs`를 실행하여 `_workspace/master.html`을 재생성합니다.

## 진행 순서

1. `node scripts/master-index.mjs` 실행
2. exit code 확인 (0이면 GREEN, 1이면 self-check 실패 보고)
3. `git status _workspace/master.html`로 변경 사항 확인
4. 변경 있으면 사용자에게 보고 (파일 크기·갱신 시각·diff 줄 수)
5. 변경 commit 여부는 사용자 결정 (자동 commit X)

## 정합 사항

- spec: `_workspace/specs/2026-05-17-master-html-design.md`
- AI 호출 0 LOCK
- 외부 호출 0 (gh CLI 1건은 graceful, 실패 시 fallback)
```

- [ ] **Step 2: commit**

```bash
git add .claude/commands/master-index.md
git commit -m "feat(meta): /master-index slash command 신설"
```

---

### Task 1.8: Phase 1 통합 smoke + CHANGELOG + PR

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: CHANGELOG row prepend**

`CHANGELOG.md` 표 헤더 직후 (line 4 다음, 기존 row 위에) 추가:

```markdown
<!-- anchor-master-html-phase-1 -->
| 2026-05-17 | **feat(meta): master HTML Phase 1 — 뼈대 + 핵심 6 카테고리 + layout + slash command** — `scripts/master-index.mjs` 신규 (Node ESM · 의존 0 · 카테고리 register 패턴 · graceful fallback) + `_workspace/master.html` 첫 생성 (다크 + 그린/앰버 디자인 · 그룹탭 2 + 하위탭 6) + 카테고리 A(가이드) · B(정책 Living) · C(ADR 74건 sortable 표) · H(backlog 57 § filter) · E(baseline 7 minor) · F(devlog 일자) 6건 추출·렌더 + `.claude/commands/master-index.md` slash command 신설. Phase 2 banner + 나머지 6 카테고리 + 인터랙션, Phase 3 test + release.sh 통합 + ADR 0079 후속. spec: `_workspace/specs/2026-05-17-master-html-design.md` (Q1~Q8 사용자 결정 8회 종합). AI 호출 0 LOCK + 외부 의존 0 + KIS 호출 0. (orchestrator) | scripts · _workspace · .claude/commands | brainstorming 흐름 사용자 자율 위임 + visual companion 결정 검증 후 Phase 1 착수 |
```

- [ ] **Step 2: 통합 smoke**

```bash
node scripts/master-index.mjs
# exit 0 + master.html 생성 + 파일 크기 < 1MB + data-tab 8 이상 검증
```

- [ ] **Step 3: push + PR**

```bash
git add CHANGELOG.md
git commit -m "chore(release): CHANGELOG row — Phase 1 master HTML"

git push -u origin chore/master-html-phase-1

gh pr create --title "feat(meta): master HTML Phase 1 — 뼈대 + 핵심 6 카테고리 + slash command" --base main --body "$(cat <<'EOF'
## Summary

magicJar 마스터 시각화 HTML Phase 1 — script 뼈대 + 핵심 6 카테고리 (A·B·C·H·E·F) + layout + slash command.

## 변경 내용

- \`scripts/master-index.mjs\` 신규 (Node ESM, 의존 0, 카테고리 register 패턴)
- \`_workspace/master.html\` 첫 생성 (다크 + 그린/앰버 신규 디자인)
- 카테고리 A(가이드) · B(정책) · C(ADR sortable) · H(backlog filter) · E(baseline) · F(devlog)
- \`.claude/commands/master-index.md\` slash command
- CHANGELOG row 1건

## 후속

- Phase 2 — 나머지 6 카테고리 + banner + 인터랙션
- Phase 3 — unit test + release.sh 통합 + ADR 0079

## Test plan

- [x] node scripts/master-index.mjs exit 0
- [x] master.html 생성 + 파일 크기 < 1MB
- [x] data-tab 8개 이상 존재
- [x] gradle check GREEN (pre-push)

spec: \`_workspace/specs/2026-05-17-master-html-design.md\`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

# Phase 2 — 나머지 6 카테고리 + Banner + 인터랙션

### Task 2.1: 카테고리 D (spec)

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: 브랜치 + main rebase**

```bash
git checkout main
git pull --ff-only origin main
git checkout -b chore/master-html-phase-2
```

- [ ] **Step 2: 카테고리 D 등록**

```js
registerCategory('D', '권위', 'spec/plan', async () => {
  const dir = join(ROOT, '_workspace/specs');
  if (!existsSync(dir)) return { error: '데이터 없음' };
  const files = readdirSync(dir).filter((f) => f.endsWith('.md'));
  const specs = files.map((f) => {
    const content = readFileSync(join(dir, f), 'utf-8');
    const titleMatch = content.match(/^#\s+(.+)$/m);
    const prMatch = f.match(/pr-(\d+)/);
    const dateMatch = f.match(/^(\d{4}-\d{2}-\d{2})/);
    return {
      filename: f,
      title: titleMatch ? titleMatch[1].trim() : f,
      pr: prMatch ? parseInt(prMatch[1], 10) : null,
      date: dateMatch ? dateMatch[1] : null,
    };
  });
  specs.sort((a, b) => (b.pr || 0) - (a.pr || 0) || (b.date || '').localeCompare(a.date || ''));
  return { recent: specs.slice(0, 10), all: specs, total: specs.length };
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  return `
    <h3>최근 spec 10건 / 전체 ${data.total}건</h3>
    <input class="cat-search" data-cat="D" placeholder="spec 검색…" />
    <div class="spec-list">
      ${data.all.map((s) => `
        <div class="card" data-search="${(s.filename + ' ' + s.title).toLowerCase()}">
          <div class="card-title">${s.pr ? 'PR #' + s.pr : '📄'} ${s.title}</div>
          <div class="card-meta">📄 _workspace/specs/${s.filename} ${s.date ? '· ' + s.date : ''}</div>
        </div>
      `).join('')}
    </div>
  `;
});
```

- [ ] **Step 3: smoke + commit**

```bash
node scripts/master-index.mjs
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 카테고리 D (spec/plan)"
```

---

### Task 2.2: 카테고리 G (release) — gh CLI + CHANGELOG fallback

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: 카테고리 G 등록**

```js
registerCategory('G', '시계열', 'release', async () => {
  // gh CLI 시도
  let releases = [];
  try {
    const output = execSync('gh release list --limit 30 --json tagName,publishedAt,name', { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
    releases = JSON.parse(output).map((r) => ({
      tag: r.tagName,
      date: r.publishedAt ? r.publishedAt.substring(0, 10) : '-',
      title: r.name || r.tagName,
      url: `https://github.com/sp-daewoon/magicJar/releases/tag/${r.tagName}`,
    }));
  } catch (e) {
    // fallback: git tag
    const tags = execSync('git tag -l "v*" --sort=-version:refname', { encoding: 'utf-8' }).trim().split('\n').slice(0, 30);
    releases = tags.map((t) => ({ tag: t, date: '-', title: t, url: null }));
  }
  return { recent: releases.slice(0, 5), all: releases, total: releases.length };
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  return `
    <h3>최근 5건 highlight</h3>
    <div class="adr-recent">
      ${data.recent.map((r) => `
        <div class="adr-card" style="border-left:3px solid #ff9800">
          <div class="adr-number">${r.tag}</div>
          <div class="adr-title">${r.title}</div>
          <div class="adr-meta">${r.date}</div>
        </div>
      `).join('')}
    </div>
    <h3>전체 ${data.total}건</h3>
    <table>
      <thead><tr><th>tag</th><th>날짜</th><th>title</th></tr></thead>
      <tbody>
        ${data.all.map((r) => `
          <tr><td>${r.url ? `<a href="${r.url}">${r.tag}</a>` : r.tag}</td><td>${r.date}</td><td>${r.title}</td></tr>
        `).join('')}
      </tbody>
    </table>
  `;
});
```

- [ ] **Step 2: smoke + commit**

```bash
node scripts/master-index.mjs
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 카테고리 G (release) — gh CLI + git tag fallback"
```

---

### Task 2.3: 카테고리 I·J·K·L (task·catalog·harness·dashboard)

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: 카테고리 I 등록 (task)**

```js
registerCategory('I', '시계열', 'task', async () => {
  const dir = join(ROOT, '_workspace/tasks/done');
  if (!existsSync(dir)) return { error: '데이터 없음' };
  const files = readdirSync(dir).filter((f) => f.match(/^T-\d{8}-\d{4}-.*\.md$/));
  const tasks = files.map((f) => {
    const content = readFileSync(join(dir, f), 'utf-8');
    const statusM = content.match(/^status:\s*(\w+)/m);
    const agentM = content.match(/^agent:\s*([\w-]+)/m);
    const prM = content.match(/^pr:\s*#?(\d+)/m);
    return { filename: f, status: statusM ? statusM[1] : 'Unknown', agent: agentM ? agentM[1] : '-', pr: prM ? prM[1] : null };
  });
  const byStatus = tasks.reduce((acc, t) => ((acc[t.status] ||= []).push(t), acc), {});
  return { total: tasks.length, byStatus, recent: tasks.sort((a, b) => b.filename.localeCompare(a.filename)).slice(0, 10) };
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  return `
    <h3>전체 ${data.total}건 · status별 분포</h3>
    <div class="cat-cards">
      ${Object.entries(data.byStatus).map(([s, ts]) => `
        <div class="card"><div class="card-title">${s}</div><div class="card-sub">${ts.length}건</div></div>
      `).join('')}
    </div>
    <h3>최근 10건</h3>
    <div class="spec-list">
      ${data.recent.map((t) => `
        <div class="card">
          <div class="card-title">${t.filename}</div>
          <div class="card-meta">${t.status} · ${t.agent} ${t.pr ? '· PR #' + t.pr : ''}</div>
        </div>
      `).join('')}
    </div>
  `;
});
```

- [ ] **Step 2: 카테고리 J 등록 (카탈로그)**

```js
registerCategory('J', '권위', '카탈로그', async () => {
  const items = [
    { name: 'indicator-catalog', path: '_workspace/strategy/indicator-catalog.md' },
    { name: 'pattern-catalog', path: '_workspace/strategy/pattern-catalog.md' },
    { name: 'prompt-catalog', path: '_workspace/ai/prompt-catalog.md' },
  ];
  return items.map((i) => {
    const meta = fileMeta(join(ROOT, i.path));
    return meta
      ? { ...i, title: firstH2(meta.content) || i.name, lines: meta.lines, mtime: meta.mtime }
      : { ...i, error: '데이터 없음' };
  });
}, (data) => `
  <div class="cat-cards">
    ${data.map((c) => `
      <div class="card">
        <div class="card-title">📚 ${c.name}</div>
        <div class="card-sub">${c.title || c.error}</div>
        <div class="card-meta">${c.lines || '-'} lines · 갱신 ${c.mtime || '-'}</div>
      </div>
    `).join('')}
  </div>
`);
```

- [ ] **Step 3: 카테고리 K 등록 (하네스)**

```js
registerCategory('K', '권위', '하네스', async () => {
  const agentsDir = join(ROOT, '.claude/agents');
  const skillsDir = join(ROOT, '.claude/skills');
  const commandsDir = join(ROOT, '.claude/commands');
  const agents = existsSync(agentsDir) ? readdirSync(agentsDir).filter((f) => f.endsWith('.md')).map((f) => ({ name: f.replace('.md', ''), path: `.claude/agents/${f}` })) : [];
  const skills = existsSync(skillsDir) ? readdirSync(skillsDir).filter((d) => statSync(join(skillsDir, d)).isDirectory()).map((d) => ({ name: d, path: `.claude/skills/${d}/SKILL.md` })) : [];
  const commands = existsSync(commandsDir) ? readdirSync(commandsDir).filter((f) => f.endsWith('.md')).map((f) => ({ name: f.replace('.md', ''), path: `.claude/commands/${f}` })) : [];
  return { agents, skills, commands };
}, (data) => `
  <h3>에이전트 ${data.agents.length}명</h3>
  <div class="cat-cards">
    ${data.agents.map((a) => `<div class="card"><div class="card-title">🤖 ${a.name}</div><div class="card-meta">📄 ${a.path}</div></div>`).join('')}
  </div>
  <h3>SKILL ${data.skills.length}개</h3>
  <div class="cat-cards">
    ${data.skills.map((s) => `<div class="card"><div class="card-title">🛠 ${s.name}</div><div class="card-meta">📄 ${s.path}</div></div>`).join('')}
  </div>
  <h3>Slash Commands ${data.commands.length}개</h3>
  <div class="cat-cards">
    ${data.commands.map((c) => `<div class="card"><div class="card-title">/ ${c.name}</div><div class="card-meta">📄 ${c.path}</div></div>`).join('')}
  </div>
  <p><a href="_workspace/arch/agent-charter.md">📋 운영 헌법 (agent-charter.md)</a></p>
`);
```

- [ ] **Step 4: 카테고리 L 등록 (dashboard link)**

```js
registerCategory('L', '시계열', 'dashboard', async () => {
  const path = join(ROOT, '_workspace/dashboard.html');
  if (!existsSync(path)) return { error: '데이터 없음' };
  const st = statSync(path);
  return { exists: true, mtime: st.mtime.toISOString().substring(0, 10), size: (st.size / 1024).toFixed(1) };
}, (data) => {
  if (data.error) return `<div class="empty">${data.error}</div>`;
  return `
    <div class="card" style="text-align:center;padding:30px">
      <div class="card-title" style="font-size:18px">📊 운영 상태 Dashboard v2</div>
      <div class="card-sub">Phase · Task · PR · commit · QA · 에이전트 실시간 운영 상태</div>
      <div style="margin:16px 0"><a href="_workspace/dashboard.html" style="background:#0f3460;padding:10px 20px;border-radius:4px;color:#fff">→ Dashboard 열기</a></div>
      <div class="card-meta">${data.size} KB · 마지막 갱신 ${data.mtime}</div>
    </div>
  `;
});
```

- [ ] **Step 5: smoke + commit**

```bash
node scripts/master-index.mjs
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 카테고리 I·J·K·L (task·catalog·harness·dashboard)"
```

---

### Task 2.4: Banner (자동 추출 + json override)

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: extractBanner 실제 구현**

기존 stub 교체:

```js
async function extractBanner() {
  // 1. 사용자 override 파일 우선
  const overridePath = join(ROOT, '_workspace/master-banner.json');
  let override = {};
  if (existsSync(overridePath)) {
    try { override = JSON.parse(readFileSync(overridePath, 'utf-8')); } catch (e) { console.warn('[banner] override 파싱 실패'); }
  }

  // 2. 자동 추출
  let release = override.release, releaseDate = override.releaseDate;
  try {
    if (!release) {
      const tag = execSync('git tag -l "v*" --sort=-version:refname', { encoding: 'utf-8' }).trim().split('\n')[0];
      release = tag;
      const dateStr = execSync(`git log -1 --format=%cI ${tag}`, { encoding: 'utf-8' }).trim();
      releaseDate = dateStr.substring(0, 10);
    }
  } catch (e) { release = release || 'unknown'; }

  // 3. 사이클 단계 — backlog 마지막 Proposed § + recent commit subject 분석
  let cycle = override.cycle, cycleSub = override.cycleSub;
  if (!cycle) {
    const backlogPath = join(ROOT, '_workspace/backlog.md');
    const sections = existsSync(backlogPath) ? parseBacklogSections(readFileSync(backlogPath, 'utf-8')) : [];
    const recentProposed = sections.filter((s) => s.status === 'Proposed' && s.date).sort((a, b) => (b.date || '').localeCompare(a.date || ''))[0];
    cycle = recentProposed ? `진행: §${recentProposed.number} ${recentProposed.title.substring(0, 30)}…` : '대기 중';
    cycleSub = recentProposed?.date ? `최근 §추가 ${recentProposed.date}` : '';
  }

  // 4. 다음 마일스톤
  let next = override.next || 'W4 진입 (T-AI + T-MCP) 또는 hotfix';
  let nextSub = override.nextSub || 'tracks-roadmap §1 + backlog Proposed 기반 추정';

  // 5. 이번 작업 timeline
  let timeline = override.timeline;
  if (!timeline) {
    try {
      // 마지막 release tag 이후 commit subject 추출
      const since = execSync(`git log -1 --format=%cI ${release}`, { encoding: 'utf-8' }).trim();
      const log = execSync(`git log --since="${since}" --format="%s" --reverse`, { encoding: 'utf-8' }).trim();
      timeline = log ? log.split('\n').slice(0, 6).map((s) => s.substring(0, 50)) : [`${release} cut 이후 변경 없음`];
    } catch (e) { timeline = ['timeline 추출 실패']; }
  }

  return { release, releaseDate, cycle, cycleSub, next, nextSub, timeline };
}
```

- [ ] **Step 2: smoke + 결과 확인**

```bash
node scripts/master-index.mjs
open _workspace/master.html
# banner에 v3.6.10 + 사이클 + 다음 + timeline 표시 확인
```

- [ ] **Step 3: commit**

```bash
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index banner 자동 추출 + master-banner.json override"
```

---

### Task 2.5: 인터랙션 보강 (sort + backlog filter)

**Files:**
- Modify: `scripts/master-index.mjs`

- [ ] **Step 1: renderShell 내 `<script>` 안에 sort + filter 추가**

기존 `<script>` 마지막에 추가:

```js
// 표 정렬 (data-sortable)
document.querySelectorAll('table[data-sortable] th[data-sort]').forEach((th) => {
  th.addEventListener('click', () => {
    const table = th.closest('table');
    const tbody = table.querySelector('tbody');
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const idx = Array.from(th.parentNode.children).indexOf(th);
    const asc = th.dataset.order !== 'asc';
    rows.sort((a, b) => {
      const va = a.children[idx].textContent.trim();
      const vb = b.children[idx].textContent.trim();
      const na = parseFloat(va), nb = parseFloat(vb);
      if (!isNaN(na) && !isNaN(nb)) return asc ? na - nb : nb - na;
      return asc ? va.localeCompare(vb) : vb.localeCompare(va);
    });
    rows.forEach((r) => tbody.appendChild(r));
    th.dataset.order = asc ? 'asc' : 'desc';
  });
});

// backlog filter
document.querySelectorAll('.backlog-filters button').forEach((b) => {
  b.addEventListener('click', () => {
    document.querySelectorAll('.backlog-filters button').forEach((x) => x.classList.toggle('active', x === b));
    const f = b.dataset.filter;
    document.querySelectorAll('.backlog-item').forEach((it) => {
      let show = true;
      if (f === 'proposed') show = it.dataset.status === 'Proposed';
      else if (f === 'recent') show = it.dataset.recent === 'true';
      else if (f === 'done') show = it.dataset.status === 'Done';
      it.dataset.hidden = show ? 'false' : 'true';
    });
  });
});

// 초기 backlog filter = proposed (사용자가 가장 자주 볼 화면)
const proposedBtn = document.querySelector('.backlog-filters button[data-filter="proposed"]');
if (proposedBtn) proposedBtn.click();
```

- [ ] **Step 2: smoke + 인터랙션 수동 검증**

```bash
node scripts/master-index.mjs
open _workspace/master.html
# 권위 그룹 → ADR 탭 → 표 column 클릭 sort 확인
# 시계열 그룹 → backlog 탭 → 필터 4 button 토글 확인
```

- [ ] **Step 3: commit**

```bash
git add scripts/master-index.mjs
git commit -m "feat(meta): master-index 인터랙션 — sort + backlog filter"
```

---

### Task 2.6: Phase 2 self-check 강화 + CHANGELOG + PR

**Files:**
- Modify: `scripts/master-index.mjs` (selfCheck)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: selfCheck 강화 (12 카테고리 모두 검증)**

기존 selfCheck 교체:

```js
function selfCheck(html) {
  const checks = [
    [html.includes('<title>magicJar Master Index'), 'title 누락'],
    [html.length < 1024 * 1024, `파일 크기 ${(html.length / 1024).toFixed(0)} KB > 1MB`],
    // 12 카테고리 + 2 그룹 = 14 data-tab
    [(html.match(/data-tab=/g) || []).length >= 14, `data-tab 14개 미만`],
    // banner 3 카드
    [html.includes('banner-card release') && html.includes('banner-card cycle') && html.includes('banner-card next'), 'banner 3 카드 누락'],
    // 각 카테고리 콘텐츠 div 존재
    ...['A','B','C','D','E','F','G','H','I','J','K','L'].map((c) => [(html.includes(`data-cat="${c}"`)), `카테고리 ${c} 누락`]),
  ];
  const failed = checks.filter(([ok]) => !ok);
  if (failed.length > 0) {
    console.error('[master-index] 무결성 self-check 실패:');
    failed.forEach(([, msg]) => console.error(`  ✗ ${msg}`));
    process.exit(1);
  }
}
```

- [ ] **Step 2: CHANGELOG row prepend**

`CHANGELOG.md` 표 헤더 직후 추가:

```markdown
<!-- anchor-master-html-phase-2 -->
| 2026-05-17 | **feat(meta): master HTML Phase 2 — 나머지 6 카테고리 + banner + 인터랙션** — 카테고리 D(spec/plan 59건) · G(release gh CLI + git tag fallback) · I(task 88+ status group) · J(카탈로그 3건) · K(하네스 6 에이전트 + 5 SKILL + 2 command + 헌법 link) · L(dashboard.html link) 추가 + banner 자동 추출(release · cycle · next · timeline rule-based + `_workspace/master-banner.json` override) + 인터랙션(URL hash 보존 + 검색 input filter + table column sort + backlog 4 filter toggle) + selfCheck 12 카테고리 모두 검증 강화. AI 호출 0 LOCK + 외부 의존 0 + KIS 호출 0. Phase 3 — unit test + release.sh 통합 + ADR 0079 후속. spec: `_workspace/specs/2026-05-17-master-html-design.md`. (orchestrator) | scripts/master-index.mjs · _workspace/master.html | Phase 1 master HTML 완성 — 나머지 6 카테고리 + banner + 인터랙션 |
```

- [ ] **Step 3: smoke + push + PR**

```bash
node scripts/master-index.mjs
git add scripts/master-index.mjs CHANGELOG.md
git commit -m "chore(release): CHANGELOG row — Phase 2 master HTML"

git push -u origin chore/master-html-phase-2
gh pr create --title "feat(meta): master HTML Phase 2 — 나머지 6 카테고리 + banner + 인터랙션" --base main --body "$(cat <<'EOF'
## Summary

Phase 2 — 나머지 6 카테고리 (D·G·I·J·K·L) + banner + 인터랙션.

## 변경 내용

- 카테고리 D(spec) · G(release gh CLI + fallback) · I(task status group) · J(catalog) · K(harness 14 정의) · L(dashboard link)
- banner 자동 추출 + \`_workspace/master-banner.json\` override
- 인터랙션 — URL hash 보존 + 검색 + table sort + backlog 4 filter
- selfCheck 12 카테고리 모두 검증 강화

## Test plan

- [x] node scripts/master-index.mjs exit 0
- [x] 12 카테고리 + 2 그룹 = 14 data-tab 모두 존재
- [x] banner 3 카드 + timeline 표시
- [x] gradle check GREEN

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

# Phase 3 — Unit Test + Release.sh 통합 + ADR 0079

### Task 3.1: Unit Test fixture + 핵심 함수 검증

**Files:**
- Create: `scripts/master-index.test.mjs`

- [ ] **Step 1: 브랜치 + main rebase**

```bash
git checkout main
git pull --ff-only origin main
git checkout -b chore/master-html-phase-3
```

- [ ] **Step 2: test 파일 작성**

```js
#!/usr/bin/env node
// scripts/master-index.test.mjs
//
// Node native test runner — node --test scripts/master-index.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

// master-index.mjs에서 helper export 의무 (아래 Task 3.2에서 추가)
import {
  firstH2, firstParagraph, parseAdrFile, parseBacklogSections, fileMeta,
} from './master-index.mjs';

test('firstH2 — 첫 H2 추출', () => {
  assert.equal(firstH2('# H1\n## H2 title\n본문'), 'H2 title');
  assert.equal(firstH2('# H1 only'), null);
});

test('firstParagraph — frontmatter + heading 건너뛰고 첫 단락', () => {
  const md = `---\nname: x\n---\n\n# title\n\n첫 단락 텍스트.\n\n두 번째 단락.`;
  assert.equal(firstParagraph(md, 100), '첫 단락 텍스트.');
});

test('parseAdrFile — 정상 frontmatter + 본문', () => {
  const md = `# ADR 0078 — 내부 자산 추출\n\n**Status**: Proposed\n\n발행일: 2026-05-16\n\n본문…`;
  const r = parseAdrFile(md, '0078-internal-asset-extraction.md');
  assert.equal(r.number, 78);
  assert.equal(r.status, 'Proposed');
  assert.equal(r.date, '2026-05-16');
  assert.match(r.title, /내부 자산 추출/);
});

test('parseAdrFile — 파일명 번호 누락 시 null', () => {
  const r = parseAdrFile('# Some ADR', 'no-number.md');
  assert.equal(r, null);
});

test('parseBacklogSections — § 번호 + status + date 추출', () => {
  const md = `## §1. 첫 의제\n\nProposed 2026-05-15\n\n본문\n\n## §2. ✅ 처리 완료 — 두 번째\n\n완료 사유\n\n## §3. 세 번째 의제\n\nProposed 2026-05-17`;
  const sections = parseBacklogSections(md);
  assert.equal(sections.length, 3);
  assert.equal(sections[0].number, 1);
  assert.equal(sections[0].status, 'Proposed');
  assert.equal(sections[0].date, '2026-05-15');
  assert.equal(sections[1].number, 2);
  assert.equal(sections[1].status, 'Done');
  assert.equal(sections[2].number, 3);
  assert.equal(sections[2].date, '2026-05-17');
});

test('fileMeta — 존재하지 않는 파일 null', () => {
  assert.equal(fileMeta('/nonexistent/file.md'), null);
});

test('idempotent — 같은 입력 → 같은 ADR 정렬 결과', () => {
  const md = '# ADR Test\n\n**Status**: Accepted';
  const r1 = parseAdrFile(md, '0001-a.md');
  const r2 = parseAdrFile(md, '0001-a.md');
  assert.deepEqual(r1, r2);
});
```

- [ ] **Step 3: master-index.mjs에서 helper export**

`scripts/master-index.mjs` 끝에 추가 (entry point 보호):

```js
// test에서 사용
export { firstH2, firstParagraph, parseAdrFile, parseBacklogSections, fileMeta };

// CLI 직접 실행 시에만 main 호출
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((e) => {
    console.error('[master-index] 치명 실패:', e);
    process.exit(1);
  });
}
```

기존 `main().catch(...)` 줄 제거.

- [ ] **Step 4: test 실행**

```bash
node --test scripts/master-index.test.mjs
```

Expected: 7 test pass.

- [ ] **Step 5: commit**

```bash
git add scripts/master-index.mjs scripts/master-index.test.mjs
git commit -m "test(meta): master-index unit test — 7건 (firstH2·firstParagraph·parseAdrFile·parseBacklogSections·fileMeta·idempotent)"
```

---

### Task 3.2: Release.sh 통합 (1 line 추가)

**Files:**
- Modify: `scripts/release.sh`

- [ ] **Step 1: release.sh 끝부분 위치 파악**

```bash
grep -n "sync-readme-releases" scripts/release.sh | tail -3
```

- [ ] **Step 2: master-index 호출 추가**

`scripts/sync-readme-releases.mjs` 호출 직후 다음 라인 추가:

```bash
# magicJar 마스터 HTML 갱신 (ADR 0079)
node scripts/master-index.mjs && echo "✓ master.html 갱신 완료"
```

- [ ] **Step 3: smoke (release.sh 부분만 dry-run으로 검증)**

```bash
# master-index만 단독 테스트
node scripts/master-index.mjs
echo "exit code: $?"
```

- [ ] **Step 4: commit**

```bash
git add scripts/release.sh
git commit -m "feat(meta): release.sh master-index 자동 통합 — 매 cut 시 master.html 갱신 (ADR 0079)"
```

---

### Task 3.3: ADR 0079 발행

**Files:**
- Create: `_workspace/arch/adr/0079-master-html-standardization.md`
- Modify: `_workspace/arch/adr/README.md` (§13 변경 이력 + §9 메타·운영 row)

- [ ] **Step 1: ADR 0079 본문 작성**

```markdown
# ADR 0079 — 마스터 시각화 HTML 표준화

**Status**: Accepted (2026-05-17)
**작성**: orchestrator (brainstorming 흐름 사용자 결정 8회 종합)
**연관 spec**: `_workspace/specs/2026-05-17-master-html-design.md`

## §1. Context

magicJar는 SoT 5층 + 다중 README + ADR 74건 + CHANGELOG 341 line + SKILL 5 + 에이전트 6 + baseline 7 minor + devlog + backlog 57 § 등 12 카테고리 풍부한 문서·정책·결정 자산을 보유한다. 기존 시각화는 운영 상태성(dashboard.html) · 시점 스냅샷성(baseline) · 일자 이력성(devlog)으로 모두 시간축 누적형. 카테고리축 + 중요도축 + 상시 최신 마스터가 없었다.

2026-05-17 W3 마감 cut(v3.6.10) 후 W4 진입 전 사용자가 본 결손을 명시 — "주요도에 나뉘어서 탭 등을 활용한 html로 정리, 표준화해서 관리하고 싶다".

## §2. Decision

`_workspace/master.html` 단일 파일 + `scripts/master-index.mjs` 자동 생성 + `/master-index` slash command 수동 trigger + `scripts/release.sh` 자동 통합으로 마스터 시각화 HTML을 표준화한다. 12 카테고리는 권위(A·B·C·D·J·K) + 시계열(E·F·G·H·I·L) 두 축으로 분류, 그룹탭 2 + 하위탭 6 + 카테고리별 본질 맞춤 입도 + 상단 banner 구조로 발행.

## §3. Architecture

- Node ESM single script (의존 0)
- 파일 system + git + gh CLI 데이터 추출 (graceful fallback)
- 인라인 HTML + CSS + vanilla JS 단일 파일 출력
- idempotent regen (같은 입력 → 같은 출력)
- AI 호출 0 LOCK

## §4. Alternatives 거절

- Alt 1: SPA 프레임워크(React) — overkill, magicJar 패턴(devlog·baseline·dashboard 인라인) 어긋남
- Alt 2: 기존 dashboard.html 흡수 — 운영 상태 vs 지식 인덱스 다른 본질, 분리 보존이 적합
- Alt 3: 외부 라이브러리(Alpine.js) — CDN 의존, 오프라인 보장 어려움
- Alt 4: 카테고리 균일 입도 — ADR 74건 vs devlog 4건 같은 카드는 가치 잃음

## §5. Consequences

**긍정**:
- 사용자 한눈에 모든 문서·정책·결정 진입
- 매 release cut 시 자동 갱신 — 항상 최신
- 의존 0 — 어떤 환경에서도 열림
- 신규 카테고리 추가 시 코드 1 항목 + 즉시 합류

**부정**:
- 단일 파일 크기 증가 가능 (1MB 임계 selfCheck로 방어)
- gh CLI 외부 호출 1건 (실패 시 git tag fallback)
- ADR/spec 추가 시 페이지 무겁기 누적 가능 (post-MVP 검토)

## §6. Phase 분리

- Phase 1 — script + slash + 6 카테고리 (PR #X)
- Phase 2 — 나머지 6 + banner + 인터랙션 (PR #Y)
- Phase 3 — test + release.sh 통합 + 본 ADR (PR #Z)

## §7. Trigger 정합

- `/master-index` slash command (사용자 수동)
- `scripts/release.sh` 매 cut 자동 (sync-readme-releases.mjs 직후)
- 기존 GitHub Actions 트리거 0 ([[feedback_no_github_actions]] 정합)

## §8. 사용자 메모리 정합

[[feedback_qa_on_demand]] AI 0 · [[feedback_no_mock_data]] graceful "데이터 없음" · [[user_language]] 한국어 · [[feedback_honorific]] 존댓말 · [[feedback_no_github_actions]] GHA 0 · [[feedback_release_policy_patch_first]] patch cycle · [[feedback_kis_first_policy]] KIS 호출 0.

## §9. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-17 | ADR 0079 발행 — Phase 1·2·3 PR 머지 완료 후 (orchestrator) |
```

- [ ] **Step 2: ADR README §9 운영 그룹에 row 추가**

`_workspace/arch/adr/README.md` 의 §9 메타·운영·하네스 표 끝에 row 추가:

```markdown
| 0079 | 마스터 시각화 HTML 표준화 (`scripts/master-index.mjs` + `/master-index` slash + release.sh 통합) |
```

- [ ] **Step 3: ADR README §14 변경 이력 row 추가**

§14 표 끝에 row 추가:

```markdown
| 2026-05-17 | ADR 0079 발행 (마스터 HTML 표준화 — orchestrator, v3.6.10 후 W3 마감 + W4 진입 전) | brainstorming 흐름 사용자 결정 8회 종합 → spec → 3 PR 직렬 머지 후 본 ADR |
```

- [ ] **Step 4: commit**

```bash
git add _workspace/arch/adr/0079-master-html-standardization.md _workspace/arch/adr/README.md
git commit -m "arch(adr): ADR 0079 발행 — 마스터 시각화 HTML 표준화 (Phase 1·2·3 PR 머지 후)"
```

---

### Task 3.4: Phase 3 CHANGELOG + PR + 머지 + 첫 자동 갱신 cut 결정

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: CHANGELOG row prepend**

```markdown
<!-- anchor-master-html-phase-3 -->
| 2026-05-17 | **feat(meta): master HTML Phase 3 — unit test + release.sh 통합 + ADR 0079 발행** — `scripts/master-index.test.mjs` 신규 7 unit test (firstH2 · firstParagraph · parseAdrFile · parseBacklogSections · fileMeta · idempotent · graceful) Node native test runner. `scripts/release.sh` 1 line 추가 (sync-readme-releases.mjs 직후 `node scripts/master-index.mjs` 자동 호출) — 매 release cut 시 master.html 자동 갱신. `_workspace/arch/adr/0079-master-html-standardization.md` 신규 (Status Accepted · §1~§9 결정 본문 + Alt 4건 거절 사유 + Phase 분리 history + 사용자 메모리 정합 7건). ADR README §9 운영 그룹 0079 row 추가 + §14 변경 이력 row 추가. 본 PR 머지 후 다음 release cut(v3.6.11 hotfix 또는 v3.7.0 W4) 시 master.html 자동 갱신 시작 — 첫 dogfooding evidence 사용자 보고 의무. (orchestrator) | scripts/master-index.test.mjs · scripts/release.sh · _workspace/arch/adr/0079 · _workspace/arch/adr/README.md | Phase 3 마무리 — master HTML 표준화 완료 + 자동 갱신 path 활성화 |
```

- [ ] **Step 2: 통합 smoke**

```bash
node --test scripts/master-index.test.mjs
node scripts/master-index.mjs
echo "exit: $?"
```

Expected: 7 test pass + master.html 정상 생성.

- [ ] **Step 3: push + PR**

```bash
git add CHANGELOG.md
git commit -m "chore(release): CHANGELOG row — Phase 3 master HTML + ADR 0079"

git push -u origin chore/master-html-phase-3
gh pr create --title "feat(meta): master HTML Phase 3 — unit test + release.sh 통합 + ADR 0079" --base main --body "$(cat <<'EOF'
## Summary

Phase 3 — unit test + release.sh 통합 + ADR 0079 발행.

## 변경 내용

- \`scripts/master-index.test.mjs\` 7 unit test (Node native test runner)
- \`scripts/release.sh\` 1 line 추가 (master-index 자동 호출)
- \`_workspace/arch/adr/0079-master-html-standardization.md\` 발행
- ADR README §9 + §14 row 추가
- CHANGELOG row

## 후속 (첫 자동 갱신 cut)

본 PR 머지 후 **다음 release cut** 시 master.html 자동 갱신 시작:
- 옵션 A: v3.6.11 hotfix (OPSP8996 fix 시점 — backlog §56)
- 옵션 B: v3.7.0 W4 진입 시점 (T-AI + T-MCP)

사용자 결정 의제로 보고.

## Test plan

- [x] node --test scripts/master-index.test.mjs 7 pass
- [x] node scripts/master-index.mjs exit 0
- [x] master.html 12 카테고리 + banner 모두 정상
- [x] gradle check GREEN

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: 사용자 PR 머지 승인 + 첫 자동 갱신 cut 결정 의제**

PR #X·#Y·#Z 머지 후 사용자에게 의제:
- "첫 자동 갱신 cut 시점: v3.6.11 hotfix OR v3.7.0 W4 진입 OR 그 외?"
- 사용자 결정 후 해당 release.sh 호출 시 master.html 자동 갱신 evidence 보고

---

### Task 3.5: 머지 후 정리 (헌법 §2 4단계)

**Files:** N/A (운영)

- [ ] **Step 1: 머지 후 main pull**

```bash
git checkout main
git pull --ff-only origin main
```

- [ ] **Step 2: 브랜치 정리**

```bash
git branch -d chore/master-html-phase-1 chore/master-html-phase-2 chore/master-html-phase-3
```

- [ ] **Step 3: spec PR #460 머지 처리 (Phase 1 시작 전에 처리되었거나 본 시점)**

spec PR #460이 아직 OPEN이면 본 시점에 머지.

- [ ] **Step 4: 메모리 등재 (사용자 확정 후만)**

사용자가 본 Plan 실행 완료 + 채택 명시 시 메모리 등재:
- `project_master_html.md` — 본 시스템 정의 + Trigger 정합
- MEMORY.md 인덱스 row 추가

---

## Self-Review

### Spec 커버리지

| spec § | 대응 task |
|---|---|
| §0 결정 매트릭스 Q1~Q8 | 전체 plan 전제 |
| §1 컨텍스트 | ADR 0079 §1 (Task 3.3) |
| §2 시스템 아키텍처 | Task 1.1·1.6·3.2 (script + render + release 통합) |
| §3 UI 구조 (banner + 그룹탭 + 하위탭 + 콘텐츠) | Task 1.6 layout + Task 2.4 banner |
| §4 카테고리 입도 매트릭스 12 | Task 1.2~1.5 (6건) + Task 2.1~2.3 (6건) |
| §5 데이터 추출 로직 | 각 카테고리 task 안 extract fn |
| §6 인터랙션 (vanilla JS) | Task 1.6 (탭+검색) + Task 2.5 (sort + filter) |
| §7 갱신 메커니즘 | Task 1.7 slash + Task 3.2 release.sh 통합 + Task 3.4 사용자 의제 |
| §8 검증 | Task 1.6/2.6 selfCheck + Task 3.1 unit test |
| §9 산출물 매트릭스 | File Structure 표 |
| §10 Phase 분리 | Phase 1·2·3 task 구조 그대로 |
| §11 비범위 | Plan에 명시 X — Phase별 PR body에 반영 |
| §12 Rollback | Task 3.2 release.sh 통합 후 주석 처리 방안 (PR body에 명시) |
| §13 사용자 메모리 정합 | ADR 0079 §8 (Task 3.3) |
| §14 변경 이력 | CHANGELOG row 3건 (Phase별 1) |

**갭 발견**: §11 비범위는 ADR 0079에 명시 권고 — Task 3.3 본문에 §11 비범위 인용 추가하면 완성. (인라인 fix 적용 가능, 본 review에서는 갭으로 명시)

### Placeholder scan

- "TBD" / "TODO" / "implement later" 0건 ✓
- 모든 step에 실제 코드 + 명령 ✓
- "Similar to Task N" 0건 ✓

### Type 일관성

- `categories[]` 인터페이스 `{ code, group, name, extract, render }` — Phase 1·2·3 모든 task에서 동일 ✓
- `extract()` 반환: `{ error: '데이터 없음' }` graceful pattern — 모든 카테고리 일관 ✓
- `render(data)` 시그니처 동일 ✓
- helpers (`firstH2`·`firstParagraph`·`parseAdrFile`·`parseBacklogSections`·`fileMeta`) — Phase 1에서 정의, Phase 3에서 export, test에서 import 일관 ✓

### Inline fix

- Task 3.3 ADR 0079 본문에 "비범위 (§11 spec 인용)" 섹션 추가 필요 — plan 본 review 후 ADR 본문 작성 시 보강

---

## Execution Handoff

Plan 완성 및 `_workspace/specs/2026-05-17-master-html-plan.md`에 저장 완료. 두 실행 옵션:

**1. Subagent-Driven (권장)** — fresh subagent per task + 두 단계 review. magicJar는 orchestrator 단독 author 정합이므로 본 plan은 orchestrator subagent 1명에게 Phase 1 일괄 위임 → Phase 2 위임 → Phase 3 위임 패턴. 빠른 iteration.

**2. Inline Execution** — 본 세션에서 직접 실행 (orchestrator 모자로). executing-plans 스킬 사용. 배치 실행 + 체크포인트 review.

사용자 결정 후 진행합니다. 또는 **Phase 1만 본 사이클에서 + Phase 2·3 다음 사이클** 같은 분할 실행도 가능.
