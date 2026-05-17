# magicJar Master HTML 설계 명세

> **위치**: `_workspace/specs/2026-05-17-master-html-design.md`
> **발행일**: 2026-05-17 KST
> **작성**: orchestrator (brainstorming 흐름 사용자 결정 8회)
> **상태**: Proposed → 사용자 검토 후 writing-plans 진입
> **연관 ADR**: (후속 발행 예정 — 본 spec 채택 시 ADR 0079 신규)

## §0. 결정 매트릭스 (사용자 채택 사항)

| Q | 결정 | 채택안 |
|---|------|--------|
| Q1 | 카테고리 분류 | 12개 모두 포함 (선택 6 + "나머지도 다 중요" 6 = 두 축 자연 분리) |
| Q2 | UI layout | δ — 2 그룹 + 하위 탭 (상위 2 → 하위 6 → 콘텐츠, 2 클릭, 위계 명확) |
| Q3 | 갱신 메커니즘 | release.sh 통합 (매 cut 자동) + `/master-index` slash command (수동 trigger 병용) |
| Q4 | 콘텐츠 입도 | 카테고리별 다른 입도 (본질 맞춤 — ADR=timeline 표·정책=카드·devlog=최신 highlight 등) |
| Q5 | 보강 사항 | 상단 banner (현재 release·사이클 단계·다음 마일스톤·이번 작업 timeline) + backlog 시계열 그룹 핵심 |
| Q6 | 위치·파일명 | `_workspace/master.html` (dashboard.html 별도 보존, INDEX.md의 시각화 버전 의미) |
| Q7 | 기술 스택 | 순수 HTML + 인라인 CSS/JS (의존 0 · devlog/baseline/dashboard 패턴 정합) |
| Q8 | 스타일 | 신규 디자인 (다크 베이스 + 그룹탭/하위탭 명확 색 구분, Q2/Q5 mockup 그린/앰버 accent 재사용) |

## §1. 컨텍스트

magicJar 프로젝트는 SoT 5층(INDEX·정책·ADR·spec·룰북) + 다중 README + ADR 74건 + CHANGELOG 341 line + SKILL 5 + 에이전트 6 + baseline 7 minor + devlog + backlog 57 § 등 **12 카테고리에 걸친 풍부한 문서·정책·결정 자산**을 보유한다.

기존 시각화:
- `_workspace/dashboard.html` (852 line, "상태 대시보드 v2") — **운영 상태**(Phase·Task·PR·commit·QA·에이전트) 시각화
- `_workspace/baseline/v3.X.0/html/` (7 minor × 8 html = 56 html) — **minor cut 시점 종합 기능 정의서**
- `_workspace/devlog/*.html` (4 html, index 포함) — **세션 단위 개발일지** (7 표준 섹션)

**결손 영역**: 사용자가 한눈에 "내가 봐야 하는 모든 문서·정책·결정"을 진입할 수 있는 **마스터성 지식 인덱스**. 기존 dashboard.html은 운영 상태성, baseline은 시점 스냅샷성, devlog는 일자 이력성 — 모두 시간축 누적형. **카테고리축 + 중요도축 + 상시 최신** 마스터가 없다.

본 spec은 그 결손을 메우는 `_workspace/master.html` + 자동 생성 script + slash command + release 통합 표준화 설계를 명시한다.

## §2. 시스템 아키텍처

```
사용자
  │
  ├─ /master-index (수동 trigger)
  │     └─ .claude/commands/master-index.md
  │           └─ Bash → scripts/master-index.mjs
  │
  └─ scripts/release.sh vX.Y.Z (자동 trigger, 매 cut마다)
        └─ scripts/master-index.mjs (sync-readme-releases.mjs 직후 호출)
              │
              ├─ 데이터 추출 (병렬, 12 카테고리 + banner)
              │     ├─ git log --oneline -50           → 사이클 timeline
              │     ├─ gh release list --limit 30      → release table
              │     ├─ Read CHANGELOG.md               → 최근 row + anchor
              │     ├─ ls _workspace/arch/adr/         → ADR 74건 frontmatter 파싱
              │     ├─ ls docs/architecture/policies/  → 정책 10건 (첫 H2/단락)
              │     ├─ ls _workspace/specs/            → spec 59건
              │     ├─ ls _workspace/baseline/         → 7 minor × 8 html
              │     ├─ ls _workspace/devlog/           → 4 html
              │     ├─ ls _workspace/tasks/done/       → 88+ task (status 추출)
              │     ├─ Read _workspace/backlog.md      → 57 § 파싱 (Proposed/Done)
              │     ├─ ls .claude/agents .claude/skills .claude/commands → 하네스 14
              │     └─ ls _workspace/strategy _workspace/ai → 카탈로그 3
              │
              └─ 렌더 (인라인 HTML + CSS + vanilla JS)
                    → _workspace/master.html (단일 파일, 의존 0)
```

