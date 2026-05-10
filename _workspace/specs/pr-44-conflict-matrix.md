# PR-44 Conflict Matrix — modify-shared 0건 + add-only 100% 검증

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-10
- 본 conflict matrix는 PR-44 (architect spec/ADR/CHANGELOG) ↔ PR-45 (frontend T-SB) 두 PR의 충돌 가능성을 LOCK.

---

## §1. 결론

**시나리오 — 직렬 채택**: PR-44 머지 → PR-45 머지. modify-shared 충돌 가능성 검토 결과 **0건** (PR-44는 docs/spec 단독, PR-45는 frontend src 단독 — 영역 깔끔 분리).

병렬 디스패치 가능 시나리오는 §3 검토.

---

## §2. PR-44 ↔ PR-45 modify-shared 매트릭스

| 파일 | PR-44 | PR-45 | 충돌? | 비고 |
|---|---|---|---|---|
| `_workspace/specs/pr-44-*.md` | ★ 신규 | - | 없음 | architect 단독 add-only |
| `_workspace/arch/adr/0055-*.md` | ★ 신규 | - | 없음 | architect 단독 add-only |
| `_workspace/arch/adr/README.md` | ★ 수정 (ADR 0055 row + §11 후속) | - | 없음 | architect 단독 |
| `docs/architecture/master-architecture.md` | ★ 수정 (§10 ADR 0055 row) | - | 없음 | architect 단독 |
| `CHANGELOG.md` | ★ 수정 (PR-44/45 slot row 사전 prepend) | ★ 수정 (PR-45 frontend slot 채움) | **시간차 modify** | PR-44 머지 후 PR-45가 main rebase 시 자연 인지 — slot row anchor 위치 변동 0이라 충돌 0. ADR-0050 정합. |
| `_workspace/tasks/T-*pr-44*.md` | ★ 자동 (task.mjs) | - | 없음 | architect 단독 |
| `_workspace/tasks/T-*pr-45*.md` | - | ★ 자동 | 없음 | frontend 단독 |
| `frontend/src/api/strategy.ts` | - | ★ 신규 | 없음 | frontend add-only |
| `frontend/src/api/pattern.ts` | - | ★ 신규 | 없음 | frontend add-only |
| `frontend/src/api/dslTranslate.ts` | - | ★ 신규 | 없음 | frontend add-only |
| `frontend/src/api/hooks/useStrategy.ts` | - | ★ 신규 | 없음 | frontend add-only |
| `frontend/src/api/hooks/useStrategyTranslate.ts` | - | ★ 신규 | 없음 | frontend add-only |
| `frontend/src/lib/dsl/*` (4 파일 + 3 테스트) | - | ★ 신규 | 없음 | frontend add-only — 신규 디렉토리 |
| `frontend/src/components/strategy/*` (8 컴포넌트 + 3 테스트) | - | ★ 신규 | 없음 | frontend add-only — 신규 디렉토리 |
| `frontend/src/pages/Strategy/*` (1 페이지 + 1 테스트) | - | ★ 신규 | 없음 | frontend add-only — 신규 디렉토리 |
| `frontend/src/App.tsx` | - | ★ 수정 (Route 1줄) | 없음 | PR-45 단독 |
| `frontend/src/components/layout/AppHeader.tsx` | - | ★ 수정 (메뉴 1줄) | 없음 | PR-45 단독 |

**결론**: PR-44 ↔ PR-45 modify-shared 충돌 가능 파일은 `CHANGELOG.md` 1건뿐 — slot anchor 정합으로 자연 해소 (PR-44가 사전 발행, PR-45가 채움).

---

## §3. 병렬 디스패치 가능성 (시나리오 검토)

### 3-1. 시나리오 A — 완전 병렬 (PR-44 + PR-45 동시 발사)

**가능 여부**: 이론적 가능, 실질적 비권장.

