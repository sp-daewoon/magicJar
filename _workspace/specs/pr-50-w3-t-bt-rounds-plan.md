# PR-50 W3 T-BT 잔여 R2·R3 plan (결과 영속 + frontend + 모델 확장)

> **상태**: plan (architect 발행 — 사용자 결정 6건 의제 대기)
> **작성일**: 2026-05-12
> **작성자**: architect (opus 4.7 1M)
> **연관 ADR**: 0001(룰 본체) · 0022(DSL .kis.yaml superset) · 0023(SignalEngine 룰 only) · 0047(DSL Evaluation Sandbox §2-2 backtest 1000ms · §2-5 abort) · 0049(GH Actions release 한정) · 0050(CHANGELOG 슬롯) · 0056(Backtest Engine Architecture — R1 LOCK · stateless run) · 0061 예약(Backtest 결과 영속 — 본 plan 채택 시 발행)
> **선행**: T-BT R1 머지 완료 (PR-47 spec + PR-48 backend-core 구현) — stateless POST /api/backtest/run + 11 domain + 7 api 파일. ADR 0056 §3-7 후속 ADR 0080+ 5건 예약 명시 → 본 plan으로 본격 진입 의제 발행.
> **본 PR 범위**: 신규 backlog §37·§38·§39 등록 + sub-agent 디스패치 매트릭스. 코드 변경 0건.

---

## §1. 컨텍스트 (왜 본 plan이 필요한가)

### 1-1. T-BT R1 머지 사실 (인용)

- **ADR 0056 Accepted (2026-05-11)** — BacktestEngine 단일 인터페이스 + 모델 3종(Tax·Commission·Slippage) + 룩어헤드 slice-by-time + abort quota 50 + **1차 stateless REST run** LOCK
- **R1 머지 코드**:
  - `domain/.../backtest/` 11 파일 (5 sealed/data + 4 port + 1 exception + 1 LookaheadGuard)
  - `api/.../controller/BacktestController.kt` + `api/.../service/backtest/` 7 파일 (JvmBacktestEngine·KoreanStockTaxModel·KisDirectCommissionModel·FixedRateSlippageModel·BacktestService·BacktestModelRegistry·BacktestEvaluatorBridge·BacktestPerformanceCalculator)
  - `api/.../controller/dto/BacktestDto.kt` (Swagger 4단 패턴 — BacktestRunRequest·BacktestRunResponse·BacktestTradeDto·BacktestPositionDto·BacktestPerformanceDto)
- **POST /api/backtest/run** 단일 endpoint. 응답 = run-time 결과 즉시 반환. **DB 영속 0** · **frontend page 0** · **모델 확장 0**

### 1-2. ADR 0056 §3-7 미완 항목 (인용)

> **0080+ 후보** — Backtest 결과 영속 + 조회 endpoint (backlog §27-2 발동 시)
> **0081+ 후보** — Backtest 거래량 기반 동적 슬리피지 모델 (backlog §27-3)
> **0082+ 후보** — Backtest 대주주 양도세 분기 모델 (backlog §27-4)
> **0083+ 후보** — Backtest 자동 batch job (매주 활성 strategy 자동 시뮬 + 리포트, backlog §27-5)
> **0084+ 후보** — backtest-eval-pool 분리 (사용자 동시 백테스트 ≥ 5건 도달 시, backlog §27-6)

ADR 0056이 명시한 backlog §27-N은 **현행 backlog.md에 등록되지 않음** — 본 PR-50에서 신규 § 등록 + 우선순위 매트릭스 발행 의무.

### 1-3. magicJar 비전 정합 (사용자 메모리 `project_system_vision.md`)

T-BT는 **룰 신뢰성 검증 베이스라인** — W3 후속 트랙(T-SC·T-SIG·T-RISK·T-O·T-RECON·T-NOTIFY·T-PERF) 모두 백테스트 통과 후 시그널 활성화 의무. R1 stateless run만으로는:

- **사용자 가치 미달**: 매번 백테스트 결과를 재실행해야 보기 가능 — "지난 결과 비교" 불가
- **운영 정합 미달**: 활성 strategy의 마지막 백테스트 시점·KPI 추적 불가 — 시그널 활성화 사전 게이트 부재
- **UI 부재**: backend API만 있고 사용자가 직접 실행할 화면이 없음 — Swagger 호출만 가능

따라서 **R2 영속 + R3 frontend + R4 모델 확장**을 본격 진입 의제로 격상.

### 1-4. fitness gap 사전 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression.md`)

| 의심 lens | 판정 |
|---|---|
| 자동매매에 필요한가? | **필수**. 룰 신뢰성 검증 베이스라인 — 사용자 시그널 활성화 사전 게이트 |
| v1/v2 잔재 아닌가? | **R1 신규 트랙(2026-05-11)**. v1/v2 PoC 시점에는 backtest 부재. v3 첫 진입 |
| 자체 추론은 없는가? | KIS·KRX·국세청 raw 출처 KDoc 인용 의무 (ADR 0056 §2-3-A). 후속 R2/R3에서도 같은 정책 유지 의무 |

---

## §2. 사용자 결정 의제 6건 (본 plan 본체 — architect 권장 + 사용자 위임)

### 의제 A — 결과 영속 도입 시점

| 옵션 | 내용 | 트레이드오프 |
|---|---|---|
| **A-1 (권장)** | **본 사이클 R2 도입** (Flyway V12·V13 + JPA 영속 + GET endpoint) | 사용자 가치 즉시. ~500 LOC 추가. backlog §27-2 LOCK 정합 |
| A-2 | post-MVP (W3 전체 완료 후 별도 사이클) | R1 stateless 유지 — 사용자가 재실행만 가능. W3 후속 트랙(T-SIG 등) 활성화 게이트 부재 |
| A-3 | Redis cache 한정 (24h TTL — DB 영속 0) | 영속 비용 0. 단 사용자가 24h 후 결과 소실 — 운영 가치 미달 |

**architect 권장**: **A-1**. 사유:
- T-SIG·T-RISK 진입 시 strategy 활성화 사전 게이트로 "최근 7일 내 백테스트 통과" 의무화 가능 — DB 영속 필수
- ~500 LOC는 1차 사이클 분량으로 적정 (PR-47 R1 ~1500 LOC 대비 1/3)
- A-3 Redis는 fitness 미달 — 결과를 시간 지나서도 비교해야 운영 가치

### 의제 B — 영속 매트릭스 (테이블 분리 깊이)

| 옵션 | 테이블 | 트레이드오프 |
|---|---|---|
| **B-1 (권장)** | `backtest_run` (1) + `backtest_trade` (N) + `backtest_position` (N) — 3 테이블 분리 | 매매·포지션 시계열 조회 가능. JPA mapping 3건 |
| B-2 | `backtest_run` 단일 — trades·positions JSONB 컬럼 | 영속 단순. 단 시계열 JOIN/조회 불가 — 후속 T-J(Trading Journal) 패턴 회귀 |
| B-3 | `backtest_run` + JSONB blob (전체 result 직렬화) | 가장 단순. 검색·통계 불가 — 운영 미달 |

**architect 권장**: **B-1**. 사유:
- 후속 T-J(Trading Journal — §D.16) 패턴 동일 — 실전 매매 기록과 백테스트 매매 기록을 통일된 schema로 비교 가능
- 매매 시계열 조회 endpoint (`GET /api/backtest/{id}/trades?page=1`) 자연스러움
- 영속 매트릭스 §4-1 LOCK

### 의제 C — REST API endpoint 추가 매트릭스