**핵심 보장**:
- **idempotent**: 같은 git sha · 같은 파일 system 입력 → 같은 master.html 출력
- **graceful**: 어떤 파일/디렉토리 누락이어도 "데이터 없음" 표시 + 다른 카테고리 정상 (메모리 [[feedback_no_mock_data]] 정합)
- **AI 호출 0**: LLM 의존 0건 (메모리 [[feedback_qa_on_demand]] 정합)
- **외부 호출 단 1건**: `gh release list` (GitHub Releases). 실패 시 CHANGELOG fallback

## §3. UI 구조

### §3.1 banner (최상단, 항상 보임)

```
┌─ 🎯 banner ────────────────────────────────────────────────────┐
│ ┌────────────┐ ┌──────────────────────┐ ┌──────────────────┐    │
│ │ 현재 Release│ │ 현재 사이클 단계      │ │ 다음 마일스톤    │    │
│ │ v3.6.10    │ │ W3 마감·W4 진입 결정 │ │ W4 (T-AI+T-MCP)  │    │
│ │ 2026-05-17 │ │ v3.7.0=W4 / 11=hotfix│ │ 또는 OPSP8996    │    │
│ └────────────┘ └──────────────────────┘ └──────────────────┘    │
│ ┌─ 📍 이번 작업 사이클 범위 ────────────────────────────────┐    │
│ │ 시작: 09:00 → 5 PR 머지 → v3.6.10 cut → master 설계 → … │    │
│ └─────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
```

**데이터 소스**:
- 현재 Release = `gh release view --json tagName,publishedAt` (실패 시 fallback `git tag -l 'v*' --sort=-version:refname | head -1`)
- 현재 사이클 단계 = (a) 자동 추출: backlog 마지막 Proposed § + CHANGELOG 최근 anchor + 활성 task agent 분류 → "W3 마감·W4 진입 결정 대기" 같은 phrase 생성 (rule-based, AI 호출 0) + (b) 사용자 override: `_workspace/master-banner.json` 존재 시 그 값 우선 (사용자 수동 명시 가능)
- 다음 마일스톤 = (a) 자동 추출: backlog Proposed § 중 W4·W5 keyword 매칭 + tracks-roadmap §1 next track + (b) 사용자 override 가능 (위와 동일 json)
- 이번 작업 timeline = `git log --since="<last release date>" --oneline` + 활성 task 추출 (rule-based, 사용자 override 불가 — 자동 100%)

### §3.2 그룹탭 (banner 직하, 항상 보임)

```
┌─ [📐 권위 ▼ (active)]  [🕐 시계열]   ───────────────────────┐
```

