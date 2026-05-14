# PR-402 spec — T-J + T-PERF 묶음 spec/plan (ADR 0072 — W3 마지막 사이클, v3.6.4 patch)

- 작성일: 2026-05-14
- 작성자: architect (사용자 위임 자율 결정)
- 권위 ADR: **ADR 0072** (`_workspace/arch/adr/0072-t-j-perf-bundle-spec.md`)
- 본 spec 위치: `_workspace/specs/pr-402-t-j-perf-spec.md`
- target main 브랜치 (rebase): `main`
- baseline ADR cross-ref: 0012(매매일지) · 0026(AI 옵션) · 0030(SoT) · 0066(T-SIG 영속) · 0067(T-RISK 영속) · 0068(T-O 영속) · 0070(T-RECON 영속) · 0071(T-SC 패턴)

---

## §1. spec 목적

본 spec은 ADR 0072 정밀 명세 + sub-agent 디스패치 구체 매트릭스. ADR 0072 §1~§15 본문을 sub-agent 4명(architect 본인 + backend-core T-J + backend-core T-PERF + frontend)이 직접 실행 가능한 수준으로 분해.

**W3 마지막 사이클** — T-SC(v3.6.3) 마감 후 운영 회고 합류 단일 사이클 v3.6.4. 본 사이클 마감 후 dogfooding 검증 누적 일괄 → v3.7.0 baseline cut + W4 진입 (T-AI).

---

## §2. 사용자 비전 정합 fitness 검증 (사용자 메모리 의무)

ADR 0072 §1-2 정합. 본 spec sub-agent 산출물 검증 기준:

| 항목 | 의무 |
|---|---|
| **fitness ★★★** | 운영 회고 의무 — 자동매매 본체 검증 핵심 |
| **v1/v2 잔재 0** | 다중 사용자 격리 + 시계열 영속 + Kafka listen 자동 INSERT — v3 신규 의제 |
| **AI 호출 0** | grep 검증 6 디렉토리 (api/journal·api/performance·batch/.../performance·domain/journal·domain/performance·consumer/.../journal 합류 시) |
| **mock 합성 0** | trading_journal 0 row 환경 → "매매일지가 비어있습니다" 한국어 graceful display (사용자 메모리 `feedback_no_mock_data` 정합) |
| **KIS endpoint 호출 0 LOCK** | 모든 데이터 영속 reuse — universe·candle·order·signal·risk·reconcile·position 모두 baseline (사용자 메모리 `feedback_kis_first_policy` 정합) |
| **존댓말** | 사용자 메모리 `feedback_honorific` 정합 — PR body + 한국어 label 모두 존댓말 |

---

## §3. Phase 분리 LOCK 매트릭스

ADR 0072 §9 정합. 3 Phase 직렬 LOCK (병렬 가능 검토 후 직렬 선택).

### 3-1. Phase 1 — backend-core T-J (api 영역 단일)

- **branch**: `agent/backend-core/t-j-phase1`
- **author**: `bot+backend-core@magicjar.local`
- **base**: main (architect spec PR 머지 후)
- **PR title**: `feat(backend-core): T-J Phase 1 — Trading Journal 영속 + 5 Kafka listen + REST 3 endpoint (ADR 0072 Phase 1)`
- **영역**: `domain/journal/*` + `api/.../persistence/journal/*` + `api/db/migration/V29__*.sql` + `api/.../messaging/JournalEventConsumer.kt` + `api/.../service/journal/*` + `api/.../controller/journal/*` + `api/.../dto/journal/*`

**산출물**:

| 파일 | 책임 |
|---|---|
| `domain/.../journal/TradingJournalEntry.kt` | 도메인 entity (id·userId·eventType·instrumentCode·instrumentName·occurredAt·summary·payload·sourceTable·sourceId·realizedPnl·createdAt) |
| `domain/.../journal/JournalNote.kt` | 메모 entity (id·userId·journalEntryId·noteText·createdAt·updatedAt) |
| `domain/.../journal/JournalEventType.kt` | enum 5종 (ORDER_FILLED · SIGNAL_GENERATED · RISK_VIOLATION · RECONCILE_DRIFT · POSITION_UPDATED) |
| `domain/.../journal/port/TradingJournalRepository.kt` | abstract port (findByUser · findEntries · saveEntry · saveNote · 헌법 §6 abstract LOCK · default fun 0) |
| `api/db/migration/V29__trading_journal_and_note.sql` | DDL IF NOT EXISTS — ADR 0072 §3-1 본문 copy |
| `api/.../persistence/journal/TradingJournalJpaEntity.kt` | JPA mapping (payload jsonb 직렬화) |
| `api/.../persistence/journal/JournalNoteJpaEntity.kt` | JPA mapping |
| `api/.../persistence/journal/TradingJournalJpaRepository.kt` | Spring Data — abstract fun only |
| `api/.../persistence/journal/JournalNoteJpaRepository.kt` | Spring Data — abstract fun only |
| `api/.../persistence/journal/TradingJournalRepositoryAdapter.kt` | port 구현 (INSERT IGNORE on UQ conflict 멱등성) |
| `api/.../messaging/JournalEventConsumer.kt` | 5 @KafkaListener (order.filled.v1·signal.generated.v1·risk.violation.v1·reconcile.drift.v1·position.updated.v1) → JournalEventType 변환 + 한국어 summary 합성 + INSERT IGNORE |
| `api/.../messaging/JournalSummaryComposer.kt` | 한국어 summary 합성 ("체결: {name} {side} {qty}주 @ {price}원" 패턴) |
| `api/.../service/journal/TradingJournalService.kt` | REST endpoint backing |
| `api/.../controller/journal/TradingJournalController.kt` | REST 3 endpoint Swagger 4단 (§6-1·§6-2·§6-3 ADR 0072) |
| `api/.../dto/journal/TradingJournalEntryDto.kt` | DTO |
| `api/.../dto/journal/JournalNoteDto.kt` | DTO |
| `api/.../dto/journal/JournalNoteRequest.kt` | DTO |

**단위 테스트 ~15건 GREEN 의무**:

| 테스트 | 케이스 |
|---|---|
| `JournalEventConsumerTest` | 6 (ORDER_FILLED 변환 · SIGNAL_GENERATED 변환 · RISK_VIOLATION 변환 · RECONCILE_DRIFT instrumentCode null · POSITION_UPDATED 변환 · 멱등성 UQ conflict 재처리) |
| `JournalSummaryComposerTest` | 5 (5 이벤트 타입 한국어 합성 정합 — "체결: 삼성전자 매수 100주 @ 75,000원" 패턴) |
| `TradingJournalServiceTest` | 4 (list 페이징 · entry detail + notes · note 작성 · userId 격리) |
| `TradingJournalControllerTest` | 5 (GET entries 200 + filter · GET entry 404 mismatch · POST note 201 · POST note 400 invalid · GET entries 200 페이징) |

**grep 검증 (Phase 1 마감 시점)**:
```
grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" \
  domain/.../journal api/.../journal api/.../messaging/JournalEventConsumer.kt
# expected: 0 lines

grep -rn "KisRest\|KisAuthAdapter\|KisCandleAdapter\|KisBalanceAdapter\|KisOrderHttp" \
  domain/.../journal api/.../journal
# expected: 0 lines
```

**헌법 §6 정합 검증**: TradingJournalJpaRepository · JournalNoteJpaRepository 모두 body 가진 default fun 0건 LOCK. 부팅 smoke 의무.

### 3-2. Phase 2 — backend-core T-PERF (api + batch 영역)

- **branch**: `agent/backend-core/t-perf-phase2`
- **author**: `bot+backend-core@magicjar.local`
- **base**: main (Phase 1 머지 후)
- **PR title**: `feat(backend-core): T-PERF Phase 2 — Performance 통계 + batch DailyPerformanceJob + REST 2 endpoint + Redis cache (ADR 0072 Phase 2)`
- **영역**: `domain/performance/*` + `api/.../persistence/performance/*` + `api/db/migration/V30__*.sql` + `api/.../service/performance/*` + `api/.../infrastructure/cache/RedisPerformanceCacheAdapter.kt` + `api/.../controller/performance/*` + `api/.../dto/performance/*` + `batch/.../job/performance/*`

**산출물**:

| 파일 | 책임 |
|---|---|
| `domain/.../performance/PerformanceSummary.kt` | 도메인 entity (period·periodStart·periodEnd·totalPnl·mddPct·winRatePct·profitLossRatio·tradeCount·metrics·computedAt) |
| `domain/.../performance/EquityPoint.kt` | equity 점 (tradeDate·dailyPnl·cumulativePnl·tradeCount) |
| `domain/.../performance/PnlDaily.kt` | 일별 P&L (userId·tradeDate·realizedPnl·winCount·lossCount·tradeCount·avgProfit·avgLoss·equityAtClose) |
| `domain/.../performance/port/PerformanceRepository.kt` | abstract port (findSummary · saveSummary · findPnlDaily · savePnlDaily) |
| `domain/.../performance/port/PerformanceCalculator.kt` | abstract port (calculate · calculateEquityCurve) |
| `api/db/migration/V30__performance_summary_and_pnl_daily.sql` | DDL IF NOT EXISTS — ADR 0072 §3-2 본문 copy |
| `api/.../persistence/performance/PerformanceSummaryJpaEntity.kt` | JPA (metrics jsonb) |
| `api/.../persistence/performance/PnlDailyJpaEntity.kt` | JPA |
| `api/.../persistence/performance/PerformanceSummaryJpaRepository.kt` | abstract fun only |
| `api/.../persistence/performance/PnlDailyJpaRepository.kt` | abstract fun only |
| `api/.../persistence/performance/PerformanceRepositoryAdapter.kt` | port 구현 (UPSERT on `ux_perf_summary_user_period`) |
| `api/.../infrastructure/cache/RedisPerformanceCacheAdapter.kt` | Redis cache TTL 1h (perf:summary·perf:equity) + invalidation pattern |
| `api/.../service/performance/PerformanceService.kt` | inline aggregate fallback + Redis cache + pnl_daily lookup + DAY·WEEK·MONTH·YEAR·ALL 5 period default 계산 |
| `api/.../service/performance/PerformanceCalculatorImpl.kt` | pure math — totalPnl·MDD(equity curve max drawdown 알고리즘 LOCK)·winRate·profitLossRatio |
| `api/.../controller/performance/PerformanceController.kt` | REST 2 endpoint Swagger 4단 (§6-4·§6-5 ADR 0072) |
| `api/.../dto/performance/PerformanceSummaryDto.kt` | DTO |
| `api/.../dto/performance/EquityCurveDto.kt` | DTO |
| `api/.../dto/performance/EquityPointDto.kt` | DTO |
| `batch/.../job/performance/DailyPerformanceJob.kt` | 매일 00:30 KST cron (Spring `@Scheduled(cron = "0 30 0 * * *", zone = "Asia/Seoul")`) — T-2 영업일 pnl_daily 일괄 집계 + performance_summary 5종 갱신 + Redis invalidate |
| `batch/.../job/performance/PerformanceJobConfig.kt` | Spring Batch 설정 (chunk processor · userId 단위 step) |

**단위 테스트 ~22건 GREEN 의무**:

| 테스트 | 케이스 |
|---|---|
| `PerformanceCalculatorImplTest` | 7 (총 P&L 합산 · 일별 P&L grouping · MDD peak-trough 산정 · MDD edge case 단조증가 0% · 승률 · 평균 손익비 · empty trades) |
| `PerformanceServiceTest` | 5 (cache HIT 즉시 반환 · cache MISS inline aggregate · pnl_daily fallback · userId 격리 · today period inline only) |
| `PerformanceControllerTest` | 4 (GET summary 200 · GET equity-curve 200 · 400 invalid period 검증 · userId mismatch 404) |
| `RedisPerformanceCacheAdapterTest` | 3 (put TTL 1h · get HIT 역직렬화 · pattern invalidation `perf:summary:{userId}:*`) |
| `DailyPerformanceJobTest` | 3 (정상 cron 1 사용자 + N 거래 → pnl_daily INSERT 1 row + performance_summary 5종 UPSERT · empty user 0 skip · 기존 row 재계산 UPSERT) |

**grep 검증 (Phase 2 마감 시점)**:
```
grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" \
  domain/.../performance api/.../performance batch/.../performance api/.../service/performance
# expected: 0 lines

grep -rn "KisRest\|KisAuthAdapter\|KisCandleAdapter" \
  domain/.../performance api/.../performance batch/.../performance
# expected: 0 lines
```

**MDD 알고리즘 명세** (PerformanceCalculatorImpl LOCK):
```kotlin
fun maxDrawdown(equityCurve: List<EquityPoint>): BigDecimal {
    if (equityCurve.isEmpty()) return BigDecimal.ZERO
    var peak = equityCurve[0].cumulativePnl
    var maxDd = BigDecimal.ZERO
    for (point in equityCurve) {
        if (point.cumulativePnl > peak) peak = point.cumulativePnl
        if (peak.signum() > 0) {  // peak 양수 한정 (음수 equity 시 0% LOCK)
            val drawdown = (peak - point.cumulativePnl) / peak * BigDecimal(100)
            if (drawdown > maxDd) maxDd = drawdown
        }
    }
    return maxDd.setScale(4, RoundingMode.HALF_UP)
}
```

**Redis invalidation 패턴** (RedisPerformanceCacheAdapter):
```kotlin
fun invalidateUser(userId: Long) {
    val pattern = "perf:summary:$userId:*"
    val keys = redis.keys(pattern)
    if (keys.isNotEmpty()) redis.delete(keys)
    val equityPattern = "perf:equity:$userId:*"
    val equityKeys = redis.keys(equityPattern)
    if (equityKeys.isNotEmpty()) redis.delete(equityKeys)
}
```