| 옵션 | endpoint | 트레이드오프 |
|---|---|---|
| **C-1 (권장)** | `GET /api/backtest` (목록 + 페이징) · `GET /api/backtest/{id}` (메타+KPI) · `GET /api/backtest/{id}/trades` (시계열) · `GET /api/backtest/{id}/positions` (시계열) · `DELETE /api/backtest/{id}` | 5 endpoint — 사용자 워크플로우 완비. ~200 LOC |
| C-2 | `GET /api/backtest/{id}` 단일 (전체 result blob 반환) | 1 endpoint. 단 응답 크기 큼 (~5KB+, trade 100+건) — 페이징 불가 |
| C-3 | `GET /api/backtest` (목록) + `GET /api/backtest/{id}` (전체) 2건 | 중간. 페이징 없음 — 결과 누적 시 응답 비대 |

**architect 권장**: **C-1**. 사유:
- 사용자가 frontend에서 "전체 백테스트 목록 → 1개 클릭 → 매매 기록 페이징" 자연스러운 흐름
- 매매 100건 이상 시 페이징 필수 — C-2/C-3 회귀 risk
- Swagger 4단 패턴 정합 (feedback_swagger_pattern.md)

### 의제 D — frontend 페이지 구조

| 옵션 | 페이지 | 트레이드오프 |
|---|---|---|
| **D-1 (권장)** | `/strategy/backtest` 단일 페이지 (왼쪽=실행 폼 + 오른쪽=목록 + 클릭 시 상세 모달) | 단일 entry — Strategy Builder 내부 통합. ~400 LOC frontend |
| D-2 | `/backtest` top-level 페이지 (목록) + `/backtest/{id}` 상세 페이지 | 2 페이지. 라우팅 추가. 단 Strategy Builder와 분리 — 발견성 낮음 |
| D-3 | Strategy Builder 페이지 내부 `EvaluationResultCard` 확장 | 0 신규 페이지. 단 백테스트 본격 UI 요건 미달 (차트·KPI 시각화 부재) |

**architect 권장**: **D-1**. 사유:
- 사용자 흐름 = Strategy Builder에서 작성 → 같은 페이지에서 백테스트 실행 → 결과 확인 → strategy 활성화 (단일 흐름)
- ADR 0040 (관심종목 우선 UI 흐름 — Strategy Builder → 발굴 → Watchlist → 상세) 정합 — 백테스트는 Strategy Builder 내부 자식 routing
- LWC 차트로 equity curve + 매매 마커 표시 — 매매 빈도 시각화

### 의제 E — 모델 확장 우선순위

| 옵션 | 확장 | 트레이드오프 |
|---|---|---|
| **E-1 (권장)** | **본 사이클 R4에서 모델 확장 0 — backlog §39로 우선순위만 등록** | R2/R3 영속·frontend 우선 완성. 모델 확장은 사용자 실제 사용 후 우선순위 재검토 |
| E-2 | 거래량 기반 동적 슬리피지 (ADR 0081+ 후보) — 본 사이클 동반 | "큰 매수 시 슬리피지 큼" 현실 정합. 단 거래량 데이터 의존 추가 — KIS REST 추가 호출 risk |
| E-3 | 대주주 양도세 분기 (ADR 0082+ 후보) — 본 사이클 동반 | 대주주 정의 (지분 1%·시총 50억) 분기 — 사용자별 보유 누적 추적 의무. 너무 무거움 |
| E-4 | 모든 모델 확장 일괄 (E-2+E-3+자동 batch job ADR 0083+) | XL 사이클. R2/R3와 충돌 — 1차 사이클 분량 초과 |

**architect 권장**: **E-1**. 사유:
- ADR 0056 default 모델 3종으로 1차 사용자 흐름 충분
- 거래량 슬리피지·대주주 양도세는 사용자 실제 백테스트 결과 누적 후 "현실 차이 발생" 확인 시 진입 — 가설 우선 구현 회피
- 본 사이클 분량(R2 영속 + R3 frontend) 적정 유지

