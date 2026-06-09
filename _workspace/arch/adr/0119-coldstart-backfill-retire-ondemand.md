# ADR 0119 — 1분봉 proactive cold-start 백필 폐지 — on-demand 전환

- 상태: **Accepted (2026-06-09 — 사용자(최고 관리자) 결재 + 구현 완료)** — 전종목 일봉 마이그레이션(ADR 0118 R-BF) 중 일봉 wipe가 managed 종목 전체에 1m cold-start storm(126 거래일×~630콜/종목)을 유발한 incident 분석 결과, "전종목 126일 1m 미리 끌어오기"가 과수집임을 확인하고 proactive cold-start 백필 layer를 전면 은퇴(on-demand 전환)한다. 신규 코드 0·삭제 위주.
- 작성: market-data
- 영역: 데이터(A) — candle ingestion / 1m 수집 정책. 자본 결정권 0.
- 관계:
  - **ADR 0118** (전종목 일봉 universe + 야간 멀티시세 snapshot) — 본 폐지의 직접 트리거(일봉 wipe→cold-start storm) + 일봉/주봉/월/년봉 적재를 R-BF/R2가 담당하므로 cold-start의 daily 역할 무용화. 본 ADR이 그 위에 1m 수집 정책을 narrow화.
  - **ADR 0048** (Watchlist Ingestion + KIS WS 41 slot) — `WatchlistChangedKafkaListener`의 cold-start 트리거를 제거하되 `SubscriptionRouter` WS/polling 분배는 유지. 신규 watchlist 종목 1m은 WS 실시간이 채움.
  - **ADR 0062** (합산 interval 백엔드 precomputed) — `AggregateBackfillRunner`는 보존(DailyCloseFinalizer·CandleBackfillWorker·SymbolDetailMinuteGapFill·admin·부팅 합산 재생성이 사용). cold-start 호출 trigger만 제거.
  - **ADR 0086 / 0090** (CandleGapDetector 4축 교차검증 + 정밀 backfill) — `LastCandleAtMonitor`의 일봉 stale 감지 + `CandleBackfillWorker(ONE_DAY)` 정밀 backfill은 유지(한국석유 회귀 가드). 1m stale 감지 + cold-start 트리거만 제거.
  - **ADR 0105** (캔들 Reconciliation + 장기 1m retention) — 1m on-demand 갭충전(`SymbolDetailMinuteGapFillService` + `KisOneMinuteCandleRangePort`) 및 admin 수동 backfill(`CandleBackfillWorker`)은 보존. proactive 6개월/1년 미리 끌어오기 부분만 폐지.
  - **ADR 0048·0063·0091** (cold-start 흐름·NXT cover·UN/J resolver) — cold-start `ColdStartExecutor`가 은퇴하므로 그 안의 `KIS_COLD_START_INTERVALS` 5종 fetch + `resolveMrktDivCode` 사용 경로 제거. resolver 자체는 `KisRestCandleAdapter`에 잔존(on-demand·R-BF가 사용).
  - direction-guard: 실시간 1m(KIS WS tick) 권위 + REST는 bootstrap/backfill/fallback 원칙 유지 (§4). watchlist 자동삽입 금지·STRATEGY/DETAIL owner 분리 정책 무변경.

---

## 1. 배경 — 2026-06-09 cold-start storm incident

### 1-1. incident evidence

전종목 일봉 마이그레이션(ADR 0118 R-BF) 진행 중 **일봉 wipe**가 발생했다. 부팅 시 `CandleBootstrapRunner`가 managed universe 종목들의 `1d candle count == 0`을 감지하고 `ColdStartExecutor.submit(code)`를 무더기 호출했다. `ColdStartExecutor`는 `KIS_COLD_START_INTERVALS` 5종(1m·1d·1wk·1mo·1y)을 fetch하는데, 그중 **1m은 `ChartCandleProperties.oneMinute.defaultLookbackTradingDays`(=126 거래일) 기반 lookback**으로 날짜별 페이지네이션(~630콜/종목)을 돈다.

→ managed 종목 수 × 126 거래일 × 일자당 ~5콜 = **KIS 콜 폭증·저속 storm**. ADR 0116 rate limit 버킷이 막아주긴 하나 BG 버킷이 장시간 포화되어 다른 backfill·on-demand 경로가 starvation.

