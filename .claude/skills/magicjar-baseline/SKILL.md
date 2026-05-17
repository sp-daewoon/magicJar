---
name: magicjar-baseline
description: magicJar baseline 문서화 사이클(7 파일 풀세트 + Mermaid 시각화 + HTML 변환). v3.X.Y release **직전** 의무 단계(ADR 0052 §X 정합 — baseline → tag → release.sh 순서 LOCK) 또는 사용자 트리거 시 4 sub-agent 병렬 디스패치로 종합 기능 정의서 발행. /baseline init|update|render|diff slash command + scripts/baseline-*.mjs 헬퍼와 함께 사용. ADR 0052 정합.
---

# magicjar-baseline — Baseline 문서화 사이클

> **운영 헌법**: `_workspace/arch/agent-charter.md` + ADR 0050(CHANGELOG 슬롯) + ADR 0052(본 사이클 정책). orchestrator 통솔 의무, 머지 후 정리 4단계, KIS-first raw 출처 인용 모두 정합.
>
> **★ 4 worktree 발행 시 sub-agent별 author 의무 (헌법 §1-A)**: 본 skill은 4 worktree 병렬 디스패치 — orchestrator는 각 worktree에 대해 sub-agent 디스패치 프롬프트에 `git config user.email "bot+<agent>@magicjar.local"` 명시 의무. baseline cycle은 add-only 단독 파일이라 author 결함이 누적되기 쉬움 (worktree default가 frontend로 떨어지면 4명 모두 frontend attribution).

## 트리거

본 skill은 다음 시점에 호출:
1. `/baseline init <version>` slash command
2. 자연어 — "v3.2.0 baseline 정리해줘", "baseline 사이클 시작" 등
3. release 직전 architect 결정 (W2·W3·W4 마감 시점)

## 산출물 — `_workspace/baseline/<version>/` 7 파일 풀세트

| 파일 | 작성자 | 핵심 내용 |
|------|--------|----------|
| README.md | architect | 한 장 요약 + 7 파일 인덱스 + 역할별 진입 경로 |
| 01-architecture.md | architect | Mermaid 4 모듈 + 외부 통합 매트릭스 + Flyway 시계열 |
| 02-data-flow.md | backend-core | 모듈별 데이터 흐름 + Kafka 토픽 + Redis 키 매트릭스 |
| 03-use-sequences.md | market-data | 사용자 시나리오 + KIS-first raw 출처 인용 (헌법 §6 의무) |
| 04-feature-spec.md | backend-core | 트랙별 9 항목 표준 명세 (REST·도메인·영속·외부 통합·Kafka·UI·PR·ADR·한계) |
| 05-ui-inventory.md | frontend | 화면 인벤토리 + 메뉴·버튼·상태 매트릭스 + Mermaid 화면 전이 |
| 06-history-traceability.md | architect | PR 시계열 + ADR 시계열 + 트랙 매핑 + backlog 처리 표 |
| 07-policy-evolution.md | architect | 운영 헌법 7§ + ADR 진화 누적 어필 |

추가: `_workspace/baseline/<version>/html/` 8 파일 (HTML 시각화).

## 사이클 7단계

### 1. 폴더·slot 사전 발행 (`/baseline init <version>`)

```bash
node scripts/baseline-init.mjs <version>
```

자동 처리:
- `_workspace/baseline/<version>/` 폴더 + README skeleton
- CHANGELOG head에 5 anchor + 5 TBD row prepend
  - `<!-- pr-N-slot-orchestrator -->` (본인용)
  - `<!-- pr-N-slot-architect -->`
  - `<!-- pr-N-slot-backend-core -->`
  - `<!-- pr-N-slot-market-data -->`
  - `<!-- pr-N-slot-frontend -->`

orchestrator가 본 commit을 chore PR로 발행 → 머지 → 단계 2.

### 2. 4 worktree + 4 task 발행

```bash
git worktree add -b agent/architect/pr-N-baseline-arch ../magic-prN-architect main
git worktree add -b agent/backend-core/pr-N-baseline-be ../magic-prN-backend main
git worktree add -b agent/market-data/pr-N-baseline-md ../magic-prN-marketdata main
git worktree add -b agent/frontend/pr-N-baseline-fe ../magic-prN-frontend main

node scripts/task.mjs start --agent architect --slug pr-N-architect ...
# (4번 반복, slug 분리 의무 — task ID 충돌 회피)
```

각 task 파일을 해당 worktree에 cp 동기화.

### 3. 4 sub-agent 병렬 디스패치 (background)

분배 LOCK (add-only 단독 파일이라 add-add 충돌 0%):

| sub-agent | 모델 | 작성 파일 |
|-----------|------|----------|
| architect | opus | README + 01 + 06 + 07 (4 파일) |
| backend-core | sonnet | 02 + 04 (2 파일) |
| market-data | sonnet | 03 (1 파일) — KIS-first raw 출처 의무 |
| frontend | sonnet | 05 (1 파일) |

**dispatch 프롬프트 핵심 의무**:
1. worktree 디렉토리 명시 (`/Users/ak-song/magic-prN-<agent>`)
2. task 파일 path + `task.mjs check` 호출 의무
3. 각 파일 §·구조 가이드 (필수 섹션 명시)
4. raw 출처 인용 의무 (코드 path + 라인)
5. CHANGELOG slot in-place edit 위치 (`<!-- pr-N-slot-<agent> -->` 직전)
6. PR 생성 + `task.mjs done --pr <N>` 호출