### 의제 F — sub-agent 디스패치 모드

| 옵션 | 모드 | 트레이드오프 |
|---|---|---|
| **F-1 (권장)** | **3 라운드 직렬** (R2 backend-core → R3 frontend → 검증) | R2 영속 API 확정 후 R3 frontend가 그 API 소비 — 자연스러운 의존. 사용자 dogfooding 가능 시점 빠름 |
| F-2 | R2 backend-core ⊕ R3 frontend mock-driven 병렬 | frontend가 mock API spec 기반 선구현. R2 머지 후 wire-up. 시간 단축. 단 mock spec 불일치 risk |
| F-3 | R2+R3 통합 PR (backend-core + frontend 1 PR) | XL PR — 라운드 분할 정책 위반 (development-rules.md 룰 3) |

**architect 권장**: **F-1**. 사유:
- 사용자 메모리 `feedback_orchestrator_active_parallel.md` 적극 병렬 권장하나 본 사이클은 **순차 의존 자연스러움** — backend API 확정 전 frontend 진행은 mock spec drift risk
- 라운드 분할 정책(룰 3 — R1·R2·R3) 정합 의무
- R2 머지 후 사용자 1회 dogfooding → 피드백 흡수 후 R3 진입 자연스러움

---

## §3. 권장안 종합 매트릭스 (사용자 검토용 한 줄 요약)

| 의제 | architect 권장 | 한 줄 사유 |
|---|---|---|
| A 영속 시점 | **A-1 본 사이클 R2** | T-SIG 활성화 게이트 사전 의무. 500 LOC 적정 |
| B 영속 매트릭스 | **B-1 3 테이블** | T-J 패턴 통일. 매매 시계열 페이징 자연스러움 |
| C REST endpoint | **C-1 5 endpoint** | 사용자 워크플로우 완비. 매매 페이징 필수 |
| D frontend | **D-1 /strategy/backtest 단일** | Strategy Builder 내부 통합 흐름 자연스러움 |
| E 모델 확장 | **E-1 본 사이클 0건** | 사용자 실제 사용 후 우선순위 재검토 |
| F 디스패치 | **F-1 R2 → R3 직렬** | API 확정 후 frontend mock spec drift 회피 |

---

## §4. 영속 매트릭스 (의제 B-1 채택 가정 sketch)

### 4-1. 테이블 schema sketch