### 3-3. Phase 3 — frontend (2 페이지 + AppHeader)

- **branch**: `agent/frontend/t-j-perf-phase3`
- **author**: `bot+frontend@magicjar.local`
- **base**: main (Phase 2 머지 후)
- **PR title**: `feat(frontend): T-J + T-PERF Phase 3 — TradingJournal·PerformanceDashboard 2 페이지 + AppHeader 메뉴 (ADR 0072 Phase 3)`
- **영역**: `frontend/src/pages/{TradingJournalPage,PerformanceDashboardPage}.tsx` + `frontend/src/features/journal/*` + `frontend/src/features/performance/*` + `frontend/src/components/layout/AppHeader.tsx` (수정) + `frontend/src/App.tsx` (수정)

**산출물** (ADR 0072 §9-4 정합):

| 파일 | 책임 |
|---|---|
| `frontend/src/pages/TradingJournalPage.tsx` | `/journal` 페이지 root — filter + list + detail modal |
| `frontend/src/pages/PerformanceDashboardPage.tsx` | `/performance` 페이지 root — 5 카드 + equity chart + daily P&L chart + period selector |
| `frontend/src/components/layout/AppHeader.tsx` (수정) | 메뉴 2 추가 (매매일지·성과) |
| `frontend/src/App.tsx` (수정) | `<Route path="/journal" .../>` + `<Route path="/performance" .../>` 등록 |
| `frontend/src/features/journal/components/JournalEntryList.tsx` | 페이징 list (rank·time·eventType·instrument·summary·realizedPnl + 메모 indicator) |
| `frontend/src/features/journal/components/JournalEntryFilterBar.tsx` | 필터 (기간 picker + 종목 검색 + eventType select) |
| `frontend/src/features/journal/components/JournalEntryDetailModal.tsx` | entry 단건 + payload 정밀 + notes list + 메모 작성 form |
| `frontend/src/features/journal/components/JournalNoteForm.tsx` | 메모 작성 (textarea + submit) |
| `frontend/src/features/journal/hooks/useJournalEntries.ts` | React Query `useInfiniteQuery` 또는 page-based `useQuery` |
| `frontend/src/features/journal/hooks/useJournalEntry.ts` | entry 단건 fetch |
| `frontend/src/features/journal/hooks/useWriteJournalNote.ts` | React Query mutation (POST + entry refetch invalidate) |
| `frontend/src/features/journal/api/journalApi.ts` | axios endpoint + TypeScript 타입 |
| `frontend/src/features/performance/components/PerformanceSummaryCards.tsx` | 5 지표 카드 (총 P&L 한국 원 포맷 + MDD % + 승률 % + 평균 손익비 + 거래 횟수) |
| `frontend/src/features/performance/components/EquityCurveChart.tsx` | LWC v4 line chart (createLineSeries + cumulativePnl 시계열) |
| `frontend/src/features/performance/components/DailyPnlBarChart.tsx` | LWC v4 histogram (createHistogramSeries + dailyPnl 시계열) |
| `frontend/src/features/performance/components/PerformancePeriodSelector.tsx` | period selector (DAY·WEEK·MONTH·YEAR·ALL 5종 button group) |
| `frontend/src/features/performance/hooks/usePerformanceSummary.ts` | React Query (period 변경 시 refetch) |
| `frontend/src/features/performance/hooks/useEquityCurve.ts` | React Query (from·to 변경 시 refetch) |
| `frontend/src/features/performance/api/performanceApi.ts` | axios + 타입 |

**단위 테스트 ~8건 GREEN 의무**:

| 테스트 | 케이스 |
|---|---|
| `JournalEntryList.test.tsx` | 3 (loading state · entries render · empty state "매매일지가 비어있습니다") |
| `JournalEntryDetailModal.test.tsx` | 2 (entry display + notes · note 작성 mutation success) |
| `PerformanceSummaryCards.test.tsx` | 3 (5 지표 표시 · empty state · negative MDD edge case) |
| `EquityCurveChart.test.tsx` | 2 (mount + LWC v4 chart instance · empty data state) |
| `DailyPnlBarChart.test.tsx` | 2 (mount + histogram · empty data) |

