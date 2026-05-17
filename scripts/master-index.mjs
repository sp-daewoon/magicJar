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

// 카테고리 register — 코드·이름·그룹·추출·렌더 4 책임
// extract: () => data (graceful: 누락 시 { error: '데이터 없음' } 반환)
// render: (data) => HTML string (inner content only, shell은 renderShell이 wrap)
const categories = [];

function registerCategory(code, group, name, extract, render) {
  categories.push({ code, group, name, extract, render });
}

// ============================================================
// 카테고리 등록 (코드 순)
// ============================================================

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

  const banner = await extractBanner();
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

function renderShell(banner, cats) {
  const groups = ['권위', '시계열'];
  const groupColors = { '권위': { primary: '#2a4d3a', accent: '#4caf50' }, '시계열': { primary: '#4d3a2a', accent: '#ff9800' } };

  const catsByGroup = Object.fromEntries(groups.map((g) => [g, cats.filter((c) => c.group === g)]));

  return `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>magicJar Master Index</title>
<style>
  body { background: #0f1419; color: #e0e0e0; font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 0; padding: 16px; font-size: 13px; line-height: 1.5; }
  h1 { margin: 0 0 12px 0; font-size: 20px; color: #fff; }
  h3 { color: #ccc; font-size: 14px; margin: 16px 0 8px; }
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
  .banner-timeline .label { color: #888; font-size: 10px; text-transform: uppercase; margin-bottom: 4px; }
  .group-tabs { display: flex; gap: 4px; margin-bottom: 8px; }
  .group-tab { background: #1a1a1a; padding: 8px 16px; border-radius: 4px 4px 0 0; cursor: pointer; font-weight: bold; color: #888; }
  .group-tab.active[data-group="권위"] { background: ${groupColors['권위'].primary}; color: #fff; }
  .group-tab.active[data-group="시계열"] { background: ${groupColors['시계열'].primary}; color: #fff; }
  .sub-tabs { display: flex; gap: 2px; margin-bottom: 12px; background: #1a1a1a; padding: 6px; border-radius: 0 4px 4px 4px; flex-wrap: wrap; }
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
  .card-files a { font-size: 10px; color: #4fc3f7; }
  .empty { color: #666; text-align: center; padding: 40px; }
  .cat-search { width: 100%; background: #0f0f0f; border: 1px solid #333; color: #fff; padding: 6px 10px; margin: 8px 0; border-radius: 3px; box-sizing: border-box; }
  table { width: 100%; border-collapse: collapse; font-size: 11px; }
  th, td { padding: 6px; text-align: left; border-bottom: 1px solid #333; }
  th { background: #0f0f0f; cursor: pointer; }
  th:hover { background: #1a1a1a; }
  a { color: #4fc3f7; text-decoration: none; }
  a:hover { text-decoration: underline; }
  .adr-recent { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; margin-bottom: 14px; }
  .adr-card { background: #1f3a2c; padding: 8px; border-radius: 3px; }
  .adr-number { font-weight: bold; font-size: 14px; }
  .adr-title { color: #aaa; font-size: 10px; margin: 4px 0; }
  .adr-meta { color: #666; font-size: 9px; }
  .backlog-filters { display: flex; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
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
  .card-mini { background: #3a2c1f; padding: 8px; border-radius: 3px; font-size: 11px; text-align: center; color: #ccc; }
  .baseline-card .card-files { margin-top: 6px; line-height: 1.8; }
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
  <div class="cat-content" data-cat="${c.code}" data-group-content-cat="${c.group}" data-active="${idx === 0 ? 'true' : 'false'}">
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
    setTimeout(() => {
      const st = document.querySelector(\`.sub-tab[data-cat="\${cat}"]\`);
      if (st) st.click();
    }, 0);
  }
}

// 검색
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