### 1-2. 핵심 발견 — proactive 1m 백필은 과수집이다

incident 분석 결과 "전종목 126일 1m 미리 끌어오기"는 실제 수요와 어긋난다:

- **실시간 1m**은 `TickToMinuteAggregator`가 **KIS WS 구독 종목(최대 `PRICE_TICK_MAX=30` + NXT)만** 매분 마감하여 DB 1m + Kafka로 흘린다. 즉 실시간으로 1m이 쌓이는 종목은 ~30개뿐인데, cold-start는 managed universe 전체(또는 watchlist 전체)에 대해 과거 126일치를 끌어왔다.
- **일봉/주봉/월/년봉**은 이제 **전종목 일봉 universe(R-BF/R2 · ADR 0118)**가 담당한다. cold-start가 1d·1wk·1mo·1y를 fetch하던 역할은 무용.
- **과거 1m 정밀 갭**이 실제 필요한 시점은 (a) 사용자가 종목 상세에 진입할 때(차트 표시), (b) admin이 수동으로 정밀 backfill을 돌릴 때뿐이다. 둘 다 이미 별도 on-demand 경로가 존재한다.

---

## 2. 결정

### 2-A. 1분봉 유입 지도 — 보존/제거 경계 LOCK

| 버킷 | 경로 | 결정 |
|------|------|------|
| **Ⓐ 실시간** | KIS WS 틱 → Redis 진행봉 → `TickToMinuteAggregator`(매분) → DB 1m + Kafka. `BoundaryAggregator`(1m→상위봉 라이브) 포함 | **보존 (절대)** |
| **Ⓑ 내부 자동 백필** | `ColdStartExecutor`(126일 1m + 5 interval) + 부팅/watchlist/stale 트리거 | **폐지 (본 ADR)** |
| **Ⓒ on-demand** | `SymbolDetailMinuteGapFillService`(차트 진입 시 bounded 갭충전, `KisOneMinuteCandleRangePort.fetchOneMinuteCandlesBetween`) + `CandleBackfillWorker`(admin/수동 정밀 갭 — `/admin/candle-curator/backfill`) | **보존** |

### 2-B. 제거 대상 (Ⓑ)

1. **`ColdStartExecutor` 전체 은퇴** — 빈 삭제 + `KIS_COLD_START_INTERVALS` 제거. 호출처 3곳 모두 제거:
   - `CandleBootstrapRunner` — 부팅 cold-start 트리거(1d==0 / 1m<700 / stale 분기) 전면 제거. **런너는 잔존**: 1m이 이미 있으나 합산봉(3m~12h)이 누락된 종목에 `AggregateBackfillRunner.backfill(code)`(KIS 호출 0)만 재실행하는 aggregate-only 경로가 의미 있으므로 유지. `magicjar.candle.bootstrap.enabled` toggle은 잔존(이제 aggregate-only 경로만 게이트).
   - `WatchlistChangedKafkaListener` — watchlist 종목 추가 시 cold-start 제거. `SubscriptionRouter.add`(WS/polling 분배)는 유지. 신규 종목 일봉=R-BF/R2 기보유, 1m=WS 실시간. 과거 1m 백필 안 함.
   - `LastCandleAtMonitor` — 1m(ONE_MIN) stale 감지 + cold-start 트리거 제거. **★일봉(ONE_DAY) stale 감지 + `CandleBackfillWorker(ONE_DAY)` 정밀 backfill + Slack 알림은 유지** (한국석유 004090 일봉 5개월 stale 회귀 가드).

### 2-C. 보존 대상 (절대 안 건드림)

`TickToMinuteAggregator` · `BoundaryAggregator` · `SymbolDetailMinuteGapFillService` · `CandleBackfillWorker`(+admin candle-curator 엔드포인트) · `AggregateBackfillRunner` · R-BF/R2 · `WatchlistPollingWorker`.

**`KisRestCandleAdapter`의 1m 126일 lookback(`oneMinuteTargetDays` / `ChartCandleProperties.oneMinute`) 은 제거하지 않는다** — `CandleBackfillWorker`(Ⓒ admin 수동)가 `kisRestPort.fetchCandles(code, ONE_MIN, count)` 경유로 동일 경로를 쓰므로, 제거하면 on-demand가 깨진다. ColdStart 전용 자산이 아니다.