- 색상: 권위 = 그린계열 (#2a4d3a / #1f3a2c) · 시계열 = 앰버계열 (#4d3a2a / #3a2c1f)
- active 그룹 = 강조 색상 + 하위탭 표시
- URL hash 보존 (`#권위`, `#시계열`)

### §3.3 하위탭 (그룹탭 직하)

권위 그룹: `[A 가이드] [B 정책] [C ADR] [D spec] [J 카탈로그] [K 하네스]`
시계열 그룹: `[E baseline] [F devlog] [G release] [H backlog] [I task] [L dashboard]`

- 그룹탭 전환 시 하위탭도 같이 갱신
- URL hash 2층 보존 (`#권위/ADR`)
- active 하위탭 = 강조 + 콘텐츠 표시

### §3.4 콘텐츠 영역 (하위탭 직하)

선택된 단일 카테고리만 표시. **카테고리별 본질 맞춤 입도** (§4 매트릭스).

## §4. 카테고리별 콘텐츠 입도 매트릭스 (12)

| 그룹 | 코드 | 카테고리 | 입도 |
|---|---|---|---|
| 📐 권위 | A | 운영 가이드 | 4 파일 카드 (README · CLAUDE.md · INDEX.md · master-architecture.md) + 첫 H2 추출 + 라인 수 + 갱신일 |
| 📐 권위 | B | 정책 Living | 10 파일 카드 (policies/ 5건 + development-rules·code-convention·candle-data·external-api·tracks-roadmap) + 첫 단락 핵심 1문장 + 갱신일 |
| 📐 권위 | C | ADR | **최근 5건 highlight** (큰 카드, 번호 + 제목 + status + 날짜) + **전체 sortable 표** (번호·제목·status·§·날짜, click sort, search filter) + 총 74건 카운트 |
| 📐 권위 | D | spec/plan | **최근 10건 PR 카드** + 전체 list (PR 번호·제목·sub-agent 영역) + 총 59건 카운트 |
| 📐 권위 | J | 카탈로그 | 3 카드 (indicator-catalog · pattern-catalog · prompt-catalog) + 항목 수 + 첫 H2 |
| 📐 권위 | K | 하네스 | 6 에이전트 카드 (description + model 차등) + 5 skill 카드 (description) + 2 command 카드 + agent-charter.md link |
| 🕐 시계열 | E | baseline | 7 minor 카드 (v3.6.0 → v3.1.0) + 각 minor에 8 sub-html link (index·architecture·data-flow·sequences·feature-spec·ui-inventory·history·policy-evolution) |
| 🕐 시계열 | F | devlog | **최신 1건 prominent** (큰 카드, 일자 + 핵심 1문장) + 나머지 컴팩트 list + index.html link |
| 🕐 시계열 | G | release | **최신 5건 highlight 카드** + 전체 27 tag 표 (tag·날짜·title·GitHub Releases link) + CHANGELOG.md 최근 row 인용 |
| 🕐 시계열 | H | backlog | 필터 4 (전체 57 / Proposed 16 / 최근 3건 / 완료 38 toggle) + **신규 §** 빨간 highlight + 전체 § list (search 가능) + 각 § 1줄 요약 + 사유·관련 ADR link |
| 🕐 시계열 | I | task | **status별 group** (DONE / RUNNING / BLOCKED) + 최신 10건 + 88+ 전체 카운트 + agent별 통계 |
| 🕐 시계열 | L | dashboard | 기존 `_workspace/dashboard.html` 큰 link 카드 (운영 상태 v2 진입점) + 마지막 갱신 표시 |

## §5. 데이터 추출 로직 (script 책임)

### §5.1 추출 표준 패턴

각 카테고리는 다음 5 단계로 처리:

```js
async function extractCategoryC_ADR() {
  // 1. 파일 system 스캔
  const files = await fs.readdir('_workspace/arch/adr');
  const mdFiles = files.filter(f => f.match(/^\d{4}-.*\.md$/));

  // 2. 각 파일 frontmatter + 첫 H2 파싱
  const adrs = await Promise.all(mdFiles.map(async (f) => {
    const content = await fs.readFile(`_workspace/arch/adr/${f}`, 'utf-8');
    return parseAdrFrontmatter(content);  // { number, title, status, section, date }
  }));

  // 3. 정렬 (번호 desc)
  adrs.sort((a, b) => b.number - a.number);

  // 4. graceful: 파싱 실패 항목은 skip + warn log
  const valid = adrs.filter(a => a.number);

  // 5. 렌더용 데이터 반환
  return { recent: valid.slice(0, 5), all: valid, total: valid.length };
}
```

### §5.2 카테고리별 입력 소스

| 카테고리 | 입력 소스 | 파싱 핵심 |
|---|---|---|
| banner | `git tag -l 'v*'` + `git log --since` + `_workspace/backlog.md` § | release · cycle · 다음 마일스톤 |
| A 가이드 | 4 hardcoded path | 파일 존재 + 첫 H2 + line count + mtime |
| B 정책 | `policies/*.md` ls | 첫 H2 + 첫 단락 + mtime |
| C ADR | `_workspace/arch/adr/*.md` ls | frontmatter `status` + 첫 H1 title + 번호 추출 + ADR README §|
| D spec | `_workspace/specs/*.md` ls | 파일명 PR 번호 추출 + 첫 H1 |
| E baseline | `_workspace/baseline/v*/html/` ls | minor 버전 + 8 sub-html 존재 |
| F devlog | `_workspace/devlog/*.html` ls | 일자 추출 (filename) + 최신 highlight |
| G release | `gh release list --limit 30 --json` + CHANGELOG row | tag + date + title |
| H backlog | `_workspace/backlog.md` | `^## §\d+` 추출 + status (Proposed/처리 완료) + 사유 |
| I task | `_workspace/tasks/done/*.md` ls | frontmatter `status` + agent + completedAt |
| J 카탈로그 | `_workspace/strategy/*.md` + `_workspace/ai/*.md` | 항목 수 + 첫 H2 |
| K 하네스 | `.claude/agents/*.md` + `.claude/skills/magicjar-*/SKILL.md` + `.claude/commands/*.md` | description frontmatter 추출 |
| L dashboard | `_workspace/dashboard.html` 존재 + mtime | link만 |

## §6. 인터랙션 (vanilla JS ~150 line)

- **그룹탭 클릭** → 하위탭 6 갱신 + 콘텐츠 초기 (첫 하위탭) + URL hash `#권위` or `#시계열`
- **하위탭 클릭** → 콘텐츠 갱신 + URL hash `#권위/ADR`
- **검색**: 각 카테고리 list에 input filter (client-side `Array.filter(item => item.title.includes(q))`)
- **ADR/backlog/release 표**: column header 클릭 → sort toggle (asc/desc)
- **backlog filter**: 4 toggle (전체 / Proposed / 최근 3건 / 완료)
- **task status group**: 접기/펴기 toggle

상태 보존: URL hash 만 사용 (localStorage 미사용 — 단일 파일 정합).

## §7. 갱신 메커니즘

### §7.1 수동 trigger (`/master-index`)

`.claude/commands/master-index.md` 정의:

```markdown
---
description: magicJar 마스터 HTML 갱신 — `_workspace/master.html` 재생성. 사용자 수동 trigger 또는 release.sh 자동 호출.
---

scripts/master-index.mjs를 실행하여 `_workspace/master.html`을 재생성합니다.
완료 후 변경 사항을 git status로 확인하고 사용자에게 보고합니다.
```

### §7.2 자동 trigger (release.sh 통합)

`scripts/release.sh` 끝부분에 1 line 추가:

```bash
# 기존:
node scripts/sync-readme-releases.mjs

# 추가:
node scripts/master-index.mjs && echo "✓ master.html 갱신 완료"
```

### §7.3 갱신 후 commit 정책

- **자동 trigger (release.sh)**: master.html diff 발생 → release cut PR (예: `chore(release): vX.Y.Z README + master 매트릭스`)에 묶음 — 기존 sync-readme-releases 패턴 정합
- **수동 trigger (`/master-index`)**: master.html 변경 발생 → 다음 사이클 작업 commit에 묶어서 함께 push (별도 PR 발행 X — 운영 부담 회피). diff가 trivial이면 push skip 옵션 사용자 결정.
- master.html은 **git tracked** (gitignore 아님) — 변경 이력 git log로 추적 가능
- `_workspace/master-banner.json` (사용자 override 파일)은 **git tracked** — 사용자 의도 명시 변경 이력 보존

## §8. 검증 (테스트)

### §8.1 unit test

- `scripts/master-index.test.mjs` (Node native test runner 또는 vitest)
- 핵심 fixture: 가짜 ADR 3건 · 정책 2건 · backlog 5 § · devlog 2건
- 테스트 케이스:
  - `parseAdrFrontmatter` — 정상/누락/오타 frontmatter
  - `parseBacklogSections` — § 번호 추출 + Proposed/처리 완료 분기
  - `renderBanner` — release/cycle/timeline 데이터 → HTML 스니펫
  - `renderCategoryC` — ADR 데이터 → 표 HTML
  - `extractAll` — 전체 추출 idempotent (같은 입력 → 같은 출력)

### §8.2 무결성 점검 (script 실행 후)

script 끝에 self-check:
- `_workspace/master.html` 생성 OK
- 파일 크기 < 1MB (overgrowth 방지)
- `<title>magicJar Master Index` 포함
- 12 카테고리 탭 DOM 모두 존재 (`grep -c 'data-tab=' master.html` 14 = 2 그룹 + 12 카테고리)
- banner 3 카드 + timeline 1 줄 존재

실패 시 → exit 1 + 에러 메시지

### §8.3 visual regression (post-MVP)

- v1: snapshot test 미적용
- v2: HTML snapshot 비교 (Playwright 또는 단순 line diff)

## §9. 산출물 매트릭스

| 파일 | 목적 | 책임 |
|---|---|---|
| `_workspace/master.html` | **마스터 HTML 생성물** (단일 파일, 인라인 CSS/JS) | script 자동 생성 |
| `scripts/master-index.mjs` | **생성 script** (Node ESM, 의존 0) | 12 카테고리 + banner 추출·렌더 |
| `scripts/master-index.test.mjs` | unit test | fixture·idempotent·핵심 함수 검증 |
| `.claude/commands/master-index.md` | **slash command** | 사용자 수동 trigger 진입점 |
| `scripts/release.sh` | release 통합 (1 line 추가) | 매 cut마다 자동 호출 |
| `_workspace/specs/2026-05-17-master-html-design.md` | **본 spec 문서** | 결정 매트릭스 + 설계 명세 |
| `_workspace/arch/adr/0079-master-html-standardization.md` | **후속 ADR** (Proposed) | 본 spec 채택 결정 기록 |

## §10. Phase 분리 (구현 순서)

- **Phase 1** — script + slash command + 핵심 카테고리 6 (A·B·C·H·E·F)
- **Phase 2** — 나머지 카테고리 6 (D·G·I·J·K·L) + banner + 인터랙션
- **Phase 3** — test + release.sh 통합 + ADR 0079 발행 + v3.6.11 또는 v3.7.0 cut 시 첫 자동 갱신

각 Phase = 별도 PR (단일 책임 룰 정합). orchestrator 단독 author (backend-core/frontend 영역 침범 없음 — 운영 도구).

## §11. 비범위 (out of scope)

- **운영 상태 영역**: `_workspace/dashboard.html`은 별도 유지 (운영 상태 = Phase·Task·PR·commit·QA·에이전트). master.html L 카테고리에서 link만.
- **실시간 데이터**: master.html은 static snapshot. 실시간성 필요는 dashboard.html이 담당.
- **모바일 반응형**: post-MVP. 데스크탑 우선.
- **다국어**: 한국어 fixed (사용자 메모리 [[user_language]] 정합).
- **다크/라이트 모드 토글**: 다크 fixed v1. 사용자 요청 시 v2 추가.
- **AI 자동 요약**: AI 호출 0 LOCK (메모리 [[feedback_qa_on_demand]] 정합).
- **로그인/권한**: 단일 사용자 정합.

## §12. Rollback 시나리오

- `_workspace/master.html` 결함 시 → script 호출 stop, 기존 파일 git checkout으로 복원
- script 결함 시 → `scripts/release.sh`에서 master-index 호출 line 주석 처리 (release cut 자체는 영향 0)
- 본 spec 채택 후 design 변경 시 → ADR 0079 supersede + 신규 ADR 발행

## §13. 사용자 메모리 정합 (의무)

- [[user_language]] — 한국어 고정 ✓
- [[feedback_honorific]] — UI 한국어 존댓말 ("~합니다") ✓
- [[feedback_no_mock_data]] — 데이터 없음 시 "데이터 없음" 한국어 표시 ✓
- [[feedback_no_github_actions]] — GHA 트리거 0 (로컬 script 단독) ✓
- [[feedback_spec_plan_scope]] — 신규 트랙·새 모듈이라 spec 작성 정당 ✓
- [[feedback_devlog_html]] — devlog와 별개 (다른 본질) — 통일 없음 ✓
- [[feedback_release_policy_patch_first]] — patch 사이클 정합 ✓
- [[feedback_kis_first_policy]] — KIS 데이터 직접 호출 0 (release.sh도 KIS 호출 0) ✓

## §14. 변경 이력

| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-05-17 | 본 spec 신설 — Q1~Q8 사용자 결정 8회 종합 | brainstorming 흐름 (사용자 자율 위임 + visual companion 결정 검증) |
