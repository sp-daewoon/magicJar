# 2026-09-17 차트/가격 데이터 정합성 정밀 감사

- 작성일: 2026-09-17
- 감사 범위: 시세 수집 파이프라인 → 캔들 영속/조회/보정 → 차트 렌더링 → 전략 평가 → 청산(손절/익절) + ADR/하네스 문서 얽힘
- 방법: 소스 직접 read + grep 실측 + 서브에이전트 3종 병렬 조사(reconcile/backfill · 전략 %후퇴 · ADR 얽힘). 추측 배제, 파일:라인/ADR 근거만 채택.
- 계기: 사용자(최고 관리자) 지적 — "차트 기반 매매를 원했으나 차트·가격 데이터 오염이 심해 %기반 전략으로 후퇴했다. 어디서부터 서비스가 흐트러졌는지 정밀 감지 + 개선안 + 벤치마킹 점수."
- 관련 문서: [audit-remediation-tracker.md](../../docs/architecture/policies/audit-remediation-tracker.md) P1-④ (가격 소스 혼합, 이연 상태) · [candle-data-policy-v2.md](../../docs/architecture/candle-data-policy-v2.md) · ADR 0019·0029·0057·0062·0064·0105·0106·0111·0118·0119·0137·0140

---

## §0. 한 줄 결론

> **데이터가 오염되어 차트 매매를 못 한 게 아니라, 캔들의 단일 진실원(single source of truth)을 확보하지 못한 채 기능을 계속 얹었고, 그 결과 캔들 최종값이 실행 순서에 의존하게 되었으며, 그 불신을 우회하려고 차트 조건을 버리고 %기반으로 후퇴했다. 오염은 원인이 아니라 증상이다.**

---

## §1. 서비스가 흐트러진 인과 사슬 (시간순)

### ① 최초 균열 — 캔들 진실원이 처음부터 다층 (ADR 0019 → 0029 → 0057 → 0105)

- ADR 0019가 "합성 폐기"로 시작했으나 한 파일 안에서 `2026-05-11 합산 5종 revoke → 05-12 부활`이 덧대짐. 사유는 사용자 지시 "ADR 폐기는 하지말어 백로그로 남겨" → **폐기 대신 상반된 결정이 같은 문서에 공존**.
- ADR 0105 §3-1이 진실원 계층을 6단으로 공식화: WS `H0UNCNT0` / Redis current / DB candle / REST 3종(`FHKST03010200`·`03010230`·`03010100`).
- 진실원이 6개 = "이 봉의 진짜 값이 무엇인가"의 답이 6곳에서 나올 수 있음.

### ② 오염의 구조화 — 같은 봉을 쓰는 경로 8개 + 쓰기 규칙 불일치

동일 `(code, interval, ts)` 한 행에 쓰는 경로(서브에이전트 실측):

| # | 경로 | 컴포넌트 | UPSERT 규칙 |
|---|------|----------|-------------|
| 1 | WS tick 집계 | `LiveCandleUpdater` / `TickToMinuteAggregator` | INSERT (source=KIS) |
| 2 | boundary 합산 | `BoundaryAggregator` / `CandleRefreshService.upsertAggregateCandles` | **DO UPDATE** |
| 3 | REST 갭 backfill | `CandleBackfillWorker.saveAll` | **DO NOTHING** |
| 4 | 전종목 일봉 캠페인 | `MarketDailyBackfillCampaign` | **DO NOTHING** |
| 5 | 야간 snapshot | `MarketDailyNightlySnapshotService` | **DO UPDATE** |
| 6 | date repair | `MarketDailyDateRepairService` | **DO UPDATE / DELETE** |
| 7 | daily rollup | `DailyFamilyRollupRunner` | **DO UPDATE / DELETE** |
| 8 | gap fill | `NoTickMinuteFallbackService`·`SymbolDetailMinuteGapFillService` | **DO NOTHING** |