- **이론**: PR-45는 frontend src 단독이라 PR-44의 `_workspace/specs/*.md`·`_workspace/arch/adr/*.md`·`docs/architecture/master-architecture.md` 어떤 파일에도 의존 0. spec 미머지 상태에서도 frontend가 코드 작성 시작 가능.
- **비권장 사유**: 
  1. PR-45 frontend는 `_workspace/specs/pr-44-w2-t-sb-design.md` DoD 26 + `pr-44-w2-t-sb-plan.md` LOC 분배 + `pr-44-dispatch-spec.md` 영역 LOCK + ADR 0055 결정을 명시 인용해야 함. spec 미머지면 sub-agent context에 spec 파일이 main에 없어 PR 본문 인용 형식 깨짐.
  2. CHANGELOG slot row 사전 발행이 PR-44에서 일어남 — PR-45가 자기 slot 채우려면 PR-44 머지 후 rebase 필수.
  3. ADR 0055 (B+C-lite 하이브리드 결정)이 PR-45 frontend에게 의존 추가 0 LOCK 의무 부여 — 미머지 시 sub-agent가 임의로 Reactflow 추가하는 위험.
- **결론**: 시나리오 A 거부 — 안전성 ↓.

### 3-2. 시나리오 B — 순차 (★ 채택)

**채택 사유**: 

1. PR-44 머지 후 PR-45 디스패치 — sub-agent context에 spec/plan/ADR/CHANGELOG slot이 main에 존재 → 인용·rebase·slot 채움 자연.
2. wallclock 영향 미미 — PR-44 머지 ~5분 + 디스패치 ~3-4시간이라 전체 ~4-5시간 (시나리오 A와 동일 수준).
3. 사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합 — "직렬 권장 답습 금지" 룰은 **동일 영역 충돌 시**에만 적용. PR-44/PR-45는 영역 분리 + spec 의존 관계 → 직렬이 자연.

---

## §4. 빌드 안전 매트릭스

### 4-1. PR-44 단독 머지 후 main 컴파일

| 모듈 | 영향 | 사유 |
|---|---|---|
| `domain` | 0 | 변경 0 |
| `api` | 0 | 변경 0 |
| `consumer` | 0 | 변경 0 |
| `batch` | 0 | 변경 0 |
| `pattern-engine` | 0 | 변경 0 |
| `frontend` | 0 | 변경 0 |
| docs/specs/CHANGELOG | 변경 | docs only — 컴파일 비대상 |

**결론**: PR-44 단독 머지 시 main 빌드 GREEN 보장.

### 4-2. PR-45 단독 머지 후 main 컴파일

| 모듈 | 영향 | 사유 |
|---|---|---|
| JVM 5 모듈 | 0 | 변경 0 |
| `frontend` | 변경 | tsc + vite build GREEN 의무 (CI matrix 정합) |

**결론**: PR-45 단독 머지 시 main 빌드 GREEN 보장 (frontend `pnpm typecheck`·`pnpm test`·`pnpm build` GREEN 후).

---

## §5. 외부 의존 정합

### 5-1. PR-45가 의존하는 main 상태 (PR-44 무관 — 모두 기존 머지)

- T-DSL backend 7 endpoint (PR-36 머지 — 2026-05-09)
- T-DSL ai-engineer translate endpoint (PR-36 머지 — 2026-05-09)
- T-IND frontend INDICATOR_CATALOG 정적 (PR-33 R3 머지 — 2026-05-09)
- T-PAT 57패턴 backend domain LOCK (PR-39 머지 — 2026-05-10)
- T-W1 watchlist endpoint + useAddWatchlist hook (W1 머지 — 2026-05-08)
- pattern-engine 모듈 분리 (PR-42·43 머지 — 2026-05-10)

**결론**: PR-45 디스패치 시점에 모든 외부 의존이 main에 존재. 인터페이스 변경 0.

### 5-2. PR-45 추가 의존 (npm 패키지)

