# ADR 0072 — T-J + T-PERF 묶음 spec/plan (W3 마지막 사이클 — Trading Journal 영속 + Performance Loop 통계)

- 상태: Accepted (architect 자율 결정 — 사용자 위임 2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율 최종권한")
- 작성일: 2026-05-14
- 결정자: architect (사용자 위임 + orchestrator dispatch · W3 마무리 v3.6.4 patch 사이클)
- 관계: ADR 0012 정합(매매일지 아키텍처 — baseline) · ADR 0042 정합(Position Bootstrap & Recovery — position 영속 reuse) · ADR 0066 정합(T-SIG 시그널 영속 — `signal` 테이블 reuse) · ADR 0067 정합(T-RISK 영속 — `risk_event`·`position` reuse) · ADR 0068 정합(T-O 주문 영속 — `order_request`·`order_execution`·`order_audit_event` 3 테이블 reuse) · ADR 0070 정합(T-RECON·T-NOTIFY — `reconcile_run`·`notification_outbox` reuse) · ADR 0071 정합(T-SC Screener — 선행 사이클 패턴 reuse) · ADR 0026 정합(AI 옵션 보조 — 본 사이클 AI 호출 0 LOCK · 회고 AI 코멘트는 W4 backlog) · ADR 0030 정합(SoT — 본 ADR 발행 + master 동시 갱신)

---

## §1. 컨텍스트 (왜 이 결정이 필요한가)

### 1-1. T-J + T-PERF 위치 — W3 마지막 사이클 (자동 매매 본체 가동 완료 후 운영 회고 합류)

2026-05-14 v3.6.3 patch cut으로 **T-SC Screener spec/plan 마감** (ADR 0071 3 Phase 직렬 디스패치 정합 — Strategy Builder 흐름 단절 회복 완료). W3 트랙 진행 시계열 정합:

| 사이클 | 트랙 | ADR | 마감 |
|---|---|---|---|
| v3.3.x | T-BT | ADR 0061 | DONE |
| v3.4.0 | T-SIG | ADR 0066 | DONE |
| v3.5.0 | T-RISK | ADR 0067 | DONE |
| v3.6.0 | T-O | ADR 0068 | DONE |
| v3.6.2 | T-RECON + T-NOTIFY | ADR 0070 | DONE |
| v3.6.3 | T-SC | ADR 0071 | DONE |
| **v3.6.4 (본 사이클)** | **T-J + T-PERF 묶음** | **ADR 0072** | **진행** |
| v3.7.0 baseline | W3 dogfooding 검증 + 일괄 cut | - | next |
| W4 진입 | T-AI | (ADR 0073 후보) | next-next |

자동 매매 흐름은 v3.6.3 시점 **신호 → 리스크 → 주문 → 체결 → 잔고 정합 → 통지 → 발굴** 7 컴포넌트 가동 완료. 그러나 **운영 회고 (사용자 메모·차트 스냅샷·P&L·MDD·승률 시계열)** 가 부재 → 사용자가 자동매매 본체 결과를 검증·복기할 수단 없음.

본 사이클은 **W3 마지막 1건 묶음** 으로 운영 회고 합류 — T-J(데이터 영속) + T-PERF(계산·시각화) 의존성 직선 정합으로 묶음 spec 적정 규모.

### 1-2. 사용자 비전 정합 fitness 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression` 의무)

**필수성** (자동매매에 진짜 필요한가?):
- ★★★ **운영 회고 의무** — 자동매매 본체 가동 후 사용자가 "오늘 어떤 매매가 일어났고, 누계 P&L은 얼마인가" 검증할 수단. 부재 시 dogfooding 사이클 진입 자체가 불가
- ★★ **사용자 메모 기반 학습 자료** — 사용자가 본인 회고를 누적 → W4 AI 합류 시 prompt context로 활용 (현 사이클 비범위, backlog 합류)
- ★★ **P&L · MDD · 승률 시계열** — 룰 본체 정상 작동 검증의 핵심 지표 (사용자 메모리 `project_system_vision` 정합 — 룰 기반 quant trading 본체 검증)

**v1/v2 잔재 검증**: v1·v2는 단일 PoC trader + 회고 부재. T-J·T-PERF는 v3 신규 의제(다중 사용자 격리 + 시계열 영속 + 자동 차트 스냅샷). 잔재 0.

**AI 호출 0 검증**: 본 사이클은 룰 기반 통계 계산만 — Spring AI·Anthropic·OpenAI import 0 의무 (개발 룰 5 정합). sub-agent 산출물 grep 검증 의무:
```
grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" \
  api/journal api/performance batch/.../performance domain/journal domain/performance \
  || echo "PASS — AI 호출 0"
```

**mock 합성 0** (사용자 메모리 `feedback_no_mock_data` 정합): T-J는 **기존 영속 테이블 reuse** — order_request·order_execution·signal·position·risk_event 모두 실제 데이터. T-PERF는 **기존 영속 + 통계 계산만** — mock 합성 금지 LOCK.

### 1-3. T-J + T-PERF 묶음 사유 (ADR 0070 패턴 정합)

ADR 0070(T-RECON·T-NOTIFY 묶음) 패턴 정합 — 두 트랙 모두 baseline ADR 존재(0012 매매일지 + 0044 후보 슬롯) 하나 사이클 부담 적정 + 의존성 직선:

| 항목 | 사유 |
|---|---|
| **의존성 직선** | T-J → T-PERF (T-J 데이터 부재 시 T-PERF 통계 계산 불가). T-J 영속 → T-PERF aggregate query 의존 |
| **사이클 부담 적정** | T-J: backend-core 영역 단일(api+domain) + frontend 페이지 1건 / T-PERF: backend-core(api+batch) + frontend 페이지 1건 — 4 Phase 직렬 적정 |
| **W3 마지막 사이클** | W3 잔여 트랙 2건 모두 본 사이클로 마감 + v3.6.4 patch cut 후 v3.7.0 baseline 진입 가능 |
| **AI 호출 0 LOCK 일관성** | 두 트랙 모두 AI 비범위 — 단일 ADR로 grep 검증 매트릭스 통합 |
| **frontend AppHeader 1회 갱신** | `/journal` + `/performance` 메뉴 2개 동시 추가 — 별개 사이클 시 AppHeader 2회 수정 risk |

### 1-4. KIS-first raw 인용 (헌법 §3 적용 매트릭스)

본 사이클은 **자체 KIS endpoint 호출 0 LOCK** — 모든 데이터는 기존 영속 테이블 reuse:

| 컴포넌트 | 데이터 소스 | KIS 호출 |
|---|---|---|
| Trading Journal entries | order_request + order_execution + signal + risk_event + position 5 테이블 (T-SIG·T-RISK·T-O baseline 영속) | 0 |
| Journal Note | journal_note 신규 (사용자 메모) | 0 |
| Performance Summary | order_execution(체결가·수수료) + position(PnL 누계) + pnl_daily(T-PERF 신규 또는 batch 계산) aggregate | 0 |
| Equity Curve | pnl_daily 또는 inline aggregate (order_execution.realized_pnl 시계열) | 0 |

**예외 발생 시 헌법 §3 의무 발동**: 본 사이클은 KIS endpoint 신규 호출 0 LOCK. 예외 0.

---

## §2. 결정 (8 의제 architect 자율 LOCK)

### 2-1. (A) Trading Journal 영속 모드 — **A-1 신규 trading_journal 테이블 view·event-sourcing 채택** (vs A-2 view-only / vs A-3 inline JOIN query only)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **A-1** trading_journal 신규 테이블 + Kafka listen으로 자동 INSERT (order.filled.v1·signal.generated.v1·risk.violation.v1 listen → entry INSERT) | 통합 시계열 single source + 사용자 메모 합류 가능 + 쿼리 단순화 + 회고 audit | **채택** |
| A-2 inline JOIN VIEW(order + signal + risk JOIN) 만 정의 | 영속 부담 0 우위 있으나 5 테이블 JOIN 쿼리 + 페이징 성능 ↓ + 사용자 메모 합류 불가 | 폐기 |
| A-3 5 테이블 직접 query + frontend에서 client-side merge | UX 응답 시간 ↑↑ + 페이징 일관성 부재 | 폐기 |

**근거**:
- trading_journal은 **이벤트 시계열 단일 영속** — order·signal·risk·position 5 테이블에서 발생한 이벤트를 Kafka listen 으로 한 row 변환 INSERT
- 사용자 메모(journal_note)는 trading_journal entry에 1:N 관계로 합류
- 차트 스냅샷(post-MVP backlog §60 — chart_snapshot 영속 강화)은 backlog
- 5 이벤트 → 1 journal entry 변환 패턴: order_filled → "체결: 005930 매수 100주 @ 75000원" / signal_generated → "시그널: 005930 BUY 강도 0.78" / risk_blocked → "리스크 차단: POSITION_LIMIT" / position_updated → "포지션 변동: 005930 +100주 누적 200주" / reconcile_drift → "잔고 드리프트 검출"

### 2-2. (B) Performance 계산 모드 — **B-1 batch DailyPerformanceJob + inline aggregate 듀얼 채택** (vs B-2 inline only / vs B-3 batch only)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **B-1** batch DailyPerformanceJob(매일 00:30 KST cron, T-2 영업일 일괄 집계 + pnl_daily INSERT) + inline aggregate(요청 시점 미집계 일자만 계산) | 시계열 검증 우위(영속) + 사용자 인터랙티브 호출 시 즉시 + lag 최소화 | **채택** |
| B-2 inline aggregate only (요청마다 order_execution 시계열 합산) | DB 부담 우위 있으나 1년 누계 시 시간 ↑↑ | 폐기 (1차) |
| B-3 batch only (cron 시각 외 호출 시 stale) | UX 시간 우위 있으나 사용자 인터랙티브 호출 시 누락 ↑ | 폐기 |

**근거**:
- pnl_daily 일별 영속(V18 또는 신규 V29·V30) — 매일 00:30 KST cron으로 T-2 영업일 일괄 집계
- inline aggregate fallback — 사용자가 today를 조회할 때만 inline (pnl_daily는 yesterday까지만)
- Redis cache TTL 1h — perf:summary:{userId}:{period} key

### 2-3. (C) Performance 지표 매트릭스 — **C-1 5 지표 fixed 채택** (vs C-2 사용자 토글 / vs C-3 minimum 3)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **C-1** 5 지표 fixed (총 P&L · 일별 P&L 시계열 · MDD · 승률 · 평균 손익비) + equity curve 점 시계열 | 사용자 비전 정합 검증 항목 풀세트 + 1차 LOC 적정 | **채택** |
| C-2 사용자 토글 (체크박스 가시화) | 1차 LOC 비용 ↑ + 검증 부담 ↑ | 폐기 (1차) |
| C-3 minimum 3 (P&L · MDD · 승률만) | 평균 손익비·equity curve 누락 시 회고 부담 ↑ | 폐기 |

**1차 LOCK 5 지표**:

| 지표 | 산정 | 정밀도 |
|---|---|---|
| **총 P&L** | sum(order_execution.realized_pnl) | NUMERIC(18, 2) — 원 단위 |
| **일별 P&L 시계열** | sum(order_execution.realized_pnl) GROUP BY trade_date | 일별 NUMERIC(18, 2) |
| **MDD (Maximum Drawdown)** | max(peak - trough) / peak * 100 — equity curve 기반 | NUMERIC(8, 4) — % |
| **승률** | count(filled_pnl > 0) / count(filled_pnl != 0) * 100 | NUMERIC(5, 2) — % |
| **평균 손익비** | avg(profit) / abs(avg(loss)) | NUMERIC(8, 4) |

**Sharpe·Sortino·CAGR**: 1차 비범위 — backlog §61(post-MVP, T-PERF 풀세트 진입 시).

### 2-4. (D) Performance Summary 영속 — **D-1 performance_summary 신규 테이블 채택** (vs D-2 pnl_daily ALTER / vs D-3 영속 없이 inline only)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **D-1** performance_summary 신규 (period + summary jsonb) + pnl_daily 신규 OR ALTER 보강 | 통계 시계열 audit + 사용자 인터랙티브 캐시 fallback + B-1 batch 결과 영속 | **채택** |
| D-2 pnl_daily ALTER 추가 컬럼만 | 컬럼 8건 ALTER 부담 ↑ + 미래 지표 추가 시 매번 ALTER | 폐기 |
| D-3 영속 없이 inline aggregate + Redis cache only | 통계 audit 부재 + 사용자 1년치 회고 시 시간 ↑↑ | 폐기 |

**1차 영속 Flyway**:
- **V29**: trading_journal + journal_note (T-J 영역, 본 ADR §3-1)
- **V30**: performance_summary + pnl_daily 신규 (또는 기존 V18 pnl_daily 있는 환경 reuse — backend-core 자율 검증) (T-PERF 영역, 본 ADR §3-2)

**IF NOT EXISTS 강제 LOCK** (헌법 §6 + ADR 0068·0070·0071 패턴 — sub-agent 자율 hotfix 회피).

### 2-5. (E) REST endpoint 매트릭스 — **E-1 5 endpoint Swagger 4단 채택**

| 옵션 | 사유 | 채택 |
|---|---|---|
| **E-1** T-J 3 endpoint (entries list 페이징 + entry detail + POST note) + T-PERF 2 endpoint (summary + equity curve) | UX 완결성 + 회고 audit + 사용자 메모 합류 | **채택** |
| E-2 T-J + T-PERF 통합 endpoint(/api/review) | 책임 분리 불명확 + Swagger 가독성 ↓ | 폐기 |
| E-3 T-J 1 + T-PERF 1 (최소 endpoint) | 페이징·detail·note 합류 시 multi-method overload 부담 | 폐기 |

**Swagger 4단 LOCK** (사용자 메모리 `feedback_swagger_pattern` 정합 — §6 본문 참조).

### 2-6. (F) Frontend UI 매트릭스 — **F-1 2 페이지 + AppHeader 메뉴 2 추가 채택**

| 옵션 | 사유 | 채택 |
|---|---|---|
| **F-1** `/journal` Trading Journal 페이지 + `/performance` Performance Dashboard 페이지 + AppHeader 메뉴 2개 (매매일지·성과) | UX 분리 명확 + 사용자 진입점 명시 | **채택** |
| F-2 `/review` 단일 페이지 + 탭 2개 | UX 합류 우위 있으나 페이지 크기 ↑ + 차트 컴포넌트 부담 ↑ | 폐기 (1차) |
| F-3 Watchlist 상세 페이지 합류 (종목별 회고만) | 전체 누계 부재 + AppHeader 진입점 부재 | 폐기 |

**Phase 4 frontend 산출물**:
- `/journal` — TradingJournalPage (시계열 list + 필터(기간·종목·이벤트 타입) + 메모 작성·조회)
- `/performance` — PerformanceDashboardPage (5 지표 카드 + equity curve LWC v4 차트 + 일별 P&L bar chart)
- AppHeader.tsx 갱신 — 메뉴 2 추가

### 2-7. (G) Kafka listen 매트릭스 — **G-1 5 토픽 listen 채택** (vs G-2 단일 통합 토픽)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **G-1** 5 토픽 listen (order.filled.v1 + signal.generated.v1 + risk.violation.v1 + reconcile.drift.v1 + position.updated.v1) → 단일 JournalEventConsumer → trading_journal INSERT | 이벤트 source 명확 + 1:1 mapping + DLQ 분리 가능 | **채택** |
| G-2 통합 토픽 journal.event.v1 발행 후 listen | publisher 부담 ↑ + 기존 토픽 reuse 안 함 비효율 | 폐기 |
| G-3 polling 기반 (cron으로 5 테이블 scan) | 이벤트 listen 패턴 위배 + ADR 0066·0067·0068 baseline 무시 | 폐기 |

**Kafka 신규 publish 0 LOCK**: 본 사이클 publish 토픽 신규 0 — 기존 토픽 reuse listen만.

### 2-8. (H) AI 호출 0 LOCK (개발 룰 5 + 사용자 메모리 정합)

| 검증 | 의무 |
|---|---|
| Spring AI/Anthropic/OpenAI import 0 | grep 검증 (§1-2) |
| Performance 계산 pure math | sum·avg·count·max — LLM 호출 0 |
| Journal entry 생성 pure mapping | Kafka event → entity 변환 — LLM 코멘트 0 |
| 사용자 메모 free-form text | 사용자 입력만 — AI 자동 완성 0 |

**AI 합류 후속 backlog**: W4 진입 시 AiCommentProvider 합류 가능 (사용자 "이 매매 복기해줘" 버튼 — D.18 AI Option Console). 본 사이클 비범위.

### 2-9. KIS Rate Limit 영향 (본 사이클 0 추정)

| 항목 | 산정 |
|---|---|
| KIS 호출 빈도 (본 사이클) | **0** — 모든 데이터 영속 reuse |
| 예외 (외부 데이터 fetch 합류 시) | 본 사이클 비범위 (post-MVP backlog) |

---

## §3. DB Flyway V29·V30 매트릭스

본 ADR §2-4 D-1 채택 — 2 신규 마이그레이션 (헌법 §6 IF NOT EXISTS 강제 LOCK).

**V20~V28 사용 현황**:
- V20~V23: T-O (ADR 0068)
- V24: T-O hotfix (position.created_at)
- V25: T-RECON Phase 2 (reconcile_run, ADR 0070)
- V26: T-RECON Phase 2 (risk_event enum placeholder, ADR 0070)
- V27: T-NOTIFY Phase 1 (notification_outbox + system_config.notification_slack_webhook, ADR 0070)
- V28: T-SC Phase 2 옵션 영속 (screening_run + screening_match, ADR 0071)

→ **V29**: T-J 영속 (trading_journal + journal_note)
→ **V30**: T-PERF 영속 (performance_summary + pnl_daily)

### 3-1. V29 — trading_journal + journal_note

```sql
-- V29__trading_journal_and_note.sql
-- T-J 영속 (ADR 0072 §2-1 A-1)
-- order·signal·risk·reconcile·position 5 Kafka 이벤트 → trading_journal INSERT
-- 사용자 메모 journal_note 1:N
CREATE TABLE IF NOT EXISTS trading_journal (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,         -- ORDER_FILLED · SIGNAL_GENERATED · RISK_VIOLATION · RECONCILE_DRIFT · POSITION_UPDATED
    instrument_code VARCHAR(16),             -- nullable (RECONCILE_DRIFT 등 portfolio-wide 이벤트)
    instrument_name VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    summary VARCHAR(255) NOT NULL,           -- "체결: 005930 매수 100주 @ 75000원"
    payload JSONB NOT NULL,                  -- 원본 이벤트 페이로드 (audit + W4 AI context)
    source_table VARCHAR(32) NOT NULL,       -- 원본 영속 테이블명 (order_execution · signal · risk_event · reconcile_run · position)
    source_id BIGINT NOT NULL,               -- 원본 PK
    realized_pnl NUMERIC(18, 2),             -- 체결 이벤트 한정, 그 외 NULL
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_trading_journal_user_occurred ON trading_journal (user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_trading_journal_user_event ON trading_journal (user_id, event_type, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_trading_journal_user_code ON trading_journal (user_id, instrument_code, occurred_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ux_trading_journal_source ON trading_journal (source_table, source_id, event_type);  -- 멱등성 (Kafka at-least-once 재처리 회피)

CREATE TABLE IF NOT EXISTS journal_note (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    journal_entry_id BIGINT NOT NULL REFERENCES trading_journal(id) ON DELETE CASCADE,
    note_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_journal_note_entry ON journal_note (journal_entry_id);
CREATE INDEX IF NOT EXISTS ix_journal_note_user_created ON journal_note (user_id, created_at DESC);
```

**멱등성 unique 키 (`ux_trading_journal_source`)**: Kafka at-least-once 재처리 시 동일 source_table + source_id + event_type 조합 중복 INSERT 회피. ADR 0068 §2-8 H-1 Redis SETNX + DB UQ 패턴 정합.

### 3-2. V30 — performance_summary + pnl_daily

```sql
-- V30__performance_summary_and_pnl_daily.sql
-- T-PERF 영속 (ADR 0072 §2-3·§2-4 C-1·D-1)
-- batch DailyPerformanceJob 매일 00:30 KST cron 결과 영속
-- pnl_daily 기존 V18 환경 reuse (V18 ALTER 부재 — 신규 환경에서만 IF NOT EXISTS LOCK)
CREATE TABLE IF NOT EXISTS pnl_daily (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    realized_pnl NUMERIC(18, 2) NOT NULL DEFAULT 0,
    win_count INT NOT NULL DEFAULT 0,
    loss_count INT NOT NULL DEFAULT 0,
    trade_count INT NOT NULL DEFAULT 0,
    avg_profit NUMERIC(18, 2),
    avg_loss NUMERIC(18, 2),
    equity_at_close NUMERIC(18, 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_pnl_daily_user_date ON pnl_daily (user_id, trade_date);
CREATE INDEX IF NOT EXISTS ix_pnl_daily_user_date_desc ON pnl_daily (user_id, trade_date DESC);

CREATE TABLE IF NOT EXISTS performance_summary (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    period VARCHAR(8) NOT NULL,              -- DAY · WEEK · MONTH · YEAR · ALL
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_pnl NUMERIC(18, 2) NOT NULL,
    mdd_pct NUMERIC(8, 4) NOT NULL DEFAULT 0,
    win_rate_pct NUMERIC(5, 2) NOT NULL DEFAULT 0,
    profit_loss_ratio NUMERIC(8, 4),
    trade_count INT NOT NULL DEFAULT 0,
    metrics JSONB NOT NULL,                  -- {"sharpe": null, "sortino": null, "cagr": null} placeholder
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_perf_summary_user_period ON performance_summary (user_id, period, period_start, period_end);
CREATE INDEX IF NOT EXISTS ix_perf_summary_user_period_desc ON performance_summary (user_id, period, period_end DESC);
```

**기존 V18 pnl_daily 환경 reuse**: V18(과거 트랙)에 pnl_daily가 정의되어 있다면 V30 CREATE TABLE IF NOT EXISTS는 no-op. backend-core 자율 검증 — V18 정의가 V30 컬럼 매트릭스와 differ 시 ALTER 추가 필요 시점만 별도 V30b 마이그레이션 발행 (헌법 §6 IF NOT EXISTS 정합).

---

## §4. Kafka 토픽 매트릭스 (신규 publish 0 LOCK)

| 항목 | 채택 |
|---|---|
| **신규 publish 토픽** | **0 LOCK** (본 사이클) |
| **listen 토픽 5건** | order.filled.v1 + signal.generated.v1 + risk.violation.v1 + reconcile.drift.v1 + position.updated.v1 (기존 baseline reuse) |
| **listener** | consumer 모듈 또는 api 모듈 단일 `JournalEventConsumer` (backend-core 자율 결정 — api 단순화 권장) |
| **idempotency** | DB UQ `ux_trading_journal_source` (source_table + source_id + event_type) + at-least-once 재처리 안전 |

**topic listen 패턴**:
```kotlin
@KafkaListener(topics = ["order.filled.v1"], groupId = "journal-event-consumer")
fun onOrderFilled(record: ConsumerRecord<String, String>) {
    val event = mapper.readValue<OrderFilledEvent>(record.value())
    journalService.recordOrderFilled(event)  // INSERT IGNORE on UQ conflict
}
```

---

## §5. Redis 키 매트릭스

ADR 0070·0071 패턴 정합. TTL 매트릭스 LOCK.

| Redis 키 | TTL | 용도 | sub-agent |
|---|---|---|---|
| `perf:summary:{userId}:{period}:{periodStart}:{periodEnd}` | 1h | T-PERF Performance Summary 캐시 (inline aggregate fallback 결과) | backend-core (Phase 3) |
| `perf:equity:{userId}:{from}:{to}` | 1h | T-PERF Equity Curve 캐시 | backend-core (Phase 3) |
| `journal:lock:{userId}:{eventKey}` | 30s | Kafka 재처리 시 동시 INSERT 방지 분산락 (옵션 — DB UQ로 충분 시 생략 가능, backend-core 자율) | backend-core (Phase 1) |

**캐시 invalidation**: pnl_daily INSERT 시점(매일 00:30 cron)에 해당 userId의 `perf:summary:{userId}:*` Redis pattern delete. backend-core 자율 결정 (Redis Lua script 또는 SCAN+DEL).

---

## §6. REST endpoint 매트릭스 (5 endpoint Swagger 4단 LOCK)

ADR 0070·0071 패턴 정합. Phase 2·3 backend-core 책임.

### 6-1. GET /api/journal/entries — 시계열 list 페이징

```kotlin
@Tag(name = "Trading Journal", description = "매매일지 시계열 영속 + 사용자 메모 — ADR 0072")
@RestController
@RequestMapping("/api/journal")
class TradingJournalController(
    private val journalService: TradingJournalService,
) {

    @Operation(
        summary = "매매일지 시계열 조회 (페이징, 최신순)",
        description = """
            order·signal·risk·reconcile·position 5 이벤트 source 통합 시계열.
            필터: from·to(기간) + instrumentCode(종목) + eventType(이벤트 타입).
        """
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "매매일지 페이지"),
        ApiResponse(responseCode = "400", description = "userId·기간 검증 실패"),
    ])
    @GetMapping("/entries")
    fun listEntries(
        @Parameter(description = "사용자 ID (multi-tenant 격리)", example = "12345678") @RequestParam userId: Long,
        @Parameter(description = "기간 시작 (ISO 8601, 옵션)", example = "2026-05-01T00:00:00+09:00") @RequestParam(required = false) from: OffsetDateTime?,
        @Parameter(description = "기간 종료 (ISO 8601, 옵션)", example = "2026-05-14T23:59:59+09:00") @RequestParam(required = false) to: OffsetDateTime?,
        @Parameter(description = "종목 코드 필터 (옵션)", example = "005930") @RequestParam(required = false) instrumentCode: String?,
        @Parameter(description = "이벤트 타입 필터 (옵션 — ORDER_FILLED·SIGNAL_GENERATED·RISK_VIOLATION·RECONCILE_DRIFT·POSITION_UPDATED)") @RequestParam(required = false) eventType: String?,
        @Parameter(description = "페이지 (0-base)", example = "0") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지당 row 수", example = "20") @RequestParam(defaultValue = "20") size: Int,
    ): Page<TradingJournalEntryDto> = ...
}

@Schema(description = "매매일지 entry")
data class TradingJournalEntryDto(
    @Schema(description = "entry ID", example = "1001") val id: Long,
    @Schema(description = "이벤트 타입", example = "ORDER_FILLED") val eventType: String,
    @Schema(description = "종목 코드 (nullable)", example = "005930") val instrumentCode: String?,
    @Schema(description = "종목명 (nullable)", example = "삼성전자") val instrumentName: String?,
    @Schema(description = "발생 시각 (KST)", example = "2026-05-14T14:32:18+09:00") val occurredAt: OffsetDateTime,
    @Schema(description = "한국어 요약", example = "체결: 삼성전자 매수 100주 @ 75,000원") val summary: String,
    @Schema(description = "실현 손익 (체결 이벤트 한정, 그 외 null)", example = "150000.00") val realizedPnl: BigDecimal?,
    @Schema(description = "원본 이벤트 페이로드 (audit + AI context)") val payload: Map<String, Any>,
    @Schema(description = "사용자 메모 (있는 경우)") val notes: List<JournalNoteDto>,
)
```

### 6-2. GET /api/journal/entries/{id} — entry 단건 상세

```kotlin
@Operation(
    summary = "매매일지 entry 단건 상세",
    description = "entry + 사용자 메모 풀세트. userId mismatch 시 404."
)
@GetMapping("/entries/{id}")
fun findEntry(
    @Parameter(description = "entry ID", example = "1001") @PathVariable id: Long,
    @Parameter(description = "사용자 ID (격리 검증)", example = "12345678") @RequestParam userId: Long,
): TradingJournalEntryDto = ...
```

### 6-3. POST /api/journal/notes — 사용자 메모 작성

```kotlin
@Operation(
    summary = "매매일지 entry에 사용자 메모 작성",
    description = "사용자가 매매 회고 자유 텍스트 입력. 1 entry: N notes 가능."
)
@PostMapping("/notes")
fun writeNote(@Valid @RequestBody request: JournalNoteRequest): ResponseEntity<JournalNoteDto> = ...

@Schema(description = "메모 작성 요청")
data class JournalNoteRequest(
    @Schema(description = "사용자 ID", example = "12345678") @field:NotNull val userId: Long,
    @Schema(description = "매매일지 entry ID", example = "1001") @field:NotNull val journalEntryId: Long,
    @Schema(description = "메모 본문 (free-form)", example = "이번 매매는 RSI 과매도 진입 + MACD bullish cross 신호로 진입. 익절 룰 7%까지 유지 검토.")
    @field:NotBlank @field:Size(max = 4000) val noteText: String,
)

@Schema(description = "메모")
data class JournalNoteDto(
    @Schema(description = "메모 ID", example = "501") val id: Long,
    @Schema(description = "메모 본문") val noteText: String,
    @Schema(description = "작성 시각", example = "2026-05-14T18:45:12+09:00") val createdAt: OffsetDateTime,
    @Schema(description = "갱신 시각", example = "2026-05-14T18:45:12+09:00") val updatedAt: OffsetDateTime,
)
```

### 6-4. GET /api/performance/summary — Performance 통계 요약

```kotlin
@Tag(name = "Performance", description = "성과 통계 — ADR 0072")
@RestController
@RequestMapping("/api/performance")
class PerformanceController(
    private val performanceService: PerformanceService,
) {

    @Operation(
        summary = "성과 통계 요약 (P&L·MDD·승률·평균 손익비)",
        description = """
            기간(period) + 시작·종료(periodStart·periodEnd) 단위 통계 요약.
            period=DAY·WEEK·MONTH·YEAR·ALL 5가지 + 사용자 지정 from·to.
            pnl_daily aggregate + Redis cache 1h.
        """
    )
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "사용자 ID", example = "12345678") @RequestParam userId: Long,
        @Parameter(description = "기간 단위", example = "MONTH") @RequestParam(defaultValue = "MONTH") period: String,
        @Parameter(description = "기간 시작 (옵션 — 미지정 시 period 기준 default)", example = "2026-05-01") @RequestParam(required = false) periodStart: LocalDate?,
        @Parameter(description = "기간 종료 (옵션 — 미지정 시 today)", example = "2026-05-14") @RequestParam(required = false) periodEnd: LocalDate?,
    ): PerformanceSummaryDto = ...
}

@Schema(description = "성과 통계 요약")
data class PerformanceSummaryDto(
    @Schema(description = "기간 단위", example = "MONTH") val period: String,
    @Schema(description = "기간 시작", example = "2026-05-01") val periodStart: LocalDate,
    @Schema(description = "기간 종료", example = "2026-05-14") val periodEnd: LocalDate,
    @Schema(description = "총 P&L (원)", example = "1250000.00") val totalPnl: BigDecimal,
    @Schema(description = "MDD (%)", example = "8.4321") val mddPct: BigDecimal,
    @Schema(description = "승률 (%)", example = "62.50") val winRatePct: BigDecimal,
    @Schema(description = "평균 손익비 (profit / abs(loss))", example = "1.8462") val profitLossRatio: BigDecimal?,
    @Schema(description = "거래 횟수", example = "32") val tradeCount: Int,
    @Schema(description = "산정 시각 (KST)", example = "2026-05-14T18:50:00+09:00") val computedAt: OffsetDateTime,
    @Schema(description = "캐시 HIT 여부", example = "false") val cacheHit: Boolean,
)
```

### 6-5. GET /api/performance/equity-curve — Equity Curve 시계열

```kotlin
@Operation(
    summary = "Equity Curve 시계열 조회",
    description = """
        일별 누적 P&L 시계열. equity curve 차트 source.
        from·to 기간 (default: 최근 30 거래일).
    """
)
@GetMapping("/equity-curve")
fun equityCurve(
    @Parameter(description = "사용자 ID", example = "12345678") @RequestParam userId: Long,
    @Parameter(description = "시작 일자", example = "2026-04-14") @RequestParam(required = false) from: LocalDate?,
    @Parameter(description = "종료 일자", example = "2026-05-14") @RequestParam(required = false) to: LocalDate?,
): EquityCurveDto = ...

@Schema(description = "Equity Curve 시계열")
data class EquityCurveDto(
    @Schema(description = "시작 일자") val from: LocalDate,
    @Schema(description = "종료 일자") val to: LocalDate,
    @Schema(description = "일별 점 시계열") val points: List<EquityPointDto>,
)

@Schema(description = "Equity Curve 점")
data class EquityPointDto(
    @Schema(description = "거래일", example = "2026-05-14") val tradeDate: LocalDate,
    @Schema(description = "당일 P&L", example = "150000.00") val dailyPnl: BigDecimal,
    @Schema(description = "누적 P&L (equity)", example = "1250000.00") val cumulativePnl: BigDecimal,
    @Schema(description = "거래 횟수", example = "3") val tradeCount: Int,
)
```

**userId mismatch 시 6-2·6-3 404 LOCK** (ADR 0010 multi-tenant 격리 정합).

---

## §7. STOMP 토픽 매트릭스 (신규 0 LOCK)

본 사이클 STOMP fanout 0. T-J·T-PERF 모두 오프라인 회고 영역 — 실시간 push 부재.

**1차 LOCK frontend 호출**: REST endpoint 5건 polling 기반. React Query staleTime 적정 LOCK (journal entries 30s · performance summary 5min).

**post-MVP backlog**: journal.recorded.v1 STOMP push (사용자가 화면에 매매일지 페이지 열어둔 상태에서 신규 entry 발생 시 실시간 추가) — backlog §59.

---

## §8. UI 변경 매트릭스 (Phase 4 frontend 합류)

ADR §2-6 F-1 채택 — 2 페이지 + AppHeader 메뉴 2 추가.

| UI 영역 | 변경 | 사유 |
|---|---|---|
| `/journal` 신규 페이지 | **TradingJournalPage 신규** | T-J 진입점 |
| `/performance` 신규 페이지 | **PerformanceDashboardPage 신규** | T-PERF 진입점 |
| AppHeader.tsx 갱신 | **메뉴 2 추가 (매매일지·성과)** | 진입점 합류 |
| 다른 페이지 | **변경 0** | T-J·T-PERF 비범위 |

**Phase 4 frontend 산출물 핵심**:
```
frontend/src/features/journal/
├── components/
│   ├── JournalEntryList.tsx
│   ├── JournalEntryFilterBar.tsx
│   ├── JournalEntryDetailModal.tsx
│   └── JournalNoteForm.tsx
├── hooks/
│   ├── useJournalEntries.ts
│   ├── useJournalEntry.ts
│   └── useWriteJournalNote.ts
└── api/
    └── journalApi.ts

frontend/src/features/performance/
├── components/
│   ├── PerformanceSummaryCards.tsx     # 5 지표 카드
│   ├── EquityCurveChart.tsx             # LWC v4 line chart
│   ├── DailyPnlBarChart.tsx             # LWC v4 histogram
│   └── PerformancePeriodSelector.tsx
├── hooks/
│   ├── usePerformanceSummary.ts
│   └── useEquityCurve.ts
└── api/
    └── performanceApi.ts

frontend/src/pages/
├── TradingJournalPage.tsx
└── PerformanceDashboardPage.tsx
```

AppHeader 갱신:
```typescript
// frontend/src/components/layout/AppHeader.tsx (기존)
<NavLink to="/journal">매매일지</NavLink>
<NavLink to="/performance">성과</NavLink>
```

---

## §9. sub-agent 디스패치 매트릭스 (Phase 분리 LOCK)

### 9-1. 직렬 디스패치 매트릭스

**원칙**: T-J(데이터 영속) → T-PERF(통계 계산) 의존성 직선 LOCK + frontend 단발 합류:

- **Phase 1** — backend-core T-J: domain port + JournalEventConsumer + Flyway V29 + REST 3 endpoint (api 또는 consumer 모듈, backend-core 자율 결정 — api 권장 단순화)
- **Phase 2** — backend-core T-PERF: domain port + batch DailyPerformanceJob + Flyway V30 + REST 2 endpoint + Redis cache
- **Phase 3** — frontend: `/journal` + `/performance` 2 페이지 + AppHeader 메뉴 2 추가

| 순서 | Phase | sub-agent | 영역 | 의존 |
|---|---|---|---|---|
| 1 | **Phase 1** | **backend-core** | api(또는 consumer) + Flyway V29 + REST 3 (Trading Journal) | order·signal·risk·reconcile·position 5 baseline 영속 reuse |
| 2 | **Phase 2** | **backend-core** | api + batch + Flyway V30 + REST 2 + Redis cache (Performance) | Phase 1 trading_journal 영속 + pnl_daily aggregate |
| 3 | **Phase 3** | **frontend** | 2 페이지 + AppHeader | Phase 1·2 REST endpoint 5건 머지 후 |

**병렬 가능 검토**: Phase 1·2 backend-core 영역 동일 — 단일 sub-agent 직렬 dispatch가 conflict 회피 정합. Phase 3 frontend는 REST 5 endpoint 의존 → 직렬 LOCK.

**ObjectProvider graceful fallback** (헌법 §6 + ADR 0066·0070·0071 패턴): Phase 1·2 미합류 시 frontend는 "데이터 없음" 표시 (mock 합성 0 LOCK).

### 9-2. Phase 1 산출물 매트릭스 (backend-core T-J)

| 영역 | 파일 | 책임 |
|---|---|---|
| **domain** | `domain/.../journal/TradingJournalEntry.kt` | 도메인 entity (event_type·summary·payload·realized_pnl 등) |
| **domain** | `domain/.../journal/JournalNote.kt` | 사용자 메모 entity |
| **domain** | `domain/.../journal/JournalEventType.kt` | enum (ORDER_FILLED · SIGNAL_GENERATED · RISK_VIOLATION · RECONCILE_DRIFT · POSITION_UPDATED) |
| **domain port** | `domain/.../journal/port/TradingJournalRepository.kt` | findByUser·findEntries·saveEntry·saveNote (헌법 §6 abstract LOCK) |
| **api/persistence** | `api/db/migration/V29__trading_journal_and_note.sql` | DDL IF NOT EXISTS (본 ADR §3-1) |
| **api/persistence** | `api/.../persistence/journal/{TradingJournalJpaEntity,JournalNoteJpaEntity,TradingJournalJpaRepository,JournalNoteJpaRepository,TradingJournalRepositoryAdapter}.kt` | JPA 어댑터 |
| **api/messaging** | `api/.../messaging/JournalEventConsumer.kt` | 5 Kafka 토픽 listen → TradingJournalEntry 변환 + INSERT IGNORE on UQ conflict |
| **api/service** | `api/.../service/journal/TradingJournalService.kt` | REST endpoint backing service |
| **api/controller** | `api/.../controller/journal/TradingJournalController.kt` | REST 3 endpoint Swagger 4단 (§6-1·§6-2·§6-3) |
| **api/dto** | `api/.../dto/journal/{TradingJournalEntryDto,JournalNoteDto,JournalNoteRequest}.kt` | DTO (§6 매트릭스 정합) |
| **test** | `api/src/test/.../JournalEventConsumerTest.kt` | 6 케이스 (5 이벤트 타입 변환 + 멱등성 UQ conflict 재처리) |
| **test** | `api/src/test/.../TradingJournalServiceTest.kt` | 4 케이스 (list 페이징·entry detail·note 작성·userId 격리) |
| **test** | `api/src/test/.../TradingJournalControllerTest.kt` | 5 케이스 (GET entries 200·GET entry 404·POST note 201·POST note 400·userId mismatch 404) |

**Phase 1 단위 테스트 ~15건 GREEN 의무**.

### 9-3. Phase 2 산출물 매트릭스 (backend-core T-PERF)

| 영역 | 파일 | 책임 |
|---|---|---|
| **domain** | `domain/.../performance/PerformanceSummary.kt` | 도메인 entity (period·total_pnl·mdd·win_rate·plratio) |
| **domain** | `domain/.../performance/EquityPoint.kt` | equity curve 점 entity |
| **domain** | `domain/.../performance/PnlDaily.kt` | 일별 P&L entity |
| **domain port** | `domain/.../performance/port/{PerformanceRepository,PerformanceCalculator}.kt` | abstract LOCK |
| **api/persistence** | `api/db/migration/V30__performance_summary_and_pnl_daily.sql` | DDL IF NOT EXISTS (본 ADR §3-2) |
| **api/persistence** | `api/.../persistence/performance/{PerformanceSummaryJpaEntity,PnlDailyJpaEntity,*Repository,*Adapter}.kt` | JPA 어댑터 |
| **api/cache** | `api/.../infrastructure/cache/RedisPerformanceCacheAdapter.kt` | Redis cache TTL 1h (perf:summary·perf:equity) |
| **api/service** | `api/.../service/performance/PerformanceService.kt` | inline aggregate fallback + Redis cache + pnl_daily lookup |
| **api/calculator** | `api/.../service/performance/PerformanceCalculatorImpl.kt` | pure math (sum·avg·count·max·MDD 알고리즘) |
| **api/controller** | `api/.../controller/performance/PerformanceController.kt` | REST 2 endpoint Swagger 4단 (§6-4·§6-5) |
| **api/dto** | `api/.../dto/performance/{PerformanceSummaryDto,EquityCurveDto,EquityPointDto}.kt` | DTO |
| **batch** | `batch/.../job/performance/DailyPerformanceJob.kt` | 매일 00:30 KST cron — T-2 영업일 pnl_daily 일괄 집계 + performance_summary 갱신 (DAY·WEEK·MONTH·YEAR·ALL 5종) |
| **batch** | `batch/.../job/performance/PerformanceJobConfig.kt` | Spring Batch 설정 |
| **test** | `api/src/test/.../PerformanceCalculatorImplTest.kt` | 7 케이스 (총 P&L·일별 P&L·MDD 산정·승률·평균 손익비·empty·edge case) |
| **test** | `api/src/test/.../PerformanceServiceTest.kt` | 5 케이스 (cache HIT·cache MISS+inline·pnl_daily fallback·userId 격리·today inline only) |
| **test** | `api/src/test/.../PerformanceControllerTest.kt` | 4 케이스 (GET summary 200·GET equity 200·400 invalid period·userId mismatch 404) |
| **test** | `api/src/test/.../RedisPerformanceCacheAdapterTest.kt` | 3 케이스 (put TTL 1h · get HIT · invalidation) |
| **test** | `batch/src/test/.../DailyPerformanceJobTest.kt` | 3 케이스 (정상 집계·empty user·기존 row 갱신) |

**Phase 2 단위 테스트 ~22건 GREEN 의무**.

### 9-4. Phase 3 산출물 매트릭스 (frontend)

| 영역 | 파일 | 책임 |
|---|---|---|
| **pages** | `frontend/src/pages/TradingJournalPage.tsx` | `/journal` 페이지 root |
| **pages** | `frontend/src/pages/PerformanceDashboardPage.tsx` | `/performance` 페이지 root |
| **layout** | `frontend/src/components/layout/AppHeader.tsx` (수정) | 메뉴 2 추가 |
| **journal components** | `frontend/src/features/journal/components/{JournalEntryList,JournalEntryFilterBar,JournalEntryDetailModal,JournalNoteForm}.tsx` | list·필터·상세·메모 작성 |
| **journal hooks** | `frontend/src/features/journal/hooks/{useJournalEntries,useJournalEntry,useWriteJournalNote}.ts` | React Query mutations·queries |
| **journal api** | `frontend/src/features/journal/api/journalApi.ts` | axios endpoint + 타입 |
| **performance components** | `frontend/src/features/performance/components/{PerformanceSummaryCards,EquityCurveChart,DailyPnlBarChart,PerformancePeriodSelector}.tsx` | 5 지표 카드 + LWC v4 차트 2종 + period selector |
| **performance hooks** | `frontend/src/features/performance/hooks/{usePerformanceSummary,useEquityCurve}.ts` | React Query queries |
| **performance api** | `frontend/src/features/performance/api/performanceApi.ts` | axios endpoint + 타입 |
| **routing** | `frontend/src/App.tsx` (수정) | 2 신규 route 등록 |
| **test** | `frontend/src/features/journal/__tests__/JournalEntryList.test.tsx` | 3 케이스 (loading·entries render·empty state) |
| **test** | `frontend/src/features/performance/__tests__/PerformanceSummaryCards.test.tsx` | 3 케이스 (5 지표 표시·empty state·negative MDD) |
| **test** | `frontend/src/features/performance/__tests__/EquityCurveChart.test.tsx` | 2 케이스 (mount·empty data state) |

**Phase 3 단위 테스트 ~8건 GREEN 의무**.

---

## §10. 검증 시나리오 (E2E 의무 — dogfooding LOCK)

ADR 0070·0071 fitness 검증 패턴 정합. dogfooding 사이클로 사용자 메모리 `feedback_verification_strict` 정합 (할루시네이션 금지).

| # | 시나리오 | 검증 | 기대 |
|---|---|---|---|
| 1 | order.filled.v1 → journal entry | 모의 주문 1건 체결 발생 | trading_journal INSERT 1건 + event_type=ORDER_FILLED + summary 한국어 + realized_pnl 영속 |
| 2 | signal.generated.v1 → journal entry | 시그널 1건 발행 | trading_journal INSERT + event_type=SIGNAL_GENERATED |
| 3 | risk.violation.v1 → journal entry | risk 1건 BLOCKED | trading_journal INSERT + event_type=RISK_VIOLATION |
| 4 | reconcile.drift.v1 → journal entry | T-RECON drift 1건 검출 | trading_journal INSERT + event_type=RECONCILE_DRIFT + instrument_code nullable |
| 5 | position.updated.v1 → journal entry | T-O 체결 → position 갱신 | trading_journal INSERT + event_type=POSITION_UPDATED |
| 6 | 멱등성 UQ 재처리 | 동일 source_table+source_id+event_type Kafka 재처리 | UQ conflict on `ux_trading_journal_source` + INSERT 0건 + log WARN |
| 7 | GET /api/journal/entries 페이징 | 1주일 누적 30 entry 환경 + page=0 size=20 | 200 + 20 entry + 최신순 + payload·notes 포함 |
| 8 | POST /api/journal/notes | entry 1건에 메모 작성 | 201 + journal_note INSERT + GET /entries/{id} 재호출 시 notes 포함 |
| 9 | DailyPerformanceJob cron | 매일 00:30 KST 트리거 | pnl_daily INSERT (T-2 영업일) + performance_summary 5종 갱신 |
| 10 | GET /api/performance/summary 캐시 HIT | 2회 연속 호출 | 1회: cacheHit=false + 2회: cacheHit=true (elapsedMs < 50) |
| 11 | inline aggregate today 호출 | today period | pnl_daily 부재 → inline aggregate fallback 동작 + 결과 일치 |
| 12 | GET /api/performance/equity-curve | from·to 1개월 | points 시계열 + cumulativePnl 누적 monotonic 검증 |
| 13 | MDD 계산 정확성 | peak-trough fixture | mdd_pct 정밀도 NUMERIC(8, 4) 일치 |
| 14 | userId 격리 검증 | userA·userB 동시 데이터 | userA 호출 시 userB entry·summary 노출 0 LOCK |
| 15 | AI 호출 0 grep 검증 | `grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" api/journal api/performance batch/.../performance domain/journal domain/performance` | 0 lines |
| 16 | KIS endpoint 호출 0 검증 | wireshark 또는 mock KIS server | 본 사이클 KIS API 호출 0 (모든 데이터 영속 reuse) |
| 17 | mock 합성 0 검증 | trading_journal 0 row 환경 | "매매일지가 비어있습니다" 한국어 표시 + mock 합성 금지 |
| 18 | frontend `/journal` 진입 | AppHeader 메뉴 클릭 | 페이지 정상 mount + JournalEntryList 표시 + 필터 UI 정상 |
| 19 | frontend `/performance` 진입 | AppHeader 메뉴 클릭 | 5 지표 카드 + Equity Curve LWC v4 chart + Daily P&L bar chart 표시 |

---

## §11. 한계 + backlog 후보

### 11-1. 1차 MVP scope 한계

| 한계 | 사유 | backlog 의제 |
|---|---|---|
| Sharpe·Sortino·CAGR 정밀 지표 | 1차 5 지표 fixed LOCK | backlog §61 — Performance 풀세트 (CAGR·Sharpe·Sortino·Calmar) |
| 차트 스냅샷 자동 영속 (매매 발생 시) | 1차 비범위 — LWC v4 export to image 부재 | backlog §60 — chart_snapshot 영속 강화 (T-J Phase 4+) |
| AI 회고 코멘트 ("이 매매 복기해줘") | AI 호출 0 LOCK | W4 backlog §62 — AiCommentProvider 합류 |
| journal.recorded.v1 STOMP push | 1차 polling | backlog §59 — 실시간 push fanout |
| journal entry 사용자 직접 삭제 | 1차 비범위 (audit 보존 정책 검토 필요) | backlog §63 — soft delete 정책 |
| 종목별 회고 페이지 (`/journal/{code}`) | 1차 시계열만 | backlog §64 — 종목별 grouping view |
| 전략별 회고 페이지 (`/journal/strategy/{id}`) | 1차 비범위 | backlog §65 |
| performance_summary 시계열 chart | 1차 single period 카드만 | backlog §66 — 시계열 mini-chart |
| Sharpe·Sortino 합산 시 risk-free rate 정책 | 1차 비범위 | backlog §67 — RFR 정책 결정 |

### 11-2. 후속 ADR/backlog 후보

- **ADR 0073 (가칭)** — W4 T-AI 진입 spec (AiCommentProvider · 회고 코멘트 · 종목 분석)
- **ADR 0074 (가칭)** — Performance 풀세트 (Sharpe·Sortino·CAGR·Calmar 합류 + RFR 정책)
- **backlog §59** — journal.recorded.v1 STOMP push (post-MVP)
- **backlog §60** — chart_snapshot 영속 강화 (T-J Phase 4 — LWC v4 export to image)
- **backlog §61** — Performance 풀세트 (Sharpe·Sortino·CAGR·Calmar)
- **backlog §62** — AI 회고 코멘트 (W4 AiCommentProvider 합류)
- **backlog §63** — journal entry soft delete 정책
- **backlog §64** — 종목별 회고 view
- **backlog §65** — 전략별 회고 view
- **backlog §66** — performance_summary 시계열 mini-chart
- **backlog §67** — RFR 정책 결정 (Sharpe·Sortino 합산 prereq)

---

## §12. Rollback 시나리오

| 시나리오 | rollback 절차 |
|---|---|
| Phase 1 머지 후 Kafka listener 누락 이벤트 | DLQ 또는 audit 시계열에서 수동 replay (backend-core 자율) + `INSERT ... ON CONFLICT DO NOTHING` 멱등성 안전 |
| Phase 1 머지 후 trading_journal DDL 충돌 | IF NOT EXISTS 강제 LOCK으로 hotfix 회피 + Flyway baseline reset 불필요 |
| Phase 2 머지 후 DailyPerformanceJob 결과 부정확 | env `MAGICJAR_PERF_RECALC=true` 토글 시 과거 N일 재계산 (backend-core 자율) — performance_summary UPSERT |
| Phase 2 머지 후 Redis 캐시 메모리 폭주 | TTL 1h → 15m 또는 LRU 강화 |
| Phase 3 머지 후 frontend UX 회귀 | AppHeader 메뉴 hidden flag (`MAGICJAR_JOURNAL_UI_ENABLED=false`) — backend 정상 가동 fallback |
| order·signal·risk 이벤트 부재 환경 (W3 baseline 부재) | trading_journal empty + "매매일지가 비어있습니다" 한국어 graceful display (mock 합성 0) |

**Phase 1 단독 rollback 안전 LOCK** (헌법 §6 ObjectProvider): Phase 2·3 미합류 환경에서도 Phase 1 단독 부팅 가능. 모든 Phase 머지 후 dogfooding 실패 시 frontend hidden flag 우선 + backend 정상 가동 fallback.

---

## §13. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **A-1 trading_journal 신규 테이블 + Kafka listen 자동 INSERT** | ✓ 채택 | 통합 시계열 single source + 사용자 메모 합류 + audit + 회고 |
| (b) A-2 inline JOIN VIEW only | 폐기 | 5 테이블 JOIN 쿼리 + 페이징 성능 + 사용자 메모 합류 불가 |
| (c) A-3 5 테이블 직접 query + frontend merge | 폐기 | UX 응답 시간 + 페이징 일관성 부재 |
| (d) **B-1 batch + inline 듀얼** | ✓ 채택 | 시계열 검증 우위 + 사용자 인터랙티브 호출 즉시 + lag 최소화 |
| (e) **C-1 5 지표 fixed (P&L·일별 P&L·MDD·승률·평균 손익비)** | ✓ 채택 | 1차 LOC 적정 + 풀세트 검증 항목 cover |
| (f) C-2 사용자 토글 가시화 | 폐기 (1차) | LOC 비용 + 검증 부담 |
| (g) **D-1 performance_summary + pnl_daily 신규 영속** | ✓ 채택 | 통계 시계열 audit + 캐시 fallback + batch 결과 영속 |
| (h) D-3 영속 없이 inline only | 폐기 | audit 부재 + 1년치 회고 시간 |
| (i) **E-1 REST 5 endpoint** | ✓ 채택 | UX 완결성 + 회고 audit + 사용자 메모 |
| (j) **F-1 2 페이지 + AppHeader 2** | ✓ 채택 | UX 분리 명확 + 진입점 명시 |
| (k) F-2 단일 `/review` + 탭 2 | 폐기 (1차) | 페이지 크기 + 차트 컴포넌트 부담 |
| (l) **G-1 5 토픽 listen** | ✓ 채택 | 1:1 mapping + DLQ 분리 가능 + publisher 부담 0 |
| (m) G-2 통합 토픽 journal.event.v1 신규 | 폐기 | publisher 부담 + 기존 토픽 reuse 안 함 |
| (n) AiCommentProvider 합류 ("이 매매 복기해줘") | 폐기 (본 사이클) | 개발 룰 5 W4 한정 — AI 호출 0 LOCK 정합 |

---

## §14. KIS-first raw 인용 (헌법 §3 검증 매트릭스)

**본 사이클 KIS endpoint 신규 호출 0 LOCK** — 모든 데이터는 기존 영속 테이블 reuse:

| 컴포넌트 | 영속 source | KIS 호출 |
|---|---|---|
| trading_journal INSERT | order_execution(T-O baseline 영속) + signal(T-SIG baseline) + risk_event(T-RISK baseline) + reconcile_run(T-RECON baseline) + position(T-O baseline) Kafka listen | 0 |
| pnl_daily INSERT (DailyPerformanceJob) | order_execution aggregate (realized_pnl·trade_count·win/loss split) | 0 |
| Performance Summary 계산 | pnl_daily aggregate (sum·max·avg) — pure math | 0 |
| Equity Curve | pnl_daily 시계열 + running sum | 0 |

**baseline ADR cross-ref**:
- ADR 0066 (T-SIG 시그널 영속) — signal 테이블 reuse
- ADR 0067 (T-RISK 영속) — risk_event 테이블 reuse
- ADR 0068 (T-O 주문 실행 영속) — order_request·order_execution 테이블 reuse
- ADR 0070 (T-RECON 영속) — reconcile_run 테이블 reuse
- ADR 0012 (매매일지 아키텍처 baseline) — 본 ADR 0072 정밀 명세 정합

**예외 발생 시 의무 발동 절차**: 본 사이클 외부 데이터 fetch 합류 결정 시 (post-MVP backlog) — 그때 raw URL fetch + KDoc inline + 헌법 §3 5단계 절차 정독 의무. 본 ADR 0072 cycle에서는 영속 reuse LOCK으로 회피.

---

## §15. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-14 | ADR 0072 신규 발행 — T-J + T-PERF 묶음 spec/plan (W3 마지막 사이클 v3.6.4 patch). 8 의제 architect 자율 LOCK (A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1). 3 Phase 직렬 디스패치 (Phase 1 backend-core T-J: domain + Flyway V29 trading_journal+journal_note + JournalEventConsumer 5 Kafka listen + REST 3 endpoint Swagger 4단 · Phase 2 backend-core T-PERF: domain + Flyway V30 performance_summary+pnl_daily + batch DailyPerformanceJob 매일 00:30 KST cron + REST 2 endpoint + Redis cache TTL 1h · Phase 3 frontend: `/journal` + `/performance` 2 페이지 + AppHeader 메뉴 2 추가). KIS endpoint 호출 0 LOCK (모든 데이터 영속 reuse — order·signal·risk·reconcile·position 5 baseline). 5 지표 fixed LOCK (총 P&L·일별 P&L·MDD·승률·평균 손익비). Sharpe·Sortino·CAGR 비범위 (backlog §61). MDD 정밀도 NUMERIC(8,4). 멱등성 UQ `ux_trading_journal_source` (source_table + source_id + event_type) Kafka at-least-once 재처리 안전. Kafka 신규 publish 0 (기존 5 토픽 listen만). STOMP 0 (polling). REST 5 endpoint Swagger 4단 (GET entries 페이징 + GET entry detail + POST notes + GET summary + GET equity-curve). AI 호출 0 grep 검증 의무. mock 합성 0 (영속 부재 시 "매매일지가 비어있습니다" 한국어 graceful display). 검증 시나리오 19건. 한계 + backlog §59~§67 등록 예정. Rollback 매트릭스 + 트레이드오프 14건. KIS-first raw 호출 0 LOCK. 사용자 비전 정합: ★★★ 운영 회고 의무 (자동매매 본체 검증 핵심) + W4 AI 합류 prereq (사용자 메모 → AI prompt context). 사용자 자율 최종권한 위임 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). v3.6.3 T-SC 마감 후 W3 마지막 사이클 v3.6.4 patch. spec 본문: `_workspace/specs/pr-402-t-j-perf-spec.md`. CHANGELOG slot 4 anchor(architect + backend-core-tj + backend-core-tperf + frontend). (architect) |