**LWC v4 chart 패턴** (ADR 0025 정합):
```typescript
import { createChart } from 'lightweight-charts';

const chart = createChart(containerRef.current, {
    width: 800,
    height: 320,
    layout: { background: { color: 'transparent' }, textColor: '#d1d5db' },
});
const lineSeries = chart.addLineSeries({ color: '#10b981', lineWidth: 2 });
lineSeries.setData(points.map(p => ({
    time: p.tradeDate as Time,
    value: Number(p.cumulativePnl),
})));
```

**한국어 label LOCK** (사용자 메모리 `feedback_honorific` 정합):
- "매매일지" · "성과" · "총 손익" · "최대 낙폭 (MDD)" · "승률" · "평균 손익비" · "거래 횟수" · "기간" · "종목" · "이벤트 타입"
- 빈 상태: "매매일지가 비어있습니다", "성과 데이터가 없습니다"

---

## §4. CHANGELOG slot 4 anchor 사전 발행 (architect 영역 — 헌법 §4)

architect PR diff에 anchor + slot row 4건 prepend (CHANGELOG.md 표 직후 최신 row 먼저).

```markdown
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-05-14 | **arch(journal+perf): PR-402 T-J + T-PERF 묶음 spec/plan 발행 (ADR 0072 신규 — W3 마지막 사이클, v3.6.4 patch)** — ADR 0072 본문 + spec 본문 + 정합 갱신 (architect 작성). (architect) | arch/spec | W3 마지막 사이클 T-J + T-PERF 묶음 spec — sub-agent 직렬 디스패치 LOCK · 운영 회고 + P&L 통계 합류 |
<!-- pr-402-slot-architect -->
| 2026-05-14 | **TBD (작성: backend-core-tj)** — T-J Phase 1 backend-core 산출물 (ADR 0072 Phase 1). | api/journal | TBD |
<!-- pr-402-slot-backend-core-tj -->
| 2026-05-14 | **TBD (작성: backend-core-tperf)** — T-PERF Phase 2 backend-core 산출물 (ADR 0072 Phase 2). | api/performance · batch | TBD |
<!-- pr-402-slot-backend-core-tperf -->
| 2026-05-14 | **TBD (작성: frontend)** — T-J + T-PERF Phase 3 frontend 산출물 (ADR 0072 Phase 3). | frontend | TBD |
<!-- pr-402-slot-frontend -->
<!-- pr-402-slot-end -->
```

sub-agent는 본인 row만 in-place edit (TBD 자리표시자만 실제 사유로 교체). 다른 row·다른 anchor 수정 절대 금지.

---

## §5. 정합 갱신 매트릭스 (architect PR diff 포함)

### 5-1. ADR README 갱신

- 헤더 카운트: `67건 sequential` → `68건 sequential`
- 헤더 footer: `+ 0066·0067·0068·0069·0070·0071 Accepted` → `+ 0066·0067·0068·0069·0070·0071·0072 Accepted`
- §6 row 추가 (전략·신호·리스크 11건):
  ```
  | 0072 | **T-J + T-PERF 묶음 spec/plan (Trading Journal 영속 + Performance 통계 + Flyway V29·V30 + REST 5 endpoint + batch DailyPerformanceJob) — Accepted (2026-05-14 architect 자율 결정 · 사용자 위임). A-1~H-1 8 의제 LOCK. 3 Phase 직렬(Phase 1 backend-core T-J: domain+Flyway V29+JournalEventConsumer 5 Kafka listen+REST 3 · Phase 2 backend-core T-PERF: domain+Flyway V30+batch DailyPerformanceJob+REST 2+Redis cache TTL 1h · Phase 3 frontend: `/journal`+`/performance` 2 페이지+AppHeader 메뉴 2). KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0. spec: `_workspace/specs/pr-402-t-j-perf-spec.md`.** |
  ```
  (§6 전략·신호·리스크 섹션에 격상 합류 — T-J·T-PERF는 운영 회고 영역이나 ADR README §10이 부재 시 §6 합류 OR 신설 §10-5 운영 회고 영역 추가 — architect 자율 결정으로 §6 row 합류 LOCK)
- §11 후속 ADR 번호: `0072부터 sequential` → `0073부터 sequential`
- §12 후속 예정: `0060 Performance 표준 → ADR 0072 흡수 closed (2026-05-14)` 갱신 row 추가

### 5-2. master-architecture.md 갱신

- 헤더 footer: `0066·0067·0068·0069·0070·0071 Accepted` → `0066·0067·0068·0069·0070·0071·0072 Accepted`
- §C.14 본문 보강:
  ```
  | C.14 | **Performance Loop (성과 통계)** | 일별/주별 P&L·MDD·승률·평균 손익비. **5 지표 fixed LOCK** (총 P&L·일별 P&L·MDD·승률·평균 손익비). **batch DailyPerformanceJob 매일 00:30 KST cron** + inline aggregate fallback + Redis cache TTL 1h. **Sharpe·Sortino·CAGR backlog §61** (post-MVP). **AI 호출 0 LOCK** + W4 회고 코멘트 backlog §62. ADR 0072 정밀 명세 정합. | 가능 (영속 데이터만) | T-PERF (ADR 0072 Accepted 2026-05-14) |
  ```
