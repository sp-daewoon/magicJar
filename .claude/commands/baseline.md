---
description: magicJar baseline 사이클 진입·갱신·렌더·diff. /baseline init <version> | render <version> | update <version> <영역> | diff <v1> <v2>
---

# /baseline — Baseline 문서화 사이클

매 release 시점(또는 사용자 트리거 시) `_workspace/baseline/<version>/` 7 파일 풀세트(README + 01~07)를 작성·갱신·시각화한다.

## sub-command 4종

### 1. `/baseline init <version>` — 새 사이클 시작

예: `/baseline init v3.2.0`

**동작**:
1. `node scripts/baseline-init.mjs <version>` 실행
   - `_workspace/baseline/<version>/` 폴더 + README skeleton
   - CHANGELOG에 4 sub-agent 슬롯 + 1 orchestrator 슬롯 prepend (ADR 0050)
2. `magicjar-baseline` skill 호출 (orchestrator 통솔자 모드)
3. orchestrator가 4 sub-agent 병렬 worktree + task 발행
4. 4 PR 머지 + 정리 4단계 ×4
5. `node scripts/fix-mermaid-syntax.mjs && node scripts/baseline-to-html.mjs` 자동 실행
6. `open _workspace/baseline/<version>/html/index.html`

**분배 매트릭스 (LOCK)**:
- architect (opus) — README + 01-architecture + 06-history + 07-policy
- backend-core (sonnet) — 02-data-flow + 04-feature-spec
- market-data (sonnet) — 03-use-sequences (KIS-first raw 출처 의무)
- frontend (sonnet) — 05-ui-inventory

### 2. `/baseline render <version>` — mermaid fix + html 재생성

예: `/baseline render v3.2.0`

**동작**:
1. `node scripts/fix-mermaid-syntax.mjs`
2. `node scripts/baseline-to-html.mjs`
3. `open _workspace/baseline/<version>/html/index.html`

baseline md 직접 수정 후 한 번에 재변환 + 시각화.

### 3. `/baseline update <version> <영역>` — 부분 갱신

예: `/baseline update v3.2.0 architecture`
예: `/baseline update v3.2.0 ui-inventory`

**영역 매핑**:
- `architecture` → architect (01-architecture.md)
- `data-flow` → backend-core (02-data-flow.md)
- `use-sequences` → market-data (03-use-sequences.md)
- `feature-spec` → backend-core (04-feature-spec.md)
- `ui-inventory` → frontend (05-ui-inventory.md)
- `history` → architect (06-history-traceability.md)
- `policy` → architect (07-policy-evolution.md)
- `readme` → architect (README.md)

**동작**:
1. 영역 → sub-agent 매핑 결정
2. orchestrator가 해당 sub-agent 단독 worktree + task 발행
3. PR 머지 + 정리 4단계
4. `/baseline render <version>` 자동 트리거

### 4. `/baseline diff <v1> <v2>` — 두 버전 변경 매트릭스

예: `/baseline diff v3.1.0 v3.2.0`
예: `/baseline diff v3.1.0 v3.2.0 --summary`

**동작**:
1. `node scripts/baseline-diff.mjs <v1> <v2> [--summary]`
2. 결과는 stdout markdown — release notes 후보로 활용

## 인자 검증

- `<version>`: `v\d+\.\d+\.\d+` 형식 (예: v3.1.0)
- 영역 미스: 매핑 표 + 가능한 영역 목록 안내
- init: `<version>` 폴더가 이미 존재하면 abort

## 사용자 의도 변형

자연어 trigger도 허용:
- "baseline 정리해줘" → `magicjar-baseline` skill 자동 호출 + 사용자에게 version 묻기
- "v3.2.0 baseline 시작" → `/baseline init v3.2.0` 동등
- "01 다이어그램 갱신" → `/baseline update <current> architecture` 동등 (current는 main HEAD 시점 추정)

## SoT 정합

- 정책 ADR: `_workspace/arch/adr/0052-baseline-cycle.md` ★
- skill: `.claude/skills/magicjar-baseline/SKILL.md`
- 헬퍼 스크립트:
  - `scripts/baseline-init.mjs` — 폴더+slot 발행
  - `scripts/baseline-to-html.mjs` — md→html
  - `scripts/fix-mermaid-syntax.mjs` — mermaid syntax fix
  - `scripts/baseline-diff.mjs` — 두 버전 비교

## 함정 주의

1. **CHANGELOG slot 수동 편집 금지** — `baseline-init.mjs`가 anchor 정합 보장. 수동 편집 시 sub-agent in-place edit이 anchor 못 찾음.
2. **mermaid 코드 직접 작성 시** — `scripts/fix-mermaid-syntax.mjs` 변환 룰 정합 (dotted edge·따옴표·alias 단순화). 자동 fix가 sequence message text를 잘못 건드리지 않도록 diagram type 명시 (`flowchart`/`graph`/`sequenceDiagram`).
3. **head v3.X.0 → v3.Y.0 갱신 시** — 이전 baseline 폴더는 보존(immutable). 새 폴더 신규 생성. ADR 0031 release/v* 정책 정합.