```sql
-- V12__backtest_run.sql

CREATE TABLE backtest_run (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,           -- multi-tenant (ADR 0010)
    universe TEXT[] NOT NULL,          -- ["005930", "000660", ...]
    interval VARCHAR(8) NOT NULL,      -- "1d" · "1m" · "5m" · ...
    range_from DATE NOT NULL,
    range_to DATE NOT NULL,
    initial_capital NUMERIC(18,2) NOT NULL,
    tax_model VARCHAR(32) NOT NULL,
    commission_model VARCHAR(32) NOT NULL,
    slippage_model VARCHAR(32) NOT NULL,
    price_adjustment_mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,       -- "SUCCESS" · "ABORTED"
    abort_reason VARCHAR(32),          -- "TIMEOUT_QUOTA_EXCEEDED" · "NO_CANDLE_DATA" · null
    timeout_count INT NOT NULL DEFAULT 0,
    final_capital NUMERIC(18,2),
    total_return NUMERIC(10,6),
    cagr NUMERIC(10,6),
    max_drawdown NUMERIC(10,6),
    sharpe_ratio NUMERIC(10,6),
    win_rate NUMERIC(10,6),
    total_trades INT,
    winning_trades INT,
    losing_trades INT,
    avg_win NUMERIC(18,2),
    avg_loss NUMERIC(18,2),
    profit_factor NUMERIC(10,6),
    trading_frequency NUMERIC(10,6),
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    duration_ms BIGINT NOT NULL,
    CONSTRAINT fk_backtest_run_strategy FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE,
    CONSTRAINT fk_backtest_run_user FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE
);

CREATE INDEX idx_backtest_run_user_executed ON backtest_run(user_id, executed_at DESC);
CREATE INDEX idx_backtest_run_strategy_executed ON backtest_run(strategy_id, executed_at DESC);

-- V13__backtest_trade_position.sql

CREATE TABLE backtest_trade (
    id BIGSERIAL PRIMARY KEY,
    backtest_run_id BIGINT NOT NULL,
    code VARCHAR(16) NOT NULL,
    side VARCHAR(4) NOT NULL,          -- "BUY" · "SELL"
    quantity BIGINT NOT NULL,
    executed_price NUMERIC(18,2) NOT NULL,
    raw_price NUMERIC(18,2) NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL,
    commission NUMERIC(18,2) NOT NULL,
    tax NUMERIC(18,2) NOT NULL,
    slippage NUMERIC(18,2) NOT NULL,
    CONSTRAINT fk_backtest_trade_run FOREIGN KEY (backtest_run_id) REFERENCES backtest_run(id) ON DELETE CASCADE
);

CREATE INDEX idx_backtest_trade_run_ts ON backtest_trade(backtest_run_id, executed_at);

CREATE TABLE backtest_position (
    id BIGSERIAL PRIMARY KEY,
    backtest_run_id BIGINT NOT NULL,
    code VARCHAR(16) NOT NULL,
    quantity BIGINT NOT NULL,
    avg_entry_price NUMERIC(18,2) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,             -- null = 미종료
    realized_pnl NUMERIC(18,2) NOT NULL,
    CONSTRAINT fk_backtest_position_run FOREIGN KEY (backtest_run_id) REFERENCES backtest_run(id) ON DELETE CASCADE
);

CREATE INDEX idx_backtest_position_run ON backtest_position(backtest_run_id);
```

### 4-2. 영속 책임 분리

- `BacktestService.runBacktest()` — 기존 R1 흐름 유지 (run-time stateless) — `BacktestResult` 반환 직후 `BacktestRunPersister.persist(result)` 후속 호출 추가
- `BacktestRunPersister` (신규 service) — `BacktestResult` → 3 테이블 transactional insert
- `BacktestRunQueryService` (신규 service) — 목록 + 상세 + trades 페이징 + positions 조회 (read-only)
- `BacktestRunJpaRepository`·`BacktestTradeJpaRepository`·`BacktestPositionJpaRepository` (신규) — Spring Data + **default fun 함정 회피** (ADR 0051 정합 — body fun 0건, query method or @Query only)

### 4-3. 데이터 크기 추정

- 1 백테스트 — 매매 평균 ~30건 (1년 일봉 + 매매 빈도 0.1) + 포지션 ~15건
- 사용자 1명 1일 ~5 백테스트 → 1주 35건 → 1년 1820건 row
- 매매 row 1820 × 30 = 54,600 row · 포지션 1820 × 15 = 27,300 row
- 총 ~84,000 row / 사용자 / 1년 — 인덱스 적용 시 page-level lookup 충분

---

## §5. REST API 매트릭스 (의제 C-1 채택 가정 sketch)

| HTTP | endpoint | 책임 | 응답 |
|---|---|---|---|
| POST | `/api/backtest/run` | (R1 기존 유지) 실행 + 즉시 결과 + **영속 동시** | `BacktestRunResponse` + `id` 필드 신규 추가 |
| GET | `/api/backtest?strategyId=&page=&size=` | 사용자 본인 백테스트 목록 + 페이징 + strategy 필터 | `Page<BacktestRunSummaryDto>` (id·strategyId·status·KPI 5건·executedAt) |
| GET | `/api/backtest/{id}` | 메타 + KPI 전체 | `BacktestRunDetailDto` (전체 12 KPI 포함) |
| GET | `/api/backtest/{id}/trades?page=&size=` | 매매 시계열 페이징 | `Page<BacktestTradeDto>` (기존 DTO 재사용) |
| GET | `/api/backtest/{id}/positions` | 포지션 전체 (페이징 X — 종목수 적음) | `List<BacktestPositionDto>` (기존 DTO 재사용) |
| DELETE | `/api/backtest/{id}` | 사용자 본인 백테스트 삭제 (CASCADE) | 204 No Content |

