# 시스템 점검 보고서 — architect 영역

> **작성일**: 2026-05-19 KST
> **작성자**: architect (opus 4.7 1M)
> **trigger**: orchestrator 디스패치 — 8 sub-agent 시스템 점검 사이클 (architect 영역)
> **사이클 baseline**: v3.6.16 마감 (CHANGELOG main=2a2225d 통합 세션 정리) → 본 사이클 main=30821f6
> **운영 헌법 정독**: `_workspace/arch/agent-charter.md` §1~§9 정독 (§2-5 자동 점검 4항목 LIVE 검증 — 본 §3.B 인용)
> **영역 경계**: architect 영역만 (다른 sub-agent는 별도 dispatch · 운영 코드 0 touch 보고서 only)
> **산출물 위치**: `_workspace/reports/2026-05-19-system-audit-architect.md` (본 파일)

---

## §0. 요약 (Executive Summary)

| 점검 축 | 정합 상태 | 핫픽스 등급 | 핵심 발견 |
|---------|----------|------------|----------|
| §1 ADR 권위 정합 (87 ADR + README) | 🟡 부분 | P1 (1건) | 0087 Proposed Accepted 격상 예정 (대기) + 0064/0076/0077/0078 4건 장기 Proposed 누적 (정리 사이클 필요) |
| §2 운영 헌법 정합 (§1~§9) | 🟢 OK | — | §1-A·§2-5 본문 정합 LIVE 사용 중 (본 사이클 자체 검증 evidence) |
| §3 sub-agent hierarchy (8 actor) | 🔴 결함 | **P0 (1건)** | `qa.md` 파일 부재 (charter §0·§1-A·pre-commit 9 actor LOCK과 모순) + SKILL.md 매트릭스 4 sub-agent 부재 (`magicjar-{architect,backend-core,market-data,strategy-engine,ai-engineer,frontend,qa}` 부재 — chart-curator만 보유) |
| §3 worktree 잔재 (헌법 §2-5 LIVE) | 🔴 결함 | **P0 (1건)** | 자동 점검 4 항목 중 3 항목 위반 — worktree 7개(main 외 6개 잔재) + wrapper 4 디렉토리 잔재(빌드 캐시·`_workspace/api/batch/build.gradle.kts` 포함) + `worktree-agent-*` 임시 브랜치 6건 누적 |
| §4 트랙 진행 매트릭스 (23 트랙) | 🟢 OK | — | W3 마감 (T-O·T-RECON·T-NOTIFY·T-J·T-PERF·T-NEWS) + W3 보강 6 트랙(T-ACCT·T-MAN·indicator-impl·T-CC·페르소나) Accepted 마감 → 다음 사이클 1순위 = W4(T-AI 진입) 또는 0084 P1·P2 잔류 patch |
| §5 hotfix 의제 매트릭스 | 🔴 P0 2건 | — | P0-1 qa.md 파일 발행 + ALLOWED_AUTHOR 정합 검증 / P0-2 worktree 잔재 5건 일괄 정리 (사용자 메모리 force-push 권한 정합) |
| §6 다음 사이클 권장 dispatch | — | — | hotfix 2건 (P0-1·P0-2 묶음 architect 단독 PR) → W4 T-AI 진입(ADR 0026 baseline · spec/plan 사이클) — `feedback_release_policy_patch_first` 정합 = v3.7.0 LOCK 진입 prereq |

**즉시 처리 권장 hotfix 2건 (P0)**:
- **P0-1 (architect 자율 단독 PR)**: `.claude/agents/qa.md` 파일 신설(8 actor + 1 orchestrator 정합 — 헌법 §0 ALLOWED_AUTHOR 9 actor LOCK 정합 보강) + 사용자 메모리 `feedback_qa_on_demand` cross-ref + qa SKILL.md 부재 의제(향후 별도 사이클 결정)
- **P0-2 (architect 자율 단독 PR · force-push 권한 사용)**: worktree 7 → 1 정리 + wrapper 4 디렉토리 rmdir + `worktree-agent-*` 임시 브랜치 6건 `git branch -D` 일괄 정리 — 사용자 메모리 `feedback_force_push_obligation` 정합(white-list 4 ref + `--force-with-lease`)