**0건** — ADR 0055 §3 LOCK (의존 추가 금지). 기존 `package.json` (`react`·`react-dom`·`react-router-dom`·`@tanstack/react-query`·`zustand`·`axios`·`lightweight-charts`·`tailwindcss`)만 사용.

---

## §6. CHANGELOG anchor 정합 (ADR-0050 LOCK)

PR-44가 CHANGELOG.md에 사전 prepend할 슬롯 형식:

```markdown
| 2026-05-10 | **arch: PR-44 — W2 T-SB 진입 spec/plan + ADR 0055 + master/README 인덱스 갱신 + CHANGELOG 슬롯 사전 발행 (architect)** ... | docs/spec/adr | PR-44 spec/plan + ADR 0055 신규 |
<!-- pr-44-slot-architect -->
| 2026-05-10 | TBD ... (작성: frontend, PR-45) — TBD | frontend | PR-45 W2 T-SB frontend |
<!-- pr-44-slot-frontend -->
<!-- pr-44-slot-end -->
```

PR-45 작성자(frontend sub-agent)가 자기 row를 채워 본문 갱신.

---

## §7. fitness gap PR 본문 의무

PR-44 본문에는 사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합 fitness gap 섹션 의무. `pr-44-w2-t-sb-design.md` §13 인용 + 5축 GREEN 표시.

PR-45 본문에도 동일 5축 GREEN 표시 의무.

---

## §8. 영역 침범 detection 체크리스트 (orchestrator 머지 직전 의무)

### 8-1. PR-44 머지 직전 검증

- [ ] PR diff에서 `frontend/`·`api/`·`consumer/`·`batch/`·`domain/`·`pattern-engine/` 변경 0건 확인
- [ ] `_workspace/specs/pr-44-*.md` 4건 신규 add 확인
- [ ] `_workspace/arch/adr/0055-*.md` 1건 신규 add 확인
- [ ] `_workspace/arch/adr/README.md` ADR 0055 row + §11 후속 ADR 0056부터 갱신 확인
- [ ] `docs/architecture/master-architecture.md` §10 ADR 0055 row 추가 확인
- [ ] `CHANGELOG.md` PR-44/45 slot row + HTML anchor 사전 prepend 확인
- [ ] `_workspace/tasks/T-*pr-44*.md` task.mjs 자동 갱신 확인
- [ ] author email = `bot+architect@magicjar.local` 확인

### 8-2. PR-45 머지 직전 검증

- [ ] PR diff에서 `domain/`·`api/`·`consumer/`·`batch/`·`pattern-engine/` 변경 0건 확인
- [ ] PR diff에서 `frontend/package.json` 의존 추가 0건 확인 (ADR 0055 §3 LOCK)
- [ ] PR diff에서 `frontend/src/{api,api/hooks,lib/dsl,components/strategy,pages/Strategy}/*` add-only + `App.tsx` 1줄 + `AppHeader.tsx` 1줄만 확인
- [ ] `pnpm typecheck`·`pnpm test`·`pnpm build` GREEN 확인
- [ ] LLM provider import 0건 확인 (`grep -rE "anthropic|openai|spring-ai" frontend/src` → 0)
- [ ] `INDICATOR_CATALOG.length === 80`·`PATTERN_CATALOG.length === 57` 확인
- [ ] DSL serializer round-trip 5 fixture GREEN 확인
- [ ] DoD 26개 PR 본문 GREEN/PENDING 마킹 확인
- [ ] CHANGELOG.md PR-45 frontend slot 채움 확인
- [ ] author email = `bot+frontend@magicjar.local` 확인

---

## §9. 결론

- modify-shared 충돌: **0건** (CHANGELOG.md slot anchor 자연 해소)
- 빌드 안전 매트릭스: **GREEN** (각 PR 단독 머지 시 main 컴파일 GREEN)
- 머지 순서: **직렬 LOCK** (PR-44 → PR-45)
- 디스패치 시점: **PR-44 머지 후 즉시** (orchestrator 책임)

---

작성: architect (opus 4.7 1M)  
작성일: 2026-05-10