**권한 검증 의무**:
- 모든 endpoint — `@AuthenticationPrincipal` 또는 `OnboardingGuardFilter` 통과 후 `userId` 확보
- 본인 백테스트만 조회·삭제 가능 — Service 차원 `backtestRun.userId == authUserId` 검증
- 위반 시 403 Forbidden

---

## §6. 도메인 모델 영향 (R1 기존 자산 활용 — 신규 0건)

- **BacktestResult.Success** (R1 기존) — 이미 모든 필드 충분
- **BacktestResult.Aborted** (R1 기존) — 영속 시 status="ABORTED" + abortReason 기록
- **BacktestRequest** (R1 기존) — 영속 시 strategyId·universe·interval·range·initialCapital·모델3·priceAdjustmentMode 전부 보존
- **신규 영속 도메인** (선택) — `BacktestRunSummary` (KPI 5건만 추출한 list view용 data class) — domain 모듈에 추가

**핵심**: ADR 0056 §3-4 "1차 stateless" LOCK은 **R1 사이클** 한정. R2 본 plan 채택 시 새 ADR(0061 후보) 발행 + ADR 0056 §3-4 supersede 표기. 도메인 모델 자체는 변경 0.

---

## §7. frontend 매트릭스 (의제 D-1 채택 가정 sketch)

### 7-1. 라우팅

- `/strategy/builder` — 기존 Strategy Builder
- `/strategy/backtest` — **신규** 백테스트 페이지

### 7-2. 페이지 구조 sketch

```
┌─────────────────────────────────────────────────────────┐
│ /strategy/backtest                                       │
├──────────────────────┬──────────────────────────────────┤
│ 왼쪽 1/3:            │ 오른쪽 2/3:                       │
│ ─ 백테스트 실행 폼   │ ─ 백테스트 목록 (페이징)           │
│   ▪ Strategy 선택    │   ▪ 표 (executedAt · status · KPI)│
│   ▪ Universe 선택    │   ▪ 행 클릭 → 상세 모달            │
│   ▪ Interval / 기간  │                                  │
│   ▪ 초기 자본        │ ↓ 상세 모달 ↓                     │
│   ▪ 모델 3종 선택    │ ─ KPI 카드 12종                  │
│   ▪ [실행] 버튼      │ ─ Equity curve (LWC line)        │
│                      │ ─ 매매 마커 차트 (LWC)            │
│                      │ ─ 매매 시계열 표 (페이징)         │
│                      │ ─ 포지션 표                       │
└──────────────────────┴──────────────────────────────────┘
```

### 7-3. 신규 컴포넌트

- `frontend/src/pages/Strategy/Backtest.tsx` — 페이지 본체
- `frontend/src/components/backtest/BacktestRunForm.tsx` — 왼쪽 실행 폼
- `frontend/src/components/backtest/BacktestRunList.tsx` — 오른쪽 목록 (React Query infinite)
- `frontend/src/components/backtest/BacktestDetailModal.tsx` — 상세 모달
- `frontend/src/components/backtest/BacktestKpiCards.tsx` — KPI 12종 카드
- `frontend/src/components/backtest/BacktestEquityCurve.tsx` — LWC line series
- `frontend/src/components/backtest/BacktestTradeMarker.tsx` — LWC chart + 매매 마커 (T-LWC ShapeOverlay 사전 활용)
- `frontend/src/api/backtest.ts` — REST 클라이언트 (`runBacktest` · `listBacktests` · `getBacktest` · `getTrades` · `getPositions` · `deleteBacktest`)

