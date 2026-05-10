# PR-44 Dispatch Spec — sub-agent 영역 LOCK + 인터페이스 동결 + 머지 정책

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-10
- 본 dispatch spec은 PR-44 (architect spec/plan + ADR 0055 + CHANGELOG 슬롯) 머지 후 PR-45 (frontend T-SB) 디스패치 명세를 LOCK한다.

---

## §1. PR 영역 분배 LOCK

### 1-1. PR-44 (본 PR — architect)

**영역 LOCK** (수정 가능 파일):

```
_workspace/specs/pr-44-w2-t-sb-design.md           [신규]
_workspace/specs/pr-44-w2-t-sb-plan.md             [신규]
_workspace/specs/pr-44-dispatch-spec.md            [신규]
_workspace/specs/pr-44-conflict-matrix.md          [신규]
_workspace/arch/adr/0055-strategy-builder-component-stack.md  [신규]
_workspace/arch/adr/README.md                      [수정 — ADR 0055 row 추가 + §11 후속 ADR 0056부터]
docs/architecture/master-architecture.md           [수정 — §10 ADR 0055 row 추가]
CHANGELOG.md                                       [수정 — PR-44/45 slot row 사전 prepend + HTML anchor]
_workspace/tasks/T-20260510-1921-pr-44-w2-t-sb.md  [task.mjs 자동 갱신]
```

**금지 영역**: `frontend/`·`api/`·`consumer/`·`batch/`·`domain/`·`pattern-engine/`·`build.gradle.kts`·`settings.gradle.kts` 모두 수정 0.

### 1-2. PR-45 (frontend — frontend sub-agent)

**영역 LOCK** (수정 가능 파일):

```
frontend/src/api/strategy.ts                       [신규]
frontend/src/api/pattern.ts                        [신규]
frontend/src/api/dslTranslate.ts                   [신규]
frontend/src/api/hooks/useStrategy.ts              [신규]
frontend/src/api/hooks/useStrategyTranslate.ts     [신규]
frontend/src/lib/dsl/blockTree.ts                  [신규]
frontend/src/lib/dsl/serializer.ts                 [신규]
frontend/src/lib/dsl/parser.ts                     [신규]
frontend/src/lib/dsl/yaml.ts                       [신규]
frontend/src/lib/dsl/__tests__/serializer.test.ts  [신규]
frontend/src/lib/dsl/__tests__/parser.test.ts      [신규]
frontend/src/lib/dsl/__tests__/yaml.test.ts        [신규]
frontend/src/components/strategy/BlockPalette.tsx          [신규]
frontend/src/components/strategy/BlockNode.tsx             [신규]
frontend/src/components/strategy/StrategyBuilderCanvas.tsx [신규]
frontend/src/components/strategy/DslTextEditor.tsx         [신규]
frontend/src/components/strategy/NaturalLanguageInput.tsx  [신규]
frontend/src/components/strategy/EvaluationResultCard.tsx  [신규]
frontend/src/components/strategy/ComplexityCard.tsx        [신규]
frontend/src/components/strategy/StrategySaveModal.tsx     [신규]
frontend/src/components/strategy/__tests__/BlockNode.test.tsx              [신규]
frontend/src/components/strategy/__tests__/StrategyBuilderCanvas.test.tsx  [신규]
frontend/src/components/strategy/__tests__/EvaluationResultCard.test.tsx   [신규]
frontend/src/pages/Strategy/StrategyBuilderPage.tsx                        [신규]
frontend/src/pages/Strategy/__tests__/StrategyBuilderPage.test.tsx         [신규]
frontend/src/App.tsx                              [수정 — Route 1줄 add]
frontend/src/components/layout/AppHeader.tsx     [수정 — 메뉴 1줄 add]
CHANGELOG.md                                       [수정 — PR-45 frontend slot 채움]
_workspace/tasks/T-*-pr-45-*.md                    [task.mjs 자동]
```

**금지 영역**: `domain/`·`api/`·`consumer/`·`batch/`·`pattern-engine/`·`build.gradle.kts`·`settings.gradle.kts`·`frontend/package.json`(의존 추가 금지 — ADR 0055 §3 LOCK)·`docker/`.

---

## §2. 인터페이스 동결 (frontend → backend 호출 명세)

PR-45 frontend가 호출할 backend REST endpoint는 **모두 main 머지 완료 + 시그니처 LOCK** (수정 0건):

### 2-1. T-DSL backend (PR-36 머지 — `api/.../controller/StrategyController.kt`)

| Method | URL | Request Body | Response | 시그니처 LOCK |
|---|---|---|---|---|
| POST | `/api/strategy` | `CreateStrategyRequest` (userId·name·nameKo·entry·exit·universe·tf) | `CreateStrategyResponse` (strategyId·complexity) | StrategyController.kt L68-90 |
| GET | `/api/strategy?userId=` | - | `List<StrategyResponse>` | L99-103 |
| GET | `/api/strategy/{id}` | - | `StrategyResponse` | L117-120 |
| DELETE | `/api/strategy/{id}` | - | 204 | L140 |
| POST | `/api/strategy/validate` | `ValidateRequest` (expr) | `StrategyComplexityDto` | L162-169 |
| PUT | `/api/strategy/{id}/enable` | - | `StrategyResponse` | L184-192 |
| POST | `/api/strategy/{id}/evaluate` | `EvaluateRequest` (code·interval) | `EvaluateResponse` (strategyId·signal·strength·reason·evalTimeMs) | L209-224 |

