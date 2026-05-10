# ADR 0055 — Strategy Builder UI 컴포넌트 스택 결정 (B+C-lite 하이브리드)

- **상태**: Accepted
- **발행일**: 2026-05-10
- **작성자**: architect (opus 4.7 1M)
- **관련 PR**: PR-44 (architect spec/plan + 본 ADR + CHANGELOG 슬롯) → PR-45 (frontend T-SB)
- **트랙**: T-SB (W2)
- **Supersedes**: 없음
- **References**: ADR 0009 (Frontend SPA) · ADR 0022 (Strategy DSL) · ADR 0023 (AI helper 사용자 트리거) · ADR 0040 (관심종목 우선 UI 흐름) · ADR 0050 (CHANGELOG 슬롯) · master-architecture.md §B.8 · tracks-roadmap.md §1·§2 T-SB row · `_workspace/specs/pr-44-w2-t-sb-design.md` §10

---

## §1. 컨텍스트

T-SB(Strategy Builder UI)는 W2 마지막 트랙 — 사용자=최고 관리자/설계자가 코드 없이 GUI 블록을 결합해 매매 룰을 만드는 1차 도구. 마스터 §B.8 정의:

> "**GUI 블록 빌더 1차** (지표 선택·연산자·논리 조합·파라미터·저장) + yaml export/import 동시. 2차 풀 GUI."

블록 빌더 컴포넌트의 기술 스택을 결정해야 한다. 후보 4개:

- A. Reactflow / @xyflow/react 캔버스 (KIS Strategy Builder Python frontend 컨셉 흡수)
- B. 트리 폼 빌더 (재귀 컴포넌트 — DSL AST 1:1 매핑)
- C. Monaco Editor + 자동완성 (코드 직접 편집)
- D. 하이브리드 (블록 + 코드 토글)

기존 frontend 스택(ADR 0009): React 19 + TS + Vite + Tailwind + LWC v4 + TanStack Query + Zustand.

DSL은 sealed class AST(`DslNode`·`Condition`·`CompositeCondition`·`ArithmeticExpr` 4종 + 7+ 분기) — T-DSL PR-36 머지로 backend `KisYamlDslParser` (consumer/.../strategy/) + frontend 호출 endpoint 확정.

---

## §2. 옵션 비교 매트릭스

| 옵션 | 의존 추가 | 학습곡선 | A11y (키보드) | 모바일 | AST 매핑 | bundle 영향 | 모바일 후속 비용 |
|---|---|---|---|---|---|---|---|
| **A. Reactflow / @xyflow/react** | ~150KB gzip 추가 | 중 (graph 모델 학습) | 어려움 (canvas 기반) | 어려움 (touch panning) | 그래프 모델 — AST tree와 mismatch (2D 위치 정보 잉여) | 큼 | rewrite 필수 |
| **B. 트리 폼 빌더 (재귀 컴포넌트)** | 0 | 낮 (React 표준) | 자연 (Tab 탐색) | 자연 (세로 스크롤) | **AST 1:1** | 0 | 자연 전환 |
| **C. Monaco Editor + 자동완성** | ~2MB | 높 (DSL 학습 필수) | 표준 (textarea editor) | 어려움 (mobile keyboard) | 텍스트만 — 시각 0 | 큼 | rewrite 필수 |
| **D. 하이브리드 (블록 + Monaco)** | ~2MB | 중 | 자연 + 표준 | 자연 (트리 only) | AST 1:1 + 텍스트 옵션 | 큼 | 트리만 자연 전환 |
| **★ 채택 — B+C-lite 하이브리드** | 0 | 낮 | 자연 + 표준 | 자연 | **AST 1:1 + textarea 옵션** | 0 | 자연 전환 |

C-lite = textarea + 단순 syntax color (정규식 기반 토큰 highlight). Monaco 풀 IDE 기능 제외.

---

## §3. 결정

**채택**: B+C-lite 하이브리드 — 트리 폼 빌더(B) 본체 + textarea DSL 직접 편집 토글(C-lite, Monaco 제외).

### 3-1. LOCK 항목