### 7-4. 사용자 메모리 정합

- **feedback_no_mock_data.md** — 백테스트 결과 0건 또는 candle 부족 503 시 "데이터 없음" 명시 표시. mock 합성 절대 금지
- **feedback_swagger_pattern.md** — backend Swagger 4단 정합 (BacktestController 기존 패턴)
- **feedback_honorific.md** — 모든 frontend 한국어 존댓말 ("백테스트 실행하기" → "백테스트를 실행합니다")

---

## §8. 운영 영향

### 8-1. KIS API 호출 영향

- **0건**. 백테스트는 이미 적재된 candle 활용 — KIS REST·WS 호출 없음
- corp_action adjusted 컬럼 사용 (T-CA1 완료 정합) — 룩어헤드 가드 보존

### 8-2. JVM 메모리 영향

- 영속 추가로 JPA mapping 3 테이블 추가 — Spring Data 메모리 ~수십 MB 증가 (전체 ~수백 MB 대비 부담 없음)
- BacktestRunPersister insert ~50건 row per run — transaction 1초 내 완료 (PostgreSQL idx 적용)

### 8-3. 모니터링 metric

| 영역 | metric | 임계 |
|---|---|---|
| 백테스트 영속 성공률 | `backtest.persist.success.ratio` | 100% (실패 시 alert) |
| 백테스트 평균 영속 시간 | `backtest.persist.duration.ms` | < 500ms |
| 목록 조회 평균 응답 | `backtest.list.duration.ms` | < 200ms |
| 사용자별 backtest_run 누적 | `backtest.run.count.per.user` | 모니터링만 (제한 0) |

### 8-4. 외부 의존

- PostgreSQL JPA만 — 외부 신규 의존 0
- frontend는 React Query·LWC v4·기존 stack (의존 추가 0)

---

## §9. 트랙 의존성

```
T-DSL (W2 완료) ─┐
T-IND (W2 완료) ─┤
T-PAT (W2 완료) ─┤
T-CA1 (W1 완료) ─┤
T-IM1 (W1 완료) ─┼─► T-BT R1 (PR-47/48 머지 LOCK) ──► T-BT R2/R3 (본 plan)
                 │                                          │
                 │                                          ├─► T-SC (W3 후속)
                 │                                          ├─► T-SIG (W3 후속, 본 plan 영속 KPI 활성화 게이트)
                 │                                          ├─► T-RISK (W3 후속)
                 │                                          └─► T-PERF (W3 후속)
                 │
                 └─► T-SB (W2 완료) ─► /strategy/builder ↔ /strategy/backtest (본 plan §7)
```

후행 트랙 의존:
- **T-SIG**: 본 plan §4 `backtest_run` 테이블의 `executed_at + status=SUCCESS` 조회로 strategy 활성화 게이트 구성 가능
- **T-RECON**: 영향 0 — 백테스트는 실전 매매 아님
- **T-J (Trading Journal)**: 본 plan §4-1 `backtest_trade`·`backtest_position` schema가 T-J 실전 매매 schema의 reference

---

## §10. CHANGELOG 슬롯 사전 발행 (헌법 §4 architect 영역)

본 plan은 **사용자 결정 의제만 발행** — 코드 변경 0건. CHANGELOG 슬롯 사전 발행 면제 조건(헌법 §4 — hotfix·결함 정정·작은 묶음·spec/plan 0 변경) 합치. **본 PR CHANGELOG row 1건**으로 충분.

**채택 후 후속 PR-51 (R2 backend-core) + PR-52 (R3 frontend)** 발행 시 별도 CHANGELOG 슬롯 사전 prepend 의무.

---

## §11. sub-agent 디스패치 매트릭스 (채택 후 진행 작전 — F-1 권장 기준)