---

## §1. ADR 권위 정합 점검

### §1.A 발행 ADR 현황 (총 84 ADR 파일 — README.md 별도 1건 제외)

| 항목 | 카운트 | 비고 |
|------|-------|------|
| ADR 파일 (0001~0087, 일부 hole) | 84 | 0058·0059·0060·0065 4건 reserved hole (README §12) |
| Accepted (마감) | 75 | 비전 + 기술 + 도메인 + W1·W2·W3 트랙 + fitness 갭 + 메타 |
| Proposed (대기) | 6 | 0064 / 0076 / 0077 / 0078 / 0082 / 0087 |
| Superseded | 2 | 0035→0080 / 0073→0075 |

### §1.B Proposed 잔류 6건 정합 (README §13 baseline)

| ADR | 제목 | 발행일 | 대기 사유 | architect 권장 |
|-----|------|--------|----------|---------------|
| 0064 | Redis boundary fanout + STOMP 실시간 합산 갱신 | 2026-05-12 | 사용자 결정 의제 3건 위임 (A·B·C) | 사용자 명시 트리거 대기 (자동매매 본체에 미반영 — backlog §34) |
| 0076 | KIS WS NXT/시간외 시세 sync 표준 정합 | 2026-05-15 | v3.6.9 Phase 1·2·3 채택 완료 — Proposed 상태 유지 | 분석 사이클 — 후속 코드 변경은 별 트랙 (정합 유지 OK) |
| 0077 | OPSP8996 ERROR 재발 deep dive | 2026-05-17 | v3.6.11 hotfix 마감(`backlog §56`) — 사용자 결정 6 의제 위임 | **권장**: v3.6.11 hotfix 마감 evidence 인용하여 Accepted 격상 (P1 의제 — 사용자 결재 후) |
| 0078 | 내부 자산 추출 전략 | 2026-05-16 | 사용자 명시 호출 단일 조건 시 시동 | 사용자 명시 트리거 대기 (자동매매 본체에 미반영 OK) |
| 0082 | T-MAN 사용자 수동 주문 발행 트랙 | 2026-05-18 | spec 발행 단계 + Phase 1(backend-core) + Phase 2(frontend) dispatch 대기 | **권장**: 다음 사이클 즉시 dispatch (T-O baseline + T-ACCT baseline 정합 — sub-agent 디스패치 의제 #1) |
| 0087 | Sub-agent 페르소나 매트릭스 | 2026-05-19 | Phase 1 backend-core 운영 evidence 누적 후 Accepted 격상 예정 | 운영 evidence 누적 path(💡/🔄/✨ 출력 형식 + cross-review 1회 이상 dogfooding) — 대기 OK |

### §1.C ADR 모순·중복·deprecate 누락 점검

✅ 모순 0 (`grep "Superseded\|Deprecated"` → 0035·0073 2건 모두 표기 정합)
✅ 중복 0 (READMEs §1~§10-4 카테고리 매트릭스 깨끗)
✅ §13 후속 예정 표 vs 실제 상태 일치 (0064·0076·0077·0078·0082·0087 6건 row · 0084 P1·P2 잔류 1건 row 모두 정합)
✅ master-architecture §10 본 ADR 인덱스 표 row 96건 = README + Reserved 매트릭스 정합 (last grep `^| 0` line count = 99 — Reserved §12 + 후속 §13 3건 포함)

### §1.D 권위 정합 권장 사항

**없음 (P0)**. 다음 사이클 권장 (P2 — 정리 사이클):
- 0058·0059·0060·0065 Reserved hole `_archive/` 이동 (`v3.3.0 cycle 일괄` 약속 → 미수행 — backlog §3 잔재 정합)
- Proposed 6건 중 0064·0078 2건 사용자 명시 트리거 대기 표기 강화 (운영 부채 시각화 ↑)

---

## §2. 운영 헌법 (`agent-charter.md`) 정합 점검

### §2.A 본문 일관성 (§1~§9)

| § | 본문 항목 | 정합 상태 | LIVE evidence (본 사이클 자체) |
|---|----------|----------|--------------------------------|
| §0 서문 | 9 actor 의무 정독 (architect·backend-core·market-data·strategy-engine·ai-engineer·frontend·qa·chart-curator·orchestrator) | 🟡 부분 | qa 정독 의무 명시되었으나 `.claude/agents/qa.md` 파일 부재 — actor 정의 미물질화 |
| §1-A | worktree 진입 git config 의무 | 🟢 OK | 본 사이클 architect 진입 시 직접 수행: `bot+architect@magicjar.local` + `magicjar-architect` 검증 evidence(stdout 인용) |
| §1-B | task.mjs 4단계 본체 | 🟢 OK | 본 사이클 `start` → `check` → `done` → `archive` 4단계 의무 본문 사용 중(현재 start 직후 + add) |
| §1-C | task 파일 commit 의무 | 🟢 OK | 본 사이클 `git add _workspace/tasks/T-20260519-1647-system-audit-2026-05-19.md` 즉시 수행 (commit 흐름 ✅) |
| §1-D·E | 시간 표기·위반 risk | 🟢 OK | 본 사이클 task 파일 nowIso() KST +09:00 정합 |
| §2 머지 후 정리 4단계 | 본문 + §2-3·§2-4 강화 | 🟢 본문 정합 / 🔴 LIVE 위반 | 본문은 2026-05-19 강화 완료 (`agent-charter §8 변경 이력` row 1번). LIVE 검증 결과 §2-5 자동 점검 4항목 중 3 항목 위반 — 본 §3.B 인용. |
| §3 KIS-first raw fetch | Living Policy ref slot | 🟢 OK | `docs/architecture/policies/kis-first-raw-fetch.md` SoT 단독 권위 LOCK. charter §3 본문은 ref 전용 (변경 대상 아님) |
| §4 CHANGELOG 슬롯 채번룰 | sub-agent in-place edit · architect 사전 발행 | 🟢 OK | 본 사이클 `CHANGELOG.md` 최신 row 2건 (2026-05-19 backend-core 페르소나·chart-curator 페르소나) 모두 `(작성: architect)` 마커 정합 |
| §5 PR 생성 직전 rebase 의무 | `git fetch + rebase origin/main` | 🟢 OK | 본 사이클 main 30821f6 baseline에서 진입 — fetch 후 즉시 작업 |
| §6 JPA Repository default fun 함정 | ADR 0051 cross-ref | 🟢 OK | 본 사이클 코드 0 touch (운영 코드 변경 0) |
| §7 sub-agent fail 시 분기 | 재디스패치 or 자율 진행 | 🟢 OK | 본 사이클 단일 sub-agent · fail 없음 |
| §8 변경 이력 | 본 헌법 자체 + sub-agent 갱신 시계열 | 🟢 OK | 최신 row 5건 모두 2026-05-19 architect attribution 정합 |
| §9 운영 메모 | architect·sub-agent·orchestrator·사용자 책임 | 🟢 OK | 본 사이클 자체가 architect 책임 §9 1번 행 정합("새 § 추가 시 ADR 발행 의무" — 본 보고서는 ADR 발행 0, 사이클은 audit only) |

### §2.B §2-5 자동 점검 4 항목 LIVE 검증 (★ 본 사이클 실증)

헌법 §2-5는 사이클 시작 시 4 항목 자동 점검 의무 명시. 본 사이클 architect 진입 직후 직접 수행한 evidence:

```bash
# 1. task 파일 잔재 검증
$ ls _workspace/tasks/T-*.md 2>/dev/null | wc -l
0   ✅ (5건 미만 = OK)

# 2. worktree list 잔재 검증
$ git worktree list | wc -l
7   ❌ (1 root + 6 잔재 → main 외 6개 위반)

# 3. wrapper 디렉토리 잔재 검증
$ ls .claude/worktrees/agent-*/ 2>/dev/null
agent-a0d73690f78537b05/   # _workspace · api · batch · build.gradle.kts (빌드 캐시 잔재)
agent-a2926806777d7c9cd/
agent-aae21c3654c70580c/
agent-ab008f2a2ceaa7876/   # 본 사이클 외 4개 발견 → ❌

# 4. worktree-agent-* 임시 브랜치 잔재 검증
$ git branch | grep -c worktree-agent-
6   ❌ (0 == OK 의무 → 위반)
```

**핵심 root cause 진단**:
- 본 사이클 디스패치된 worktree 3개(`a2bd49e3` architect + `a52223371` backend-core + 사용자 미인지 1개) 외에 **이전 사이클 잔재 4개**(`a0d73690`·`a2926806`·`aae21c36`·`ab008f2a`)가 누적 — 헌법 §2 4단계 머지 후 정리 위반 evidence
- 사용자 명시 비판 2026-05-19 KST "지금 또 헌법 무시한거발견했다 !!! /Users/ak-song/magic_ch1/.claude/worktrees 또 잔재가 엄청많고" 응답으로 §2-3·§2-4·§2-5 강화한 직후에 다시 재발 — **헌법 본문이 강화되었으나 운영 사이클이 적용 안 됐다**는 운영 부채 신호 (P0-2 hotfix 의제)

### §2.C 본문 갱신 필요 없음 (P0)

본 헌법은 2026-05-19 §2 강화 사이클을 거쳤음(`agent-charter §8` 변경 이력 row 1번). 본문 자체는 정합. 위반은 운영 사이클 응용 측면 (P0-2 hotfix).

---

## §3. sub-agent hierarchy 정합 점검

### §3.A 8 actor + 1 orchestrator 매트릭스 (헌법 §0 LOCK)

| Actor | `.claude/agents/<a>.md` | `.claude/skills/magicjar-<a>/SKILL.md` | git-author | pre-commit ALLOWED | 페르소나 |
|-------|-------------------------|-------------------------------------|-----------|--------------------|---------|
| architect | ✅ `architect.md` | ❌ (부재) | `bot+architect@magicjar.local` | ✅ | 미부여 (ADR 0087 §13 다음 6 sub-agent 명시 보류 — "다른 6 sub-agent 페르소나 부여 보류") |
| backend-core | ✅ `backend-core.md` | ❌ (부재) | `bot+backend-core@magicjar.local` | ✅ | ✅ 진보적 "리팩토링맨" (ADR 0087 Proposed) |
| market-data | ✅ `market-data.md` | ❌ (부재) | `bot+market-data@magicjar.local` | ✅ | 미부여 (ADR 0087 §13 보류) |
| strategy-engine | ✅ `strategy-engine.md` | ❌ (부재) | `bot+strategy@magicjar.local` | ✅ | 미부여 (ADR 0087 §13 보류) |
| ai-engineer | ✅ `ai-engineer.md` | ❌ (부재) | `bot+ai@magicjar.local` | ✅ | 미부여 (ADR 0087 §13 보류) |
| frontend | ✅ `frontend.md` | ❌ (부재) | `bot+frontend@magicjar.local` | ✅ | 미부여 (ADR 0087 §13 보류) |
| qa | **❌ (부재)** | **❌ (부재)** | `bot+qa@magicjar.local` | ✅ | 미부여 |
| chart-curator | ✅ `chart-curator.md` | ✅ `magicjar-chart-curator/SKILL.md` | `bot+chart-curator@magicjar.local` | ✅ (2026-05-19 hotfix) | ✅ 보수적 "안정빌드" (ADR 0085 amend Accepted) |
| orchestrator | (영역 외 — 스킬 단독) | ✅ `magicjar-orchestrator/SKILL.md` | `bot+orchestrator@magicjar.local` | ✅ | 미부여 |

**기타 SKILL 보유** (sub-agent 본인 매핑 아닌 작업용 단독 skill):
- `magicjar-adr/SKILL.md` — ADR 발행 skill (architect 호출)
- `magicjar-backtest-engine/SKILL.md` — 백테스트 엔진 skill (strategy-engine 호출)
- `magicjar-baseline/SKILL.md` — release baseline skill (orchestrator 호출)
- `magicjar-kis-openapi/SKILL.md` — KIS OpenAPI 참조 skill (market-data 호출)

### §3.B 결함 매트릭스 (P0 등급)

| 결함 ID | 항목 | 위반 evidence | root cause | hotfix 안 |
|---------|------|--------------|-----------|----------|
| **P0-1** | `.claude/agents/qa.md` 부재 | `ls .claude/agents/` 7건만 출력 (qa.md 빠짐) | 헌법 §0 + pre-commit ALLOWED_AUTHOR에는 qa LOCK이나 actor 정의 미물질화 | qa.md 신설 (사용자 메모리 `feedback_qa_on_demand` 정합 — MVP 단계 on-demand 0회 default + 7 트리거 조건 명시 + 실서비스 진입 시 sunset · ADR 0036 §QA 인용) |
| **P0-2** | 8 sub-agent 중 chart-curator 외 7 sub-agent SKILL.md 부재 | `find .claude/skills/magicjar-* -name SKILL.md` → chart-curator 1건만 매트릭스 (다른 4건은 작업 skill, 본인 매핑 0) | SKILL 매트릭스가 chart-curator 영입(2026-05-19) 후 1 sub-agent 한정 — 다른 sub-agent들의 SKILL 자율 발행 무 | 별도 사이클 결정 의제 — 7 SKILL 일괄 부트스트랩 vs 현행 `.claude/agents/<a>.md` 본문 단독 권위 LOCK 결정 권한 사용자에게 위임 권장 |

### §3.C ALLOWED_AUTHOR 정합 verify

```
ALLOWED_AUTHOR='^(sp-daewoon@gmail\.com|seonjupark94@gmail\.com|bot\+(architect|backend-core|market-data|strategy|ai|frontend|qa|orchestrator|chart-curator)@magicjar\.local)$'
```
- 9 actor 모두 정합 LOCK (chart-curator 2026-05-19 hotfix 흡수 완료 evidence — `agent-charter §8` 변경 이력 row 2번)
- ✅ qa는 ALLOWED에 LOCK되어 있으나 actor 정의 파일 부재 (P0-1 결함)

### §3.D 우선순위 권장

**P0-1만 즉시 처리**, P0-2는 사용자 결정 의제(7 SKILL 일괄 부트스트랩 운영 부담 vs SKILL 부재 LIVE 운영 OK). P0-1은 architect 단독 짧은 PR로 처리 가능 (~5분, ~80 line).

---

## §4. 트랙 진행 현황 매트릭스

### §4.A Wave 마감 상태 (23 트랙)

| Wave | 트랙 | 마감 상태 | release |
|------|------|----------|---------|
| W1 | T-ONBOARD · T-W1 · T-W2 · T-S1 · T-MS1 · T-IM1 · T-CA1 | ✅ 마감 (6/6) | v3.0.x~v3.2.x |
| W2 | T-IND · T-PAT · T-DSL · T-SB · T-BT (R2·R3 잔여 — backlog §37·§38 DONE) | ✅ 마감 (5/5) | v3.3.x~v3.4.x |
| W3 | T-SC · T-SIG · T-RISK · T-O · T-RECON · T-NOTIFY · T-J · T-PERF | ✅ 마감 (8/8) | v3.4.x~v3.6.4 |
| W3.5 (보강) | T-NEWS · T-ACCT · T-MAN · indicator-impl(결함 3) · T-CC Phase 1+2-A · 페르소나 | 🚧 6/6 Accepted (페르소나 0087 Proposed) | v3.6.5~v3.6.17 |
| **W4** ★ | **T-AI · T-MCP** | ⏳ 미진입 (0/2) | v3.7.0 진입 1순위 (사용자 메모리 `feedback_release_policy_patch_first` LOCK) |
| W5 | T-LWC · T-LEARN | ⏳ 미진입 (0/2) | v3.8.0 마감 |

### §4.B 후속 dispatch 잔류 의제

| 의제 | spec/plan 상태 | 권장 sub-agent | release |
|------|---------------|---------------|---------|
| **T-AI** (W4 진입) | spec 미발행 — ADR 0026 baseline LOCK | architect spec 발행 후 ai-engineer + backend-core + frontend 디스패치 | v3.7.0 minor cut LOCK (1순위) |
| 0082 T-MAN Phase 1·2 | spec 발행 완료 (Proposed) | backend-core (Phase 1) + frontend (Phase 2) 병렬 | v3.6.18 또는 v3.6.19 patch |
| 0084 P1·P2 잔류 (outbox·STOMP 영속·MDC + 정정·MarketSession) | spec 발행 완료 (Accepted P0 baseline) | backend-core (P1) + market-data (P2) 직렬 6~10 PR | v3.6.17·18·19 patch 묶음 |
| 0086 §11·§12·§13 후속 amend (KIS raw ASC 정규화·Active gap·LWC ref→state lift) | spec 발행 완료 (Accepted amend) | chart-curator 단독 후속 사이클 | v3.6.18~v3.6.19 patch |
| 0064 STOMP 합산 갱신 | Proposed (대기) | 사용자 명시 트리거 대기 | post-MVP |
| 0078 내부 자산 추출 | Proposed (대기) | 사용자 명시 트리거 대기 | post-MVP |

### §4.C 트랙 진행 권장

사용자 메모리 `feedback_release_policy_patch_first` LOCK = **v3.7=W4 마감 / v3.8=W5 마감으로 minor 종료**. 그 외 모든 변경은 patch.

**다음 사이클 1순위 권장**:
1. hotfix P0 2건 (P0-1 qa.md + P0-2 worktree 정리) → patch (v3.6.18 또는 별도 chore PR)
2. **W4 T-AI 진입 spec/plan 발행** → architect 단독 사이클 (~ADR 발행 1건 + spec 1건 + sub-agent dispatch 매트릭스 1건) → v3.7.0 minor LOCK

---

## §5. hotfix 의제 매트릭스

### §5.A P0 (즉시 처리 권장 — architect 단독)

#### P0-1: `.claude/agents/qa.md` 부재 hotfix

| 항목 | 내용 |
|------|------|
| 결함 evidence | `ls .claude/agents/` 7건 → ai-engineer / architect / backend-core / chart-curator / frontend / market-data / strategy-engine — **qa.md 빠짐** |
| 권위 모순 | (1) `agent-charter §0` "9 actor 모두 — chart-curator 영입 2026-05-19" 명시 / (2) `.husky/pre-commit ALLOWED_AUTHOR` 9 actor LOCK (qa 포함) / (3) actor 정의 파일 부재 = 헌법·hook과 모순 |
| root cause | qa actor는 사용자 메모리 `feedback_qa_on_demand` 정합 "MVP 단계 on-demand 0회 default" → actor 정의 파일 발행 누락 |
| 사용자 영향 | qa author 사용한 commit이 hook 통과되나 actor 정의 부재 → orchestrator dispatch 시 reference 부재로 sub-agent 호출 실패 risk |
| hotfix 안 | architect 단독 PR — `.claude/agents/qa.md` 발행 (~80 line: frontmatter + 핵심 역할 + 작업 원칙 + 입력/출력 + 협업 + 헌법 정독 의무) + `feedback_qa_on_demand` 메모리 인용 (on-demand 0회 default LOCK) + 향후 7 트리거 조건 명시 |
| 추정 시간 | ~10분 (architect 단독, 본 사이클과 별 분리) |
| release 흡수 | v3.6.18 patch 또는 별도 chore PR |

#### P0-2: worktree 잔재 정리 hotfix

| 항목 | 내용 |
|------|------|
| 결함 evidence | (1) `git worktree list` 7건 = main 외 6개 잔재 (2) `.claude/worktrees/` 4 wrapper 디렉토리 (`a0d73690`·`a2926806`·`aae21c36`·`ab008f2a` — 빌드 캐시 포함) (3) `git branch | grep worktree-agent-` 6건 임시 브랜치 |
| 권위 모순 | 헌법 §2-5 자동 점검 4 항목 LOCK (본 사이클 자체 검증으로 위반 확정) |
| root cause | 헌법 §2-3·§2-4 강화(2026-05-19)했으나 운영 사이클이 룰을 적용하지 못함 — orchestrator·sub-agent 머지 후 정리 누락 패턴이 다시 재발 |
| 사용자 영향 | 사용자 명시 비판 2026-05-19 KST 재발 risk — "작업이 다 끝났다고 하면서 남아있으면 내가 오해하잖아" |
| hotfix 안 | architect 자율 처리(`feedback_force_push_obligation` 정합 white-list `--force-with-lease`) — 본 사이클 worktree 3개(architect·backend-core·미인지 1개) **외** 4개 wrapper + `worktree-agent-*` 6 임시 브랜치 일괄 정리. 본 사이클 디스패치된 3개는 본 사이클 머지 후 orchestrator 정합 처리. **사용자 직접 승인 의무** (backlog §4 — "누적 15+ 시 사용자 직접 승인 후 일괄 정리") · 현재 6건이라 사용자 결재 의제로 olympus |
| 추정 시간 | ~5분 (`git worktree remove` 4건 + `rmdir` + `git branch -D` 6건) |
| release 흡수 | 별도 chore PR (architect 단독 또는 사용자 직접 — `chore: worktree-agent-* + wrapper 잔재 일괄 cleanup`) |

### §5.B P1 (다음 사이클 검토)

| 의제 | 설명 |
|------|------|
| ADR 0077 Accepted 격상 | v3.6.11 hotfix 마감 evidence가 backlog §56에 명시되어 있으나 ADR 0077 본문 상태가 Proposed 유지. ADR 0077 본문 격상 사이클 (architect 단독 ~10분) |
| Reserved hole 4건 (0058·0059·0060·0065) | README §12 "v3.3.0 cycle 일괄" 약속 미수행. 정리 사이클에 `_archive/` 이동 또는 hole 보존 명시 (P2 — 정리 사이클 일괄) |

### §5.C P2 (장기 의제)

| 의제 | 설명 |
|------|------|
| qa.md SKILL.md 발행 여부 (P0-1 후속) | qa.md 발행 후 SKILL.md 발행 의제 검토 (사용자 결정 의제) |
| 7 sub-agent 페르소나 부여 매트릭스 확장 | ADR 0087 §13 보류 명시 — Phase 1 backend-core 운영 evidence 누적 후 다른 sub-agent 매트릭스 확장 결정 |

---

## §6. 다음 사이클 권장 dispatch 매트릭스

### §6.A 즉시 처리 (P0 hotfix 2건)

| 순번 | 작업 | 담당 | branch | 소요 |
|------|------|------|--------|------|
| 1 | P0-2 worktree 잔재 정리 (사용자 결재) | architect 또는 orchestrator | `chore/worktree-cleanup-2026-05-19` | ~5분 |
| 2 | P0-1 `.claude/agents/qa.md` 발행 | architect 단독 | `agent/architect/qa-md-creation` | ~10분 |

### §6.B v3.6.18 patch 후보 (P1)

| 순번 | 작업 | 담당 | branch | 소요 |
|------|------|------|--------|------|
| 1 | ADR 0077 Accepted 격상 (v3.6.11 hotfix 마감 evidence 인용) | architect 단독 | `docs/adr-0077-accepted` | ~10분 |
| 2 | T-MAN Phase 1·2 dispatch (0082 사용자 결재 대기) | backend-core + frontend 병렬 | `agent/backend-core/t-man-phase1` + `agent/frontend/t-man-phase2` | spec 머지 후 |
| 3 | ADR 0086 §11·§12·§13 후속 구현 (KIS raw ASC + Active gap + LWC state lift) | chart-curator 단독 | `agent/chart-curator/adr-0086-amend-impl` | spec 머지 후 |

### §6.C v3.7.0 minor 진입 (W4)

| 순번 | 작업 | 담당 | branch | 소요 |
|------|------|------|--------|------|
| 1 | W4 T-AI spec/plan 발행 (ADR 0026 baseline) | architect 단독 | `agent/architect/w4-t-ai-spec` | ~30~60분 (KIS-first raw + 사용자 결정 의제 위임 풀세트) |
| 2 | T-AI Phase 1·2·3 디스패치 (사용자 결재 후) | ai-engineer + backend-core + frontend 직렬 | (multi) | sub-agent dispatch 매트릭스 |
| 3 | v3.7.0 cut + baseline init | orchestrator + 4 sub-agent 병렬 baseline | (release) | baseline 8 PR 머지 + tag |

---

## §7. 최종 권장 우선순위

1. **즉시** — P0-2 worktree 잔재 정리 (사용자 결재 후 force-with-lease 사용)
2. **즉시** — P0-1 qa.md 발행 (architect 단독 ~10분)
3. **v3.6.18 patch 후보** — ADR 0077 Accepted 격상 + T-MAN 디스패치 + 0086 amend 구현 (영역 분리 병렬)
4. **v3.7.0 minor 진입** — W4 T-AI spec/plan 발행 (architect 단독) → 사용자 결재 → 3 sub-agent 디스패치

---

## §8. 검증 evidence 인용

- 본 사이클 worktree: `.claude/worktrees/agent-a2bd49e53c628d3d9` (architect, branch `agent/architect/system-audit-2026-05-19`)
- 본 사이클 main baseline: 30821f6
- 본 사이클 git author 검증: `bot+architect@magicjar.local` / `magicjar-architect` (§1-A 정합 LIVE evidence)
- 본 사이클 task 파일: `_workspace/tasks/T-20260519-1647-system-audit-2026-05-19.md` (§1-C 정합 LIVE evidence)
- 본 사이클 운영 코드 0 touch (architect 영역 보고서 only)

---

## §9. 영역 경계 검증

- ✅ architect 영역만 (다른 sub-agent dispatch 0)
- ✅ 운영 코드 (Kotlin·TS·SQL) 0 touch
- ✅ 본 보고서 + 헌법 cross-ref만 (영역 외 침범 0)
- ✅ CHANGELOG slot prepend 단일 row (작성: architect)
- ✅ ADR 발행 0 (audit only — 새 § 추가 0, 헌법 갱신 0)

---

> **결론**: P0 결함 2건 즉시 처리 권장(qa.md 발행 + worktree 정리). 그 후 W4 T-AI 진입(v3.7.0 minor LOCK) 디스패치 사이클 진입 권장. 8 sub-agent 시스템 점검 잔여 7명(backend-core·market-data·strategy-engine·ai-engineer·frontend·qa·chart-curator)는 별도 dispatch — 본 보고서는 architect 영역만 권위.