- §D.16 본문 보강:
  ```
  | D.16 | **Trading Journal (매매일지)** | order·signal·risk·reconcile·position 5 Kafka 이벤트 통합 시계열 영속(trading_journal 테이블). 사용자 메모(journal_note 1:N). 멱등성 UQ (source_table+source_id+event_type). 차트 스냅샷 자동 영속은 backlog §60 (post-MVP). ADR 0072 정밀 명세 정합. | 가능 (영속 only) | T-J (ADR 0072 Accepted 2026-05-14) |
  ```
- §10 ADR 인덱스 row 추가:
  ```
  | 0072 | T-J + T-PERF 묶음 spec/plan | 2026-05-14 Accepted | 운영 회고 + P&L 통계 |
  ```
- §13 변경 이력 row 추가

### 5-3. tracks-roadmap.md 갱신

- §2 T-J row 격상 (★ 표기 + W3 마지막 사이클):
  ```
  | T-J ★ | **Trading Journal (5 Kafka 이벤트 통합 시계열 + journal_note 1:N + REST 3 endpoint) — ADR 0072 Accepted 2026-05-14** | §D.16 | trading_journal 영속 (V29) + 멱등성 UQ + 사용자 메모 + Kafka 5 listen 자동 INSERT | T-SIG·T-RISK·T-O·T-RECON | T-PERF · W4 AI | **W3 ★ (마지막 사이클)** | M (Phase 1 backend-core 직렬) | backend-core+frontend |
  ```
- §2 T-PERF row 격상:
  ```
  | T-PERF ★ | **Performance Loop (5 지표 fixed + batch DailyPerformanceJob + REST 2 endpoint + Redis cache) — ADR 0072 Accepted 2026-05-14** | §C.14 | pnl_daily + performance_summary 영속 (V30) + MDD 알고리즘 + 매일 00:30 KST cron + inline fallback + 5 period (DAY·WEEK·MONTH·YEAR·ALL) | T-J·T-SIG·T-RISK | W4 AI 합류 | **W3 ★ (마지막 사이클)** | M (Phase 2 backend-core 직렬) | backend-core+frontend |
  ```
- §3 T-J outline 풀 보강 (ADR 0072 정합):
  ```
  ### T-J — Trading Journal (W3 마지막 사이클 — ADR 0072 Accepted 2026-05-14, v3.6.4 patch)
  - **마스터 §위치**: §D.16 (ADR 0072 정밀 명세 정합)
  - **목표**: order·signal·risk·reconcile·position 5 Kafka 이벤트 통합 시계열 영속 + 사용자 메모 합류 + REST 3 endpoint
  - **선행/후행**: T-SIG·T-RISK·T-O·T-RECON / T-PERF · W4 AI 합류
  - **오프라인 동작**: 필수 (영속 only, KIS endpoint 호출 0 LOCK)
  - **ADR**: **0072 Accepted 2026-05-14** + 0012 baseline + 0066·0067·0068·0070 영속 reuse
  - **데이터**: DB Flyway V29 (trading_journal + journal_note IF NOT EXISTS). Kafka listen 5 (order.filled.v1·signal.generated.v1·risk.violation.v1·reconcile.drift.v1·position.updated.v1). Redis (옵션 분산락 journal:lock)
  - **산출물 핵심** (ADR 0072 §9-2): Phase 1 backend-core — domain port + Flyway V29 + JournalEventConsumer + REST 3 endpoint
  - **비범위**: chart_snapshot 자동 영속 (backlog §60) · STOMP push (backlog §59) · AI 회고 코멘트 (W4 backlog §62)
  ```