### 2-2. T-DSL ai-engineer (PR-36 머지 — `api/.../ai/dsl/DslTranslateController.kt`)

| Method | URL | Request Body | Response | 시그니처 LOCK |
|---|---|---|---|---|
| POST | `/api/dsl/translate-from-natural` | `DslTranslateRequest` (text @NotBlank @Size(max=1000)) | `DslTranslateResponse` (mode·dslEntry?·dslExit?·reasoning?) | DslTranslateController.kt |

### 2-3. T-W1 backend (이미 머지 — frontend 기존 `useAddWatchlist` 재사용)

| Method | URL | Request Body | Response |
|---|---|---|---|
| POST | `/api/watchlist` | `WatchlistAddRequest` (code·nameKo) | `WatchlistEntryDto` |
| GET | `/api/watchlist` | - | `List<WatchlistEntryDto>` |
| DELETE | `/api/watchlist/{code}` | - | 204 |

---

## §3. DSL 토큰 화이트리스트 (자체 추론 0)

PR-45 frontend serializer는 **backend `KisYamlDslParser` 화이트리스트 1:1 정합**. 자체 추가 토큰 0.

매트릭스: `pr-44-w2-t-sb-design.md` §6 LOCK 인용. serializer.ts 토큰 ENUM 의무.

---

## §4. 머지 정책

### 4-1. 머지 정책 (헌법 §5 + CLAUDE.md 정합)

- **rebase merge** 의무 — squash 금지 (author email 화이트리스트 정합)
- author email: `bot+architect@magicjar.local` (PR-44) / `bot+frontend@magicjar.local` (PR-45)
- 브랜치: `agent/architect/pr-44-w2-t-sb` (PR-44) / `agent/frontend/pr-45-w2-t-sb` (PR-45)
- PR base: `main`
- PR 직전 의무: `git fetch origin main && git rebase origin/main`

### 4-2. 머지 순서 LOCK

```
PR-44 (architect spec/ADR/CHANGELOG 슬롯) — 1차 머지
    ↓ (즉시 디스패치)
PR-45 (frontend) — 2차 머지
    ↓
W2 마감 → W3 진입
```

PR-44는 코드 0건이라 main 컴파일 영향 0. PR-45는 frontend 단독 add-only — main에 backend·domain 변경 0이므로 머지 순서 강제 0이지만, 디스패치는 PR-44 머지 후 시작이 합리(spec 참조).

### 4-3. CI 검증 게이트

- **PR-44**: `_workspace/`·`docs/`·`CHANGELOG.md` 변경만이라 GitHub Actions 0건이 정상(ADR 0049). pre-push에서 `./gradlew check`는 캐시 hit pass. `pnpm test/build`는 frontend src 변경 0이라 캐시 hit pass.
- **PR-45**: pre-push `./gradlew check` GREEN (JVM 변경 0이라 캐시) + `pnpm typecheck`·`pnpm test`·`pnpm build` GREEN 의무.

---

## §5. KIS-first 의무 발동 매트릭스

| 영역 | KIS-first raw fetch 발동 여부 | 사유 |
|---|---|---|
| PR-44 architect spec | **발동 0** | KIS API 직접 호출 0 — frontend가 magicJar `/api/*`만 호출. 80지표/57패턴 catalog는 backend domain LOCK + frontend 정적(T-IND R3 기존)에서 파생 — 코드 추론 비대상. |
| PR-45 frontend | **발동 0** | 동일 — KIS API 직접 호출 0. |
| 80지표 INDICATOR_CATALOG | **기존 LOCK** | T-IND R3 PR-33 머지 시 `_workspace/strategy/indicator-catalog.md` (KIS indicators.py 1507 lines raw fetch — market-data 책임) 정합. PR-45는 그 카탈로그 정적 데이터 그대로 사용. |
| 57패턴 PATTERN_CATALOG | **기존 LOCK** | T-PAT PR-39 머지 시 `_workspace/specs/pr-38-kis-pattern-raw-verification.md` (KIS candlestick.py 755 lines raw fetch — architect 책임) 정합. PR-45는 backend `domain/.../pattern/PatternCatalog.kt` 1:1 정합 정적 데이터. |
| DSL 토큰 화이트리스트 | **기존 LOCK** | T-DSL PR-35 머지 시 `_workspace/specs/pr-35-kis-dsl-raw-verification.md` (KIS parser.py 664 lines raw fetch — architect 책임) 정합. PR-45 serializer는 그 화이트리스트 1:1. |