**핵심 결함**:
- DO NOTHING(먼저 쓴 값 고정)과 DO UPDATE(무조건 덮음)가 혼재 → **최종값이 실행 순서에 의존**. `MarketDailyNightlySnapshotService.kt:33-39`가 "R2(DO UPDATE)가 DailyCloseFinalizer(DO NOTHING)를 권위로 덮는다"고 명시.
- `ConsumerCandleRepositoryAdapter.kt:86-88` — DO UPDATE에 WHERE 가드 없음("무조건 갱신"). 부정확한 backfill 값이 정상 확정봉을 덮을 수 있음.
- `EXCLUDED.source`까지 함께 덮여(`CandleRefreshService.kt:2103`) 봉의 진짜 출처 추적이 마지막 writer로 소실.
- `source` 필드 12종 혼재(`KIS`·`KIS-BACKFILL`·`KIS-MKT-DAILY-BF`·`KIS-MKT-DAILY-SNAP`·`KIS-NOTICK-FALLBACK`·`KIS-RANGE-GAPFILL`·`KIS-POLLING`·`KIS_DAILY_CLOSE`·`KIS_KSDINFO` …), VARCHAR(20), 하이픈/언더스코어 표준 불일치. 정작 `CandleHealthService`는 이 필드를 정합 판정에 안 쓰고 `quote:last` 문자열의 `"polling"` 포함 여부로 WS 추정 → **12종 라벨이 신뢰 판정에 미활용**.

### ③ 조회 시점 재조립 — 볼 때마다 봉이 달라질 수 있음

- `CandleQueryService.getCandles()`가 조회마다 **DB candle + Redis current + quote:last 3중 병합**. `mergeRedisCandle`·`mergeQuoteLastCurrentCandle`에 "same-ts replace" 패턴(2026-05-15 hotfix 주석).
- 저장된 확정 데이터가 아니라 조회 시점 조립값 → 자동매매가 보는 봉 ≠ 사용자 차트 봉 (시점 의존).

### ④ 프론트 이중 계산 — KST 경계를 백/프론트가 따로 계산

- `LightweightChart.tsx`: `KST_OFFSET_MS = 9*60*60*1000` 하드코딩, `barBoundaryTimestamp()`가 90m/4h/12h 경계를 **프론트에서 독립 재계산**.
- 백엔드 `LiveCandleUpdater.liveCandleStart()`도 같은 경계 계산. 같은 로직 2곳(DRY 위반) → 한쪽만 바뀌면 실시간 tick이 엉뚱한 봉에 붙음. NXT 통합세션(08:00~20:00) 앵커처럼 복잡할수록 위험.

### ⑤ 결정타 — 가격 소스 혼합 (감사 P1-④, 차트 조건이 실제로 틀렸던 증거)

`JvmStrategyEvaluator.directCandleAccess()` 주석 직접 인용:

> 종전 본 함수는 수정주가(adj) 우선이라 `close > ma(20)`이 **수정주가 vs 원주가 혼합 비교**가 됐다(액면분할·배당락 종목 조건 체계적 왜곡 + 백테스트 PriceAdjustmentMode.RAW 요청 무력화).

- DSL `close`(과거 adj)와 `ma(20)`(raw)를 비교하면 분할/배당 종목에서 조건이 체계적으로 틀림 = "차트 그림대로 매매가 안 되던" 가장 직접적 코드 증거.
- 현재 raw로 통일했으나, ADJUSTED 모드에서 지표+DSL 양쪽 동시 adj threading은 **미해결 후속과제**(주석 명시). audit-remediation-tracker P1-④ 상태 = **이연**.

### ⑥ 후퇴 — 차트 조건 포기, %기반으로 (ADR 0106 → 0111 → 0137 → 0140)

데이터 불신 → 청산 로직이 차트에서 %로 이동. ADR에 명시적으로 설계된 후퇴:

- **ADR 0106**: 자동매매 BUY는 `fixed_stop_loss_pct`·`take_profit_pct` 없으면 fail-closed 차단 → 고정 % 필수화. "차트 범위 손절·FVG/OB 무효화 손절·ATR 손절은 후속 확장"으로 명시.
- **ADR 0111 §2-5**: "scale-in/exit는 진입/청산 조건(DSL)이 아니다 … DSL parser/evaluator 변경 0" → 차트 조건과 청산 수준 의도적 분리.
- **ADR 0137**: REAL 계좌에서 vwap/ma DSL 청산이 휩쏘로 왕복비용만 잠식(-0.3~0.5%) → dsl_exit를 0.3% 버퍼 + min_hold_bars 8로 감쌈.
- **ADR 0140 (추세선 매매)**: 코드는 있으나 `magicjar.autopilot.planned-entry.enabled` **default false**, yml/env override 없음 → 프로덕션 미작동. 설계 자체가 "봉 경계마다 추세선 가격을 slPrice로 재박제 → tick watcher는 박제된 상수만 본다" = **추세선조차 최종적으로 %처럼 고정 가격으로 환원**.