- §3 T-PERF outline 풀 보강 (ADR 0072 정합):
  ```
  ### T-PERF — Performance Loop (W3 마지막 사이클 — ADR 0072 Accepted 2026-05-14, v3.6.4 patch)
  - **마스터 §위치**: §C.14
  - **목표**: 5 지표 fixed (총 P&L·일별 P&L·MDD·승률·평균 손익비) + batch DailyPerformanceJob 매일 00:30 KST + inline aggregate fallback + Redis cache TTL 1h
  - **선행/후행**: T-J·T-SIG·T-RISK / W4 AI 합류
  - **오프라인 동작**: 필수 (영속 데이터만)
  - **ADR**: **0072 Accepted 2026-05-14** + 0012 baseline cross-ref
  - **데이터**: DB Flyway V30 (performance_summary + pnl_daily IF NOT EXISTS). Redis (perf:summary·perf:equity TTL 1h). Kafka 0
  - **산출물 핵심** (ADR 0072 §9-3): Phase 2 backend-core — domain port + Flyway V30 + batch DailyPerformanceJob + REST 2 endpoint + PerformanceCalculatorImpl pure math + Redis invalidation
  - **비범위**: Sharpe·Sortino·CAGR·Calmar (backlog §61) · AI 복기 (W4 backlog §62) · RFR 정책 (backlog §67)
  ```
- §8 변경 이력 row 추가:
  ```
  | 2026-05-14 (PR-402) | T-J + T-PERF 묶음 spec/plan ADR 0072 발행 정합 — §2 T-J·T-PERF row 격상 (★ 표기 + W3 마지막 사이클), §3 T-J·T-PERF outline 풀 보강 (ADR 0072 정합 — Flyway V29·V30 + REST 5 endpoint + 5 지표 fixed + 5 Kafka listen). v3.6.4 patch. (architect) |
  ```

---

## §6. 작업 시작 절차 (sub-agent 의무)

ADR 0070·0071 패턴 정합. sub-agent dispatch 시점에 의무 절차:

### 6-1. 사이클 시작 (Phase 1 backend-core T-J)

```bash
# 1. 작업 directory 변경
cd /Users/ak-song/<worktree-path>

# 2. main 정합
git fetch origin main && git rebase origin/main

# 3. branch 생성
git checkout -b agent/backend-core/t-j-phase1

# 4. author 설정
git config user.email "bot+backend-core@magicjar.local"
git config user.name "magicjar-backend-core"

# 5. task 시작
node scripts/task.mjs start --agent backend-core \
  --goal "T-J Phase 1 — Trading Journal 영속 + 5 Kafka listen + REST 3 endpoint (ADR 0072)" \
  --deliverables "domain/journal port 4 + entity 3; Flyway V29 IF NOT EXISTS; JournalEventConsumer 5 listen; TradingJournalRepositoryAdapter; REST 3 endpoint Swagger 4단; 단위 테스트 ~15건 GREEN; AI 호출 0 grep PASS; KIS endpoint 호출 0 grep PASS"
```

### 6-2. 사이클 마감 (각 Phase)

```bash
# 검증 (의무)
./gradlew :api:check :domain:check  # Phase 1 + 2
./gradlew :batch:check              # Phase 2 한정
cd frontend && pnpm typecheck && pnpm build && pnpm test  # Phase 3

# grep 검증
grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" \
  <영역 디렉토리>
# expected: 0 lines

grep -rn "KisRest\|KisAuthAdapter\|KisCandleAdapter" <영역 디렉토리>
# expected: 0 lines

# CHANGELOG slot 본인 row in-place edit
# (TBD → 실제 사유로 교체)

# task check 4단계
node scripts/task.mjs check "<deliverable 항목명>"
# (deliverable 1건 완료 시마다)

# PR 생성
git push -u origin agent/backend-core/t-j-phase1
gh pr create --base main --title "..." --body "..."

# task done
node scripts/task.mjs done --pr <N>
```

### 6-3. 머지 후 정리 (orchestrator 영역, 헌법 §2)

```bash
cd <repo-root>
git pull --ff-only origin main
node scripts/task.mjs archive T-<id>
git worktree remove <path>
git branch -d agent/backend-core/t-j-phase1
```

---

## §7. 검증 시나리오 (E2E dogfooding 의무)

ADR 0072 §10 19건 정합. 본 spec PR 머지 후 sub-agent 4 cycle 마감 후 dogfooding 사이클로 검증 — 사용자 메모리 `feedback_verification_strict` 정합 (할루시네이션 금지, 머지 GREEN만으로 종결 금지).