1. **의존 추가 0건** — `frontend/package.json`에 Reactflow·@xyflow/react·React DnD·Monaco Editor 추가 금지. 기존 React 19 + Tailwind만 사용.
2. **AST 1:1 트리 폼 빌더** — DSL `DslNode` sealed class 8 분기를 재귀 컴포넌트로 시각화 (`BlockNode.tsx` 본 PR-45 신설). entry/exit 두 트리 토글.
3. **DSL 텍스트 토글** — 사용자가 "DSL 직접 편집" 클릭 시 textarea 모드 전환. parse → 트리 재구성.
4. **단순 syntax color** — Monaco 미사용. textarea overlay 정규식 highlight (토큰별 span 컬러). post-MVP에 Monaco 검토 가능 (사용자 요청 시).
5. **YAML export/import** — `.kis.yaml` 다운로드/업로드 동시 지원 (마스터 §B.8 의무).
6. **자연어 helper** — T-DSL `/api/dsl/translate-from-natural` (이미 머지) 사용자 트리거 버튼만. ADR 0023 정합 (매매 결정권 0).
7. **데스크톱 위주** — 1280px+ 정상. 768px 이하는 "데스크톱 권장" 안내 (mobile 폼 전환은 후속 PR).
8. **키보드 접근성** — Tab 노드 탐색 + Enter 추가 + Delete 제거. 마우스 의존 0 시나리오 GREEN.

### 3-2. 컴포넌트 LOCK

```
frontend/src/components/strategy/
├─ BlockPalette.tsx              // 좌측 80지표·57패턴·캔들·연산자·상수 4 탭
├─ BlockNode.tsx                 // 재귀 컴포넌트 (8 분기 — Indicator·Literal·Candle·Pattern·Comparison·Logical·Arithmetic·Not)
├─ StrategyBuilderCanvas.tsx     // entry/exit 두 트리 + DSL 미리보기 readonly
├─ DslTextEditor.tsx             // textarea + 단순 syntax color (Monaco 미사용)
├─ NaturalLanguageInput.tsx      // modal — translate-from-natural helper
├─ EvaluationResultCard.tsx      // signal/strength/reason + Watchlist 추가
├─ ComplexityCard.tsx            // 4단 배지 (LOW/MEDIUM/HIGH/CRITICAL)
└─ StrategySaveModal.tsx         // name·nameKo·universe·tf 저장
```

### 3-3. lib/dsl 직렬화 LOCK

```
frontend/src/lib/dsl/
├─ blockTree.ts    // BlockNode 타입 + Tree 구조
├─ serializer.ts   // BlockTree → DSL 텍스트 (backend KisYamlDslParser 화이트리스트 1:1)
├─ parser.ts       // DSL 텍스트 → BlockTree (yaml import + AI translate 응답용)
└─ yaml.ts         // .kis.yaml export/import
```

---

## §4. 거부 옵션 + 사유

### 4-1. A. Reactflow / @xyflow/react 캔버스 — **거부**

- **의존 무게**: ~150KB gzip 추가. magicJar는 LWC v4(이미 포함) 외 frontend bundle 최소화 정책.
- **AST mismatch**: DSL은 tree 구조(parent-child만). 캔버스는 graph 모델(임의 edge + 2D 위치) — 위치 정보가 직렬화에 잉여. 트리 → graph 변환 + graph → 트리 역변환 보일러플레이트.
- **모바일 어려움**: touch panning + zoom. mobile 후속 전환 시 rewrite 필수.
- **키보드 접근성 ↓**: canvas 기반이라 Tab 탐색 어려움.
- **사용자 명시 요청 시 재검토**: 사용자가 "drag&drop 시각 빌더가 더 직관적이다" 요청 시 W3 후 별도 사이클 + ADR 0055 갱신.

### 4-2. C. Monaco Editor 풀 IDE — **거부**

- **의존 무게**: ~2MB. frontend bundle 큰 영향.
- **DSL 단순성**: 1줄 표현식이 표준 (entry/exit 각 1줄). 풀 IDE 기능(go-to-definition·refactor·multi-cursor) 잉여.
- **사용자=최고 관리자/설계자 친화**: 메모리 `project_system_vision.md` 정합 — 사용자가 코드 친화적이지만 DSL은 단순 → textarea + 단순 syntax color로 충분.
- **post-MVP 검토**: DSL이 다중 줄·풀 스크립트로 확장 시 Monaco 채택 검토.

### 4-3. D. 풀 하이브리드 (B + Monaco) — **거부**