**market-data 특수 의무** (헌법 §6):
- 모든 KIS endpoint는 `https://github.com/koreainvestment/open-trading-api` 레포 raw URL 인용
- `mcp__kis-code-assistant__*` 도구 활용 권장

### 4. 4 PR 머지 + 정리 4단계 ×4

각 PR 머지 후:
1. `git pull --ff-only origin main`
2. `node scripts/task.mjs archive T-<id>` (status=DONE 강제, 필요 시 `--force`)
3. `git worktree remove <path>`
4. `git branch -d agent/<agent>/<branch>`

가장 흔한 함정: sub-agent가 `task.mjs done` 호출을 누락해 status=RUNNING으로 남는 케이스. archive `--force` 우회 사용.

### 5. mermaid syntax fix (자동)

```bash
node scripts/fix-mermaid-syntax.mjs
```

4 변환 룰 (mermaid 10.6.1 호환):
- `\n` → `<br/>`
- flowchart dotted edge `-.text.->` → solid `-->|"text"|`
- flowchart 노드 라벨 따옴표 보강 (콜론·슬래시·등호·공백)
- sequence message 따옴표 일괄 제거 + alias 단순화

### 6. HTML 변환

```bash
node scripts/baseline-to-html.mjs
```

`_workspace/baseline/<version>/html/` 8 파일 생성:
- index.html (README 기반)
- 01-architecture.html ~ 07-policy-evolution.html
- marked 11 + mermaid 10 CDN 클라이언트 사이드 렌더링
- 단일 파일 inline embed (CORS 회피, file:// 직접 열람)

### 7. 사용자 검수

```bash
open _workspace/baseline/<version>/html/index.html
```

사용자 보고 fail 다이어그램 → 추가 수동 fix 또는 `fix-mermaid-syntax.mjs` 룰 보강 후 재실행.

## 부분 갱신 (`/baseline update <version> <영역>`)

영역 → sub-agent 매핑:

| 영역 키 | sub-agent | 파일 |
|---------|----------|------|
| architecture | architect | 01-architecture.md |
| data-flow | backend-core | 02-data-flow.md |
| use-sequences | market-data | 03-use-sequences.md |
| feature-spec | backend-core | 04-feature-spec.md |
| ui-inventory | frontend | 05-ui-inventory.md |
| history | architect | 06-history-traceability.md |
| policy | architect | 07-policy-evolution.md |
| readme | architect | README.md |

단일 sub-agent 디스패치 → PR 머지 → 정리 → render.

## CHANGELOG 슬롯 채번룰 (ADR 0050 정합)

본 skill 사이클은 **architect spec/plan PR 발행 사이클** 분기 (ADR 0050 §3-1):

1. orchestrator가 chore PR (init)에서 5 anchor + 5 TBD row prepend
2. sub-agent 4명이 각자 본인 anchor 직전 row만 in-place edit (TBD → 실제 내용)
3. anchor line 자체는 변경 금지 → git diff 다른 line이라 머지 충돌 0%

## sub-agent fail 시 분기 룰 (헌법 §7)

분배 LOCK이 add-only 단독 파일이라 충돌 risk 낮음. 단:
- sub-agent가 task.mjs done 누락 → orchestrator가 archive `--force` (감수)
- sub-agent가 영역 외 침범 (예: backend-core가 README 변경) → 재디스패치 또는 orchestrator 자율 정정

## 사용 예시

### 시나리오 1 — W2 마감 후 v3.2.0 baseline

```
사용자: /baseline init v3.2.0
orchestrator:
  1. node scripts/baseline-init.mjs v3.2.0 (chore PR)
  2. 4 worktree + 4 task 발행
  3. 4 sub-agent 병렬 디스패치 (background)
  4. 4 PR 머지 + 정리 4단계 ×4
  5. node scripts/fix-mermaid-syntax.mjs
  6. node scripts/baseline-to-html.mjs
  7. open html/index.html
```

### 시나리오 2 — UI 변경 후 부분 갱신

```
사용자: /baseline update v3.2.0 ui-inventory
orchestrator:
  1. frontend sub-agent 단독 worktree + task
  2. 디스패치 → PR → 머지 → 정리
  3. render (mermaid + html)
```

### 시나리오 3 — 두 버전 비교

```
사용자: /baseline diff v3.1.0 v3.2.0
orchestrator:
  node scripts/baseline-diff.mjs v3.1.0 v3.2.0 --summary
```

## SoT 정합

- ADR 0052 — Baseline Documentation Cycle (정책 본체)
- ADR 0050 — CHANGELOG 슬롯 채번룰 (sub-agent 분배 매트릭스 정합)
- ADR 0031 — release/v* immutable (이전 baseline 폴더 보존)
- 헌법 §2 — 머지 후 정리 4단계
- 헌법 §6 — KIS-first raw 출처 의무 (market-data 특수)

## 함정

1. **task.mjs slug 충돌** — 4 task 동시 발행 시 분 단위 timestamp + 동일 slug면 ID 충돌. `--slug pr-N-<agent>` 필수.
2. **CHANGELOG anchor 수동 편집** — `baseline-init.mjs` 자동 생성 후 수동 변경 시 sub-agent in-place edit 부정합 발생.
3. **mermaid sequence vs flowchart 정규식 매치** — `fix-mermaid-syntax.mjs`가 diagram type 식별 후 분기. sequence message text가 flowchart 정규식에 매치되면 따옴표 잘못 추가됨 — 이미 v1에서 fix됨.
4. **이전 baseline 변경 금지** — `_workspace/baseline/v3.1.0/` 등 immutable. 새 버전 신규 폴더만 생성.