실제 살아 움직이는 손절/익절(서브에이전트 실측):
- `ScaleInExitCalculator.stopLossPrice = avgPrice × (1 − pct/100)`, `profitPct = (current−avg)/avg×100`
- `PositionStopLossTickWatcher.stopPriceOf` / `PositionTakeProfitTickWatcher.takeProfitPriceOf` — 순수 평단 대비 %

**정리**: DSL/패턴/추세선은 "언제 사고팔지" 신호로만 평가되고, "어디서 자를지" 가격 수준은 **항상 진입가 대비 고정 %**가 지배. 원래 목표한 "차트 그림 기반 매매"는 진입 트리거에만 반쯤 남고 청산은 완전히 %로 이동.

---

## §2. 소스 ↔ 문서(ADR/하네스) 얽힘 — 복잡도 부채 정량

| 지표 | 실측값 | 의미 |
|---|---|---|
| 캔들/가격 주제에 얽힌 ADR | **94건 / 136건 (68%)** | ADR 3건 중 2건이 캔들 하나에 얽힘 |
| 반복 재발 매칭(hotfix/incident/regression) | 490파일 3,375매칭 (backlog.md 101 · ADR README 71) | 동일 결함 반복 재발 |
| `KisWebSocketBridge.kt` | **2,741 LOC / ADR 18종 73회 인용** | 한 파일이 18개 결정 짊어짐 |
| `CandleRefreshService.kt` | **2,237 LOC / ADR 11종 29회** | 캔들 재통합/뒤집기 ADR이 거의 다 여기 |
| `SignalGeneratedKafkaListener.kt` | **2,092 LOC / ADR 10종 41회 + TODO/우회 5개** | 리스크 게이트 비대화 |
| `candle-data-policy-v2.md` 헤더 amend 노트 | **17개 날짜별 amend** | 정책 1개에 ADR 12종 인용 누적 |

**얽힘의 구조적 원인 3가지**:

1. **"ADR 폐기 금지" 정책의 부작용** — 폐기 대신 헤더 노트 누적 → 한 ADR이 상반된 결정 동시 보관(0019: 합성 폐기 ↔ 부활). 이력 보존 의도가 "지금 무엇이 권위인가"를 흐림.
2. **같은 주제 반복 재결정** — 합성폐기↔부활(0019↔0062/0064), NXT통합↔J단일(0063/0091↔0118), WS슬롯 4연속(0048→0123→0124→0138). 매 재결정이 코드 주석으로 적층 → 파일 비대화. 문서 부채와 코드 부채가 상호 강화.
3. **동일 결함 반복 재발** — `_workspace/reports/2026-05-19-candle-12-30-stale-recurrence-investigation.md` 존재 자체가 증거("PR #535·#537·#540·#541·#558·#571 머지 완료 상태에서 동일 결함 재발"). 근본(진실원 다층) 미수정, 증상(stale) 반복 패치.

**문서 상태 불일치 실측**: ADR 0063·0091은 0118 §9가 사실상 deprecated 선언했으나 상태줄은 여전히 `Accepted`.

---

## §3. 벤치마킹 점수표

프로덕션 자동매매 시스템 기준, 10점 만점, 근거 기반.

