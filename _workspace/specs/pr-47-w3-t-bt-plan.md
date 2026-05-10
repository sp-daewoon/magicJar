# PR-47 W3 T-BT plan — 분할 + 머지 동시성

> **상태**: plan LOCK (architect 발행 — PR-47)
> **작성일**: 2026-05-11
> **연관 spec**: `pr-47-w3-t-bt-design.md`
> **연관 ADR**: 0050(CHANGELOG 슬롯) · 0034(rebase 머지)

---

## §1. 분량 추정

### 1-1. PR-48 backend-core (메인) — ~1500 LOC

| 파일 영역 | 신규 파일 수 | LOC |
|---|---|---|
| **domain backtest 도메인** | 11 파일 | ~350 |
| `domain/backtest/BacktestRequest.kt` (data class + PriceAdjustmentMode + SizingPolicy sealed) | 1 | 60 |
| `domain/backtest/BacktestResult.kt` (sealed Success·Aborted) | 1 | 70 |
| `domain/backtest/BacktestPosition.kt` | 1 | 30 |
| `domain/backtest/BacktestTrade.kt` (+ TradeSide enum) | 1 | 50 |
| `domain/backtest/BacktestPerformance.kt` | 1 | 50 |
| `domain/backtest/port/BacktestEngine.kt` | 1 | 20 |
| `domain/backtest/port/BacktestTaxModel.kt` | 1 | 25 |
| `domain/backtest/port/BacktestCommissionModel.kt` | 1 | 25 |
| `domain/backtest/port/BacktestSlippageModel.kt` | 1 | 25 |
| `domain/backtest/BacktestException.kt` (sealed) | 1 | 35 |
| `domain/backtest/LookaheadGuard.kt` (utility 또는 inline check helper) | 1 | 60 |
| **api 어댑터** | 8 파일 | ~700 |
| `api/.../controller/BacktestController.kt` (Swagger 4단 + DTO 7건) | 1 | 250 |
| `api/.../service/backtest/BacktestService.kt` | 1 | 180 |
| `api/.../service/backtest/JvmBacktestEngine.kt` (구현) | 1 | 220 |
| `api/.../service/backtest/tax/KoreanStockTaxModel.kt` | 1 | 50 |
| `api/.../service/backtest/commission/KisDirectCommissionModel.kt` | 1 | 40 |
| `api/.../service/backtest/slippage/FixedRateSlippageModel.kt` | 1 | 30 |
| `api/.../service/backtest/BacktestModelRegistry.kt` (모델 lookup) | 1 | 50 |
| `api/.../service/backtest/BacktestEvaluatorBridge.kt` (consumer 모듈의 JvmStrategyEvaluator 호출 bridge — 단순 위임) | 1 | 80 |
| **단위 테스트** | 7 파일 | ~450 |
| `BacktestControllerTest.kt` | 1 | 80 |
| `BacktestServiceTest.kt` | 1 | 60 |
| `JvmBacktestEngineTest.kt` (룩어헤드 가드 5 케이스 negative + 정상 시뮬 시나리오 5건) | 1 | 150 |
| `KoreanStockTaxModelTest.kt` | 1 | 40 |
| `KisDirectCommissionModelTest.kt` | 1 | 40 |
| `FixedRateSlippageModelTest.kt` | 1 | 30 |
| `BacktestPerformanceCalculatorTest.kt` (총수익·CAGR·MDD·Sharpe·승률·profit factor·매매빈도 케이스 7건) | 1 | 50 |
| **합계** | **26 파일** | **~1500 LOC** |

### 1-2. PR-49 strategy-engine (보조) — ~300 LOC (선택)

| 항목 | 사유 |
|---|---|
| `JvmStrategyEvaluator.evaluateBacktest()` API 안정성 검증 + KDoc 보강 | 이미 코드상 존재 — 호환성 차원 검증만 |
| (선택) `StrategyEvalPoolConfig`에 backtest-eval-pool 분리 옵션 검토 | ADR 0047 §2-2-A 정합 — backtest는 realtime과 같은 pool 공유가 default (분리는 post-MVP) |
| (선택) BacktestEvaluatorBridge 인터페이스 noop stub | api 모듈이 consumer 모듈 의존 미흡 시 — 1차는 직접 의존 인정 |

**1차 결정**: PR-49 **scope-out**. backend-core PR-48이 단독으로 진행 — `JvmStrategyEvaluator` 호출은 api ↔ consumer 모듈 간 직접 의존(이미 ADR 0004 §의존 방향 정합) 또는 단순 service-level wrapper로 해소.

### 1-3. PR-50 frontend (선택) — ~200 LOC

- `frontend/src/api/backtest.ts` (DTO + fetch — 50 LOC)
- `frontend/src/api/hooks/useBacktestRun.ts` (TanStack Query mutation — 30 LOC)
- `frontend/src/components/backtest/BacktestResultCard.tsx` (성과 카드 — 80 LOC)
- `frontend/src/components/backtest/BacktestTradeTable.tsx` (매매 기록 표 — 40 LOC)
- (선택) Strategy Builder 페이지 통합 "백테스트 실행" 버튼

**1차 결정**: PR-50 **scope-out, backlog 합류**. PR-47 사이클은 backend-core만으로 진행 — frontend는 PR-47 머지 후 별도 사이클.

---

## §2. 시나리오 검토

### 2-1. 시나리오 A: 단일 sub-agent (backend-core 단독)