### 2-D. deferred (사용자 주도 향후)

본 ADR은 proactive 백필 폐지만 다룬다. 다음은 사용자 주도로 별도 결정:
- 종목 상세 fetch 정책 narrow화 (현재 진입 시 bounded gap fill — 더 좁힐지).
- 전략 리서치 fetch 정책 (스크리너/발굴이 필요로 하는 1m 범위).
- 전략 라이브 시 활성 종목 1m 워밍업 fetch (자동매매 진입 직전 정합 보강).

---

## 3. 운영 단계 슬롯 (헌법 §9-5)

### 3-1. SLO 영향

- **개선** — KIS REST BG 버킷(ADR 0116) 포화 위험 대폭 감소. 부팅·watchlist add·일일 18:00 monitor 시 1m 무더기 백필 storm 제거 → KIS rate limit 여유 확보 + Outbox/on-demand 경로 starvation 해소.
- 일봉 SLA watchdog(`LastCandleAtMonitor` 일봉 5거래일)는 유지 → 일봉 stale 회귀 가드 SLO 무변동.
- 실시간 1m 경로(Ⓐ) 무영향 → 자동매매 시그널 data readiness SLO 무변동(active 종목은 WS 실시간 + on-demand gap fill로 READY 도달).

### 3-2. rollback plan

1. 코드 revert (git revert) — `ColdStartExecutor` 복원 + 호출처 3곳 원복. 삭제 위주 변경이라 revert 단순.
2. DB 마이그레이션 없음 — Flyway 변경 0, 스키마 무변동.
3. 부분 비활성으로는 복원 불가(빈 삭제). 긴급 시 `magicjar.candle.bootstrap.enabled=false`로 부팅 합산 재생성만 차단 가능(cold-start와 무관).

### 3-3. 관측 매트릭스

- audit_trail: `KIS_COLD_START_DONE`/`_PARTIAL_FAIL`/`_FAILED` row 신규 발행 0 (cold-start 은퇴 evidence). 기존 row는 historical 보존.
- `CANDLE_LAST_AT_MONITOR` audit row는 계속 발행(일봉 stale 검출 수만 집계).
- KIS rate limit 메트릭(ADR 0116 버킷 점유율) — 부팅/watchlist add 시점 BG 버킷 burst 사라짐 확인.
- frontend 영향 0 (Visual 회귀 시나리오 불요 — 데이터 수집 layer 변경, UI 무변동).

---

## 4. 방향성 보존 검증 (direction-guard)

- **Ⓐ 실시간 1m 회귀 0**: WS tick → `TickToMinuteAggregator` → DB 1m 경로 무변동. 장중 실시간 권위 = KIS WS tick 원칙(CLAUDE.md) 유지.
- **Ⓒ on-demand 회귀 0**: 종목 상세 진입 시 `SymbolDetailMinuteGapFillService` bounded gap fill + admin `CandleBackfillWorker` 무변동. REST는 bootstrap/backfill/fallback 원칙 유지.
- KIS-only 단일 출처 유지 · mock 합성 0 · LLM import 0.
- watchlist 자동삽입 금지 + STRATEGY/DETAIL owner 분리 무변동(`SubscriptionRouter` 보존).

---

## 5. 구현 결과 (2026-06-09)

- 삭제: `consumer/.../ingestion/ColdStartExecutor.kt` (전체).
- 수정: `CandleBootstrapRunner`(cold-start 트리거 제거·aggregate-only 유지) · `WatchlistChangedKafkaListener`(cold-start 제거) · `LastCandleAtMonitor`(1m 자동백필 제거·일봉 유지) · `LastCandleAtBootstrapRunner`/`AggregateBackfillRunner`/`CandleGapDetectorAdapter`/`LiveDailyCandleScheduler`(주석 정합).
- 테스트: `LastCandleAtMonitorTest`(일봉 단독 watchdog + 1m stale 트리거 0 검증) · `WatchlistChangedKafkaListenerTest`(cold-start 없이 add만) 정합.
- `:consumer:check` GREEN. Flyway/Kafka/Redis/STOMP/ENV 신규 자원 0.