### 11-1. R2 backend-core 사이클 (PR-51)

- **에이전트**: backend-core (sonnet 4.5 1M)
- **goal**: T-BT R2 — backtest_run/trade/position 영속 + 5 GET/DELETE endpoint + run endpoint 영속 동시 호출
- **branch**: `agent/backend-core/t-bt-r2-persistence`
- **deliverables**:
  - Flyway `V12__backtest_run.sql` + `V13__backtest_trade_position.sql`
  - `api/.../persistence/BacktestRun{JpaEntity,JpaRepository,RepositoryAdapter}.kt` + `BacktestTrade*` + `BacktestPosition*`
  - `api/.../service/backtest/{BacktestRunPersister,BacktestRunQueryService}.kt`
  - `api/.../controller/BacktestController.kt` 5 endpoint 추가 (Swagger 4단)
  - `api/.../controller/dto/BacktestDto.kt` 추가 (`BacktestRunSummaryDto`·`BacktestRunDetailDto`)
  - `BacktestService.runBacktest()` — 결과 반환 직후 `BacktestRunPersister.persist(result)` wire-up
  - ADR 0051 default fun 함정 회피 검증
- **검증**:
  - `./gradlew :api:check` GREEN
  - Swagger UI 5 신규 endpoint 노출 검증
  - 사용자 dogfooding — Watchlist 1종목 + simple strategy 백테스트 실행 → DB row 증가 검증

### 11-2. R3 frontend 사이클 (PR-52)

- **에이전트**: frontend (sonnet 4.5 1M)
- **goal**: T-BT R3 — /strategy/backtest 페이지 + 6 컴포넌트 + LWC equity curve
- **branch**: `agent/frontend/t-bt-r3-page`
- **deliverables**:
  - `frontend/src/pages/Strategy/Backtest.tsx`
  - `frontend/src/components/backtest/` 6 컴포넌트 (§7-3)
  - `frontend/src/api/backtest.ts` REST client
  - 라우팅 `/strategy/backtest` 추가
  - 사용자 dogfooding 후 피드백 흡수
- **검증**:
  - `pnpm test` + `pnpm build` GREEN
  - 사용자 dogfooding — 백테스트 실행·목록·상세·삭제 e2e

### 11-3. (선택) 추후 사이클 — 모델 확장 (E-2/E-3/E-4 — backlog §39)

- backlog §39 우선순위만 등록. 본 PR-50 사이클 진행 0
- 사용자 실제 사용 후 "현실 차이 확인" 시점에 별도 사이클 발동 — ADR 0081+/0082+/0083+ 분리 발행

---

## §12. rollback 매트릭스

| 단계 | rollback |
|---|---|
| 본 PR-50 plan | revert 1 commit — 의제만 있고 코드 0건. 영향 0 |
| R2 backend-core (PR-51) | Flyway V12/V13 rollback (DROP TABLE backtest_run/trade/position CASCADE) + revert. R1 stateless로 복원 |
| R3 frontend (PR-52) | revert frontend commit — 라우팅·페이지 제거. R2 API는 그대로 보존 |

---

## §13. 사용자 결정 형식 (응답 예시)

```
A-1, B-1, C-1, D-1, E-1, F-1   ← architect 권장안 전체 채택
```
또는
```
A-1, B-2, C-1, D-1, E-1, F-1   ← B만 JSONB blob 변경
```

본 plan 채택 후 orchestrator가 PR-51 (R2 backend-core) 디스패치 + 머지 후 PR-52 (R3 frontend) 디스패치.

---

## §14. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-12 | PR-50 신규 발행 — T-BT R2/R3 잔여 plan + 사용자 결정 의제 6건 + backlog §37·§38·§39 등록 의제 + sub-agent F-1 직렬 디스패치 매트릭스. ADR 0056 §3-7 후속 ADR 0080+ 5건 격상 의제. (architect, opus 4.7 1M) |