**구성**: PR-48 backend-core only

**장점**:
- 충돌 0건 — 단일 sub-agent 단일 영역 LOCK
- 모듈 경계 깔끔 — domain backtest add-only + api 어댑터 add-only
- 머지 단순 — 단독 rebase merge

**단점**:
- backend-core 1인 분량 ~1500 LOC 부담 (단 PR-39b·PR-43 등 backend-core 단독 실적 정합 — 무리 없음)
- frontend는 backlog 흡수 후 별도

### 2-2. 시나리오 B: 다중 sub-agent (backend-core + strategy-engine + frontend)

**구성**: PR-48 backend-core + PR-49 strategy-engine + PR-50 frontend 3 sub-agent 병렬

**장점**:
- 분량 분산 (1500 + 300 + 200)
- 동시 출시 사용자 가치 ↑

**단점**:
- PR-49 strategy-engine 작업이 ~300 LOC로 작음 → overhead가 가치보다 큼
- PR-50 frontend는 backend-core의 endpoint 동결 후 작업이 자연 — 직렬 권장
- modify-shared 충돌 행렬 늘어남 (CHANGELOG slot · build.gradle.kts 등)

### 2-3. 채택: **시나리오 A 단일 PR-48 backend-core**

**사유**:
1. T-BT는 backend-core 단일 영역 (domain + api) — 자연 분리 0
2. strategy-engine 영역 분량 부족 — 분리 overhead가 가치보다 큼
3. frontend는 backend-core endpoint 동결 후 자연 분리 — backlog 합류
4. 충돌 0건 + 머지 단순 + 사이클 단축

**결정 사항**:
- PR-48 backend-core 단독 디스패치 → 본 PR-47 sub-agent slot 1건만 (architect + backend-core 2 row)
- PR-49 strategy-engine **scope-out** — 본 사이클 dispatch 0건
- PR-50 frontend **scope-out, backlog 합류** — backlog §27 신설 의제: "T-BT frontend `BacktestResultCard` + `useBacktestRun` hook + Strategy Builder 페이지 통합"

---

## §3. 머지 순서

### 3-1. PR-41~43 학습 인용

PR-41~43 (pattern-engine 모듈 분리) 사이클에서 학습: **빌드 그래프 변경 동반 시 동시 머지 의무**. 본 PR-47은 **신규 모듈 0** — 기존 domain·api 모듈에 add-only — 빌드 그래프 변경 0. 단독 머지 안전.

### 3-2. 결론: PR-47 architect 머지 → PR-48 backend-core 단독 머지 (직렬)

```
[t0] PR-47 architect — spec/plan/conflict/ADR 0056/CHANGELOG slot — main 머지
   ↓ (architect 영역 LOCK 동결)
[t1] PR-48 backend-core — domain backtest + api adapter + tests — main 머지
   ↓
[t2] (선택) PR-50 frontend — backlog §27에서 별도 사이클로 분리
```

---

## §4. 추정 wallclock

| PR | sub-agent | LOC | 추정 시간 (분당 LOC ~5 기준) | 비고 |
|---|---|---|---|---|
| PR-47 | architect | ~600 (spec) + ~300 (ADR 0056) + ~200 (plan·dispatch·conflict) + ~50 (CHANGELOG·README edit) = ~1150 LOC docs | ~2 hour | 이미 본 단계에서 작성 중 |
| PR-48 | backend-core | ~1500 LOC code + tests | ~4-5 hour | 단독 sub-agent |
| **총** | | | **~6-7 hour** | 직렬 |

---

## §5. 머지 동시성 룰

| 룰 | 결정 |
|---|---|
| 머지 정책 | `--rebase` (ADR 0034) — squash 금지 (author 보존) |
| Base branch | `main` |
| Branch 명 (architect) | `agent/architect/pr-47-w3-t-bt` |
| Branch 명 (backend-core) | `agent/backend-core/pr-48-w3-t-bt-engine` |
| Author email | architect: `bot+architect@magicjar.local` · backend-core: `bot+backend-core@magicjar.local` |
| 머지 순서 | PR-47 (architect) → PR-48 (backend-core) 직렬 |
| 동시 머지 의무 | 없음 (빌드 그래프 변경 0) |
| CI 트리거 | release/v*·v*.*.* 한정 (ADR 0049) — pre-push hook이 `./gradlew check` 자동 실행 |

---

## §6. backlog 합류

본 PR-47 사이클로 처리 예정 외 후속 의제 — `_workspace/backlog.md` §27 합류 예정 (orchestrator 별도 갱신 의무):

| 의제 ID | 제목 | 우선순위 |
|---|---|---|
| §27-1 | T-BT frontend — `BacktestResultCard` · `useBacktestRun` hook · Strategy Builder 페이지 통합 버튼 | 중 (W3 내) |
| §27-2 | T-BT 결과 영속 — `backtest_run` · `backtest_trade` 테이블 + GET /api/backtest/{id} | 후순위 (post-MVP) |
| §27-3 | T-BT 거래량 기반 동적 슬리피지 모델 (post-MVP) | 후순위 |
| §27-4 | T-BT 대주주 양도세 분기 모델 (post-MVP) | 후순위 |
| §27-5 | T-BT batch job — 매주 1회 활성 strategy 자동 백테스트 + 성과 리포트 (post-MVP) | 후순위 |
| §27-6 | T-BT backtest-eval-pool 분리 검토 (현재 strategy-eval-pool 공유) | 후순위 |