**결론**: PR-44/PR-45 사이클은 KIS-first raw fetch **신규 발동 0**. 기존 raw verification 3건(T-IND·T-DSL·T-PAT) 인용만. 헌법 §3 GREEN.

---

## §6. CHANGELOG 슬롯 (헌법 §4 ADR-0050 정합)

PR-44는 신규 트랙 진입 spec/plan PR이므로 sub-agent 슬롯 row + anchor 사전 prepend 의무. 본 dispatch spec이 명시:

| 슬롯 | 위치 | 작성자 | 내용 |
|---|---|---|---|
| pr-44-slot-architect | CHANGELOG.md 1번째 row | architect (PR-44 본 PR) | spec/plan + ADR 0055 + master/README 인덱스 갱신 + CHANGELOG 슬롯 발행 |
| pr-44-slot-frontend | CHANGELOG.md 2번째 row | frontend (PR-45 코드) | T-SB frontend 코드 (트리 폼 빌더 + DSL serializer/parser + YAML import/export + validate/evaluate/save flow + AI translate helper + Watchlist 추가 + 단위 테스트 5종 + 컴포넌트 테스트 3종 + 페이지 테스트 1종) |
| pr-44-slot-end | CHANGELOG.md 3번째 row | - | end marker (HTML 주석) |

PR-44 본문에 architect slot 채움. PR-45 본문에 frontend slot 채움.

---

## §7. 영역 침범 detection (PR diff 검증 의무)

각 PR 머지 직전, orchestrator는 PR diff를 일독하여 영역 침범 0 검증:

- PR-44 PR diff: `_workspace/specs/pr-44-*.md` + `_workspace/arch/adr/0055-*.md` + `_workspace/arch/adr/README.md` + `docs/architecture/master-architecture.md` + `CHANGELOG.md` + `_workspace/tasks/T-*pr-44*.md`만이어야 함. frontend·backend·domain 변경 발견 시 reject.
- PR-45 PR diff: `frontend/src/{api,api/hooks,lib/dsl,components/strategy,pages/Strategy}/*` + `frontend/src/App.tsx` + `frontend/src/components/layout/AppHeader.tsx` + `CHANGELOG.md` + `_workspace/tasks/T-*pr-45*.md`만이어야 함. backend·domain·gradle 변경 발견 시 reject.

---

## §8. fitness gap 매트릭스 (사용자 메모리 정합)

본 사이클은 사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합으로 PR 본문에 fitness gap 섹션 명시 의무:

- ✓ 자동매매에 필요한가? — T-SB는 룰 본체 작성 도구. 핵심.
- ✓ v1/v2 잔재 아닌가? — v1/v2 시점 GUI 0. v3 W2 신규.
- ✓ AI 호출 0 (룰 5)? — frontend 본체 LLM import 0건. translate hook은 backend endpoint fetch만.
- ✓ mock 데이터 0 (메모리)? — evaluate 0건/에러 시 "데이터 없음" 명시.
- ✓ KIS-first (헌법 §3)? — KIS API 직접 호출 0. raw fetch 발동 0.

---

## §9. 디스패치 시점 + 명령 템플릿

PR-44 머지 직후, orchestrator가 frontend sub-agent에게 다음 명령으로 디스패치:

```
오케스트라가 frontend에게 PR-45 디스패치:

브랜치: agent/frontend/pr-45-w2-t-sb
goal: "PR-45 W2 T-SB frontend — 트리 폼 빌더 + DSL serializer/parser + YAML import/export + validate/evaluate/save flow + AI translate helper + Watchlist 추가"
deliverables: "api/strategy.ts, api/pattern.ts, api/dslTranslate.ts, api/hooks/useStrategy.ts, api/hooks/useStrategyTranslate.ts, lib/dsl/blockTree+serializer+parser+yaml + 단위 테스트 3, components/strategy/BlockPalette+BlockNode+StrategyBuilderCanvas+DslTextEditor+NaturalLanguageInput+EvaluationResultCard+ComplexityCard+StrategySaveModal + 컴포넌트 테스트 3, pages/Strategy/StrategyBuilderPage + 페이지 테스트 1, App.tsx Route 1줄, AppHeader 메뉴 1줄, CHANGELOG slot 채움"
spec: _workspace/specs/pr-44-w2-t-sb-design.md (DoD 26개 LOCK)
plan: _workspace/specs/pr-44-w2-t-sb-plan.md (LOC 분배 LOCK)
dispatch-spec: _workspace/specs/pr-44-dispatch-spec.md (영역 + 인터페이스 LOCK)
conflict-matrix: _workspace/specs/pr-44-conflict-matrix.md
ADR-0055: _workspace/arch/adr/0055-strategy-builder-component-stack.md (B+C-lite 하이브리드 채택)
검증: pnpm typecheck + pnpm test + pnpm build GREEN + LLM import 0 + DSL serializer round-trip 5 fixture + DoD 26 GREEN/PENDING 마킹
```

---

작성: architect (opus 4.7 1M)  
작성일: 2026-05-10