| # | 시나리오 | 검증 evidence |
|---|---|---|
| 1 | order.filled → journal entry | PostgreSQL trading_journal SELECT + event_type=ORDER_FILLED + summary 한국어 |
| 2~5 | signal·risk·reconcile·position → journal entry | 4 시나리오 동일 패턴 검증 |
| 6 | 멱등성 UQ 재처리 | Kafka 동일 message 2회 send + DB row 1건 LOCK + log WARN |
| 7 | GET /api/journal/entries 페이징 | curl + Swagger UI 200 응답 + page=0 size=20 |
| 8 | POST /api/journal/notes | curl + 201 + DB SELECT note row 영속 |
| 9 | DailyPerformanceJob cron | 매일 00:30 KST 트리거 (또는 manual trigger) + pnl_daily INSERT 검증 |
| 10 | GET /api/performance/summary cache HIT | 2회 호출 evidence (1회 cacheHit=false · 2회 cacheHit=true) |
| 11 | inline aggregate today | period=DAY today + pnl_daily 부재 → inline 결과 |
| 12 | equity curve cumulativePnl monotonic | from·to 1개월 + 누적 sum 검증 |
| 13 | MDD 정확성 | fixture peak-trough + NUMERIC(8,4) 정밀도 |
| 14 | userId 격리 | userA 매매 + userB 매매 후 userA 호출 시 userB 미노출 LOCK |
| 15 | AI 호출 0 grep | 6 디렉토리 0 lines |
| 16 | KIS endpoint 호출 0 | 5 adapter 디렉토리 0 lines |
| 17 | mock 합성 0 | 빈 DB 환경에서 "매매일지가 비어있습니다" 한국어 |
| 18 | `/journal` 진입 | AppHeader 메뉴 클릭 → 페이지 mount + JournalEntryList |
| 19 | `/performance` 진입 | AppHeader 메뉴 클릭 → 5 카드 + 2 chart |

---

## §8. 한계 + post-MVP backlog (ADR 0072 §11 정합)

본 사이클 비범위:

- Sharpe·Sortino·CAGR 정밀 지표 (backlog §61)
- 차트 스냅샷 자동 영속 LWC v4 export (backlog §60)
- AI 회고 코멘트 (W4 backlog §62)
- journal.recorded.v1 STOMP push (backlog §59)
- journal entry soft delete 정책 (backlog §63)
- 종목별 회고 view `/journal/{code}` (backlog §64)
- 전략별 회고 view (backlog §65)
- performance_summary 시계열 mini-chart (backlog §66)
- RFR 정책 (Sharpe·Sortino prereq, backlog §67)

---

## §9. 사용자 메모리 정합 매트릭스

| 메모리 | 정합 |
|---|---|
| `feedback_v3_skepticism_no_regression` | fitness ★★★ 운영 회고 의무 §1-2 검증 + v1/v2 잔재 0 |
| `feedback_release_policy_patch_first` | v3.6.4 patch (W3 잔여 마지막 사이클) |
| `feedback_3tier_hierarchy` | architect spec → sub-agent 4 직렬 디스패치 |
| `feedback_no_mock_data` | trading_journal·pnl_daily 영속 부재 시 graceful empty display |
| `feedback_orchestrator_active_parallel` | Phase 1·2 backend-core 영역 동일 → 직렬 LOCK 채택 (병렬 가능 검토 후) |
| `feedback_kis_first_policy` | KIS endpoint 호출 0 LOCK §1-4 검증 |
| `feedback_swagger_pattern` | REST 5 endpoint Swagger 4단 한국어 + example LOCK |
| `feedback_verification_strict` | dogfooding evidence 19 시나리오 §10 의무 |
| `feedback_honorific` | 존댓말 (PR body + 한국어 label) |
| `feedback_qa_on_demand` | qa 게이트 0 (MVP) |
| `feedback_no_github_actions` | release tag W3 마감 후 v3.7.0 baseline |
| `project_system_vision` | 룰 본체 + AI 호출 0 §2-8 |
| `project_pr29_w1_close_in_progress` | 본 사이클은 W3 마지막 — W1 마감 후 진행 정합 |

---

## §10. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-14 | PR-402 spec 발행 — T-J + T-PERF 묶음 spec/plan. ADR 0072 정밀 명세 LOCK. Phase 분리 LOCK 3 PR 직렬 (Phase 1 backend-core T-J + Phase 2 backend-core T-PERF + Phase 3 frontend). 산출물 매트릭스 ~45 파일 + 단위 테스트 ~45건 GREEN 의무. Flyway V29·V30 IF NOT EXISTS LOCK. Kafka 신규 publish 0 + listen 5. STOMP 0. REST 5 endpoint Swagger 4단 한국어 + example. AI 호출 0 grep 검증 6 디렉토리. KIS endpoint 호출 0 grep 검증 5 adapter. mock 합성 0 (영속 부재 시 한국어 graceful display). 검증 시나리오 19건 dogfooding evidence 의무. CHANGELOG slot 4 anchor 사전 발행 (architect + backend-core-tj + backend-core-tperf + frontend). W3 마지막 사이클 v3.6.4 patch — v3.6.3 T-SC 마감 후. dogfooding 검증 누적 일괄 → v3.7.0 baseline cut + W4 진입 (T-AI). (architect) |