| 영역 | 점수 | 근거 | 개선 포인트 |
|---|---|---|---|
| **데이터 정합성 (핵심)** | **3.5** | 진실원 6단, writer 8경로, DO NOTHING/UPDATE 혼재, 무가드 DO UPDATE, DELETE repair 위험 | writer 단일화 + source precedence 가드 (P0) |
| **차트 파이프라인 신뢰성** | **4.0** | 조회 시점 3중 병합, 프론트/백 KST 이중 계산, source 12종 미활용 | 조회 재조립 제거 + 경계 단일화 (P1) |
| **전략 평가 정확성** | **5.0** | 가격 소스 혼합(P1-④) raw 통일했으나 ADJUSTED 미해결, DSL/패턴 평가는 견고 | 지표+DSL 소스 모드 공유 (P2) |
| **차트 기반 매매 목표 달성** | **2.5** | 추세선 매매 default OFF 미작동, 청산 전량 %, 원래 목표 크게 후퇴 | 추세선 canary 활성 + tick 보간 (P2) |
| **리스크/주문 안전성** | **7.5** | 6룰 게이트, fail-closed, 멱등, KillSwitch, 다층 방어 (강점) | 유지, 게이트 로직 파일 분해 |
| **방어 로직 견고성** | **7.0** | OHLC sanity·0원 가드·미래분 복원 등 다층 (많음 자체가 오염 증거) | 근원 수정 후 방어층 단순화 |
| **아키텍처 복잡도 관리** | **3.0** | ADR 68% 캔들 얽힘, 2천+ LOC 파일 3개, 동일 결함 반복 재발 | 문서/코드 부채 정리 (P2) |
| **문서화/추적성** | **8.0** | ADR 136건·baseline·backlog·감사 추적 — 추적성 최상급 (강점) | 폐기 정책만 보완 |
| **테스트 커버리지** | **7.5** | canary/regression/incident 테스트 방대 | 유지 |
| **운영 관측성** | **6.5** | Micrometer·Slack·DLT·health check·stale watchdog | reconciliation stale 처리 보강 |

### 종합: **5.2 / 10**

**해석**: 리스크 안전성·문서화·테스트는 상용 수준(7~8점)이나, 데이터 정합성·아키텍처 복잡도(3점대)가 전체를 끌어내림. 안전장치는 훌륭하나 그 안전장치가 딛고 선 데이터 지반이 흔들리는 구조. 방어 로직이 유난히 많은 것이 역설적으로 근원 미수정의 증거.

### 개선 포인트 Top 3 (ROI 순)

1. **캔들 writer 단일화 + source precedence 가드 (P0)** — 데이터 정합성 3.5 → 7점대. 오염·조회 불일치·반복 재발을 동시 차단. 최고 ROI.
2. **가격 소스 모드 공유로 혼합비교 완전 제거 (P2)** — 전략 정확성 5.0 → 8점대. 차트 매매 복원의 전제.
3. **조회 재조립 제거 + KST 경계 단일화 (P1)** — 차트 신뢰성 4.0 → 7점대. 화면 차트와 자동매매 데이터 일치.

---

## §4. 근거 인용 인벤토리 (재현 가능성)

직접 read 확인:
- `consumer/.../market/TickToMinuteAggregator.kt` — RENAME 스냅샷·OHLC sanity·0원 가드·미래분 복원
- `consumer/.../market/LiveCandleUpdater.kt` — 13 interval tick 갱신·seeded/acmlBase/wsVolDelta·NXT 예상체결 TODO
- `consumer/.../aggregation/BoundaryAggregator.kt` — 1m fetch 합산·in-process 멱등 가드
- `api/.../service/CandleQueryService.kt` — 3중 병합·same-ts replace
- `frontend/.../Symbol/LightweightChart.tsx` — KST_OFFSET_MS·barBoundaryTimestamp 재계산
- `strategy-engine/.../JvmStrategyEvaluator.kt` — directCandleAccess adj/raw 혼합 주석
- ADR 0105·0057 원문, policies/kis-first-raw-fetch.md, audit-remediation-tracker.md, candle-data-policy-v2.md

서브에이전트 grep/read 실측(3 트랙): reconcile/backfill(source 12종·경로 8개·DO NOTHING vs UPDATE)·전략 %후퇴(ScaleInExitCalculator·PositionStopLossTickWatcher·ADR 0106/0111/0137/0140)·ADR 얽힘(94/136·거대파일 LOC/ADR 인용 수).

## §5. 변경 이력

- 2026-09-17: 신설 — 차트/가격 데이터 정합성 정밀 감사. §1 인과 사슬(6단계)·§2 문서 얽힘 정량·§3 벤치마킹 5.2/10·§4 근거 인벤토리 LOCK. 후속 조치 플랜은 [chart-data-integrity-remediation.md](../../docs/architecture/policies/chart-data-integrity-remediation.md).