- C와 동일 사유 — Monaco ~2MB 무게 회피. C-lite로 충분.

---

## §5. 채택 사유 (B+C-lite)

1. **의존 무게 회피**: ADR 0009 (Frontend SPA) 핵심 의존만 사용. bundle 증가 0.
2. **AST 1:1 자연성**: DSL sealed class AST를 그대로 시각화 — 직렬화·역직렬화 단순.
3. **키보드 접근성**: Tab/Enter/Delete 표준 — 사용자=최고 관리자가 키보드 친화.
4. **모바일 후속 자연**: 트리 폼은 narrow viewport에서 세로 스크롤 자연 전환.
5. **마스터 §B.8 정합**: "**GUI 블록 빌더 1차 골격** + yaml export/import 동시" — 트리 폼이 정확히 그 명세. 풀 GUI는 2차(post-W2).
6. **사용자 메모리 정합**:
   - `project_system_vision.md`: 룰 기반 본체 + AI 옵션 보조 → AI는 helper만, GUI는 룰 본체 도구.
   - `feedback_no_mock_data.md`: evaluate 0건 시 "데이터 없음" 명시 — 단순 컴포넌트로 충분.
   - `feedback_orchestrator_active_parallel.md`: 의존 무게 ↑ 시 PR fragmentation 위험 ↑.
7. **PR-45 단일 sub-agent 적합**: frontend 단독 add-only로 마감 가능 (~2890 LOC). 의존 추가 시 `package.json` + `pnpm-lock.yaml` 영역 관리 추가 부담.
8. **CI 영향 0**: 의존 추가 0이라 CI matrix 정합 보존.

---

## §6. 결과

### 6-1. 영향 받는 영역

- **frontend/src/**: 신규 디렉토리 4건 (`api/`·`api/hooks/`·`lib/dsl/`·`components/strategy/`·`pages/Strategy/`) + `App.tsx` Route 1줄 + `AppHeader.tsx` 메뉴 1줄.
- **frontend/package.json**: 변경 0 (의존 추가 0 LOCK).
- **backend·domain·gradle·docker**: 영향 0.

### 6-2. 정합 의무

- DSL 토큰 화이트리스트 — backend `KisYamlDslParser` (consumer/.../strategy/) main LOCK. serializer.ts 토큰 ENUM 1:1.
- 80지표 INDICATOR_CATALOG — 기존 frontend `api/indicator.ts` (T-IND R3 PR-33 머지) 80건 보존.
- 57패턴 PATTERN_CATALOG — backend `domain/.../pattern/PatternCatalog.kt` 57건 1:1로 frontend `api/pattern.ts` 신설.
- AI translate helper — 기존 backend `/api/dsl/translate-from-natural` (T-DSL PR-36 ai-engineer 머지) 호출만. frontend src 본체 LLM provider import 0건 (룰 5).

### 6-3. 후속 검토 트리거 (UNCERTAIN)

- 사용자가 "drag&drop 시각 빌더가 더 직관적이다" 명시 요청 시 → ADR 0055 갱신 + Reactflow 채택 사이클.
- DSL이 다중 줄·풀 스크립트로 확장 시(post-MVP) → Monaco 채택 검토.
- mobile 진입 빈도 ↑ 시 → mobile 폼 전환 후속 PR (트리 폼 자연 전환 시나리오 검증).
- catalog endpoint 단일화(frontend 정적 → backend GET) — backlog §N 후속 사이클 (T-SB 머지 후).

---

## §7. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)

- ✓ **자동매매에 필요한가?** — 룰 본체 작성 도구. 핵심.
- ✓ **v1/v2 잔재 아닌가?** — v1/v2 시점 GUI 0. v3 W2 신규.
- ✓ **AI 호출 0 (룰 5)** — frontend src 본체 LLM import 0건. translate hook은 backend endpoint fetch만.
- ✓ **mock 데이터 0** — evaluate 0건/에러 시 "데이터 없음" 명시.
- ✓ **KIS-first (헌법 §3)** — KIS API 직접 호출 0. 80지표/57패턴/DSL 토큰은 기존 raw verification(T-IND·T-DSL·T-PAT) 1:1 정합.

---

작성: architect (opus 4.7 1M)  
작성일: 2026-05-10
