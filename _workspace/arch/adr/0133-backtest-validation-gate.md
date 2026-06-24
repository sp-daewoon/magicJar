# ADR 0133 — 백테스트 분봉 backfill cross-process 통합(api 자급) + 전략 ACTIVE 검증 게이트

- 상태: **Accepted** (2026-06-24)
- 작성: architect
- 관련: ADR 0132(초단타 정합 백테스트 — INTRADAY_REALISTIC·분봉 on-demand backfill·정합성 가드) · ADR 0131(autopilot 일일 universe 재생성) · ADR 0130(Planned Entry + lot 소유권) · ADR 0129(매매행위 하드닝) · ADR 0117/0121(발굴 레이어·사이징 정합·과최적화) · ADR 0056(백테스트 엔진) · ADR 0004(JVM 모듈 경계 — domain port / api·consumer 어댑터) · ADR 0037/0100/0116(KIS rate limit priority bucket) · ADR 0093/0118/0120(캔들 fetch·롤업)
- spec: `_workspace/specs/2026-06-24-backtest-validation-gate.md` + plan `_workspace/specs/2026-06-24-backtest-validation-gate-plan.md`
- 롤백 앵커: v3.14.x (ADR 0131/0132 와 동일 베이스라인 — 두 결정 모두 게이트 default OFF)
- 자본 경로: 간접 최고위험 (#5는 "어떤 전략이 REAL ACTIVE 가 되는가"의 판정 규칙). 백테스트 자체는 주문 발행 0.
- 코드 0 (본 ADR은 설계만 · 구현은 spec/plan 라운드별 task)

---

## 0. 한 줄 요약

> ADR 0132 의 INTRADAY_REALISTIC 백테스트는 **분봉 선수집 어댑터(`BacktestCandlePrepareAdapter`)가 consumer 프로세스에**, **백테스트 엔진(`JvmBacktestEngine`)이 api 프로세스에** 있어, api 가 consumer 빈을 주입받지 못한다(`@Autowired(required=false)` → null → backfill 0 → DB 에 분봉이 있는 1종목만 백테스트 가능). 다종목 검증이 막힌다. **(#4)** 본 ADR 은 **backfill 능력을 api 자급(in-process)으로 통합**한다 — api 의 `CandleRefreshService` 가 **이미** KIS 분봉(FHKST03010230) fetch + upsert + 합산봉 재생성 + `KisCallPriority.BACKFILL` 분기를 보유하므로, 신규 KIS 어댑터 0 으로 port 구현을 api 에 둔다(대안 C 채택). **(#5)** 그 위에서 **autopilot 후보 종목/전략을 ACTIVE 승격 전 INTRADAY_REALISTIC 백테스트 합격 의무**로 묶는 **검증 게이트**를 둔다 — 기존 "성공한 백테스트 있어야 ACTIVE"(`existsAutoActivationReadyRun`) 규율을 autopilot 자동 경로가 동일하게(더 엄격하게) 따르게 하고, 매일 universe 재생성(ADR 0131) 과 "후보 backfill→백테스트→통과분만 ACTIVE" 로 연결한다. **#4 가 #5 의 선결**이다.

---

## 1. 배경 — orchestrator 라이브 실증 (2026-06-24)

### 1-A. #4 결함 evidence (cross-process 단절)

ADR 0132 R2a(분봉 backfill 어댑터)·R2b(엔진 모드 분기)가 머지됐다. 그러나 **두 책임이 다른 JVM 프로세스에 배치**됐다:

| 책임 | 클래스 | 모듈/프로세스 |
|---|---|---|
| 백테스트 엔진(INTRADAY_REALISTIC 오케스트레이션) | `JvmBacktestEngine` | **api** |
| 분봉 선수집 + 정합성 보고(`BacktestCandlePreparePort` 구현) | `BacktestCandlePrepareAdapter` | **consumer** |

`JvmBacktestEngine` 은 port 를 `@Autowired(required = false) candlePreparePort: BacktestCandlePreparePort?` 로 선택 주입한다(R2a/R2b 병렬 머지 LOCK 의도). **api 프로세스에는 그 port 의 구현 빈이 없다**(구현이 consumer 에만 존재). 따라서 라이브 api 에서 `candlePreparePort == null` → `runIntradayRealistic` 가 backfill 0 으로 진행 → DB 에 이미 분봉이 있는 종목(예 000650)만 백테스트되고, **인트라데이 이력이 없는 universe 대부분은 INSUFFICIENT_DATA**로 빠진다.

> 결과: 다종목 INTRADAY_REALISTIC 백테스트가 **구조적으로 불가**. ADR 0132 가 의도한 "분봉 없으면 백테스트하며 수집"이 라이브에서 발화하지 않는다.

근본은 헥사고날 port 의 **구현 모듈 선택 오류**다(인터페이스는 domain·옳음 / 구현이 호출자와 다른 프로세스). Kafka·REST 같은 프로세스 간 채널을 두지 않는 한 api 엔진은 consumer 빈을 in-process 로 부를 수 없다.

### 1-B. #5 결함 evidence (검증 없는 자동 승격 risk)

ADR 0131 의 `AutopilotUniverseRefreshWorker`(08:35)는 매일 발굴 TOP N 으로 owner=AUTOPILOT 전략의 `strategy.codes` 를 갈아끼운다. 그런데 **그 새 종목들이 "이 전략 룰로 굴렸을 때 통과하는 종목인지" 검증이 없다.** 발굴(scorecard)은 "오늘 단타 적합도"를 매기지만, 그것이 **이 전략의 진입/청산 룰로 실제 기대값 양수인지**는 별개다. 기존 시스템은 사용자 전략에 대해 "성공한 백테스트가 있어야 ACTIVE"(`StrategyService.activationBlockedReason` → `existsAutoActivationReadyRun`) 게이트를 두고 있는데, **autopilot 자동 경로는 이 규율 밖**에 있다.

ADR 0132 §4 가 명시했다: *"본 ADR(parity 백테스트)은 전략 ACTIVE 승격 전 검증 게이트의 선결 인프라다. 게이트 판정 규칙은 후속 ADR로 분리한다."* — **본 ADR 이 그 후속이다.**

### 1-C. 사용자(최고 관리자) 의도 정합

- MEMORY `project_autopilot_initiative` — "검증→승격" 루프. 손실 시 풀 기준 하드칭이 백테스트 검증에 의존.
- MEMORY `feedback_verification_strict` — "단위테스트≠라이브". 백테스트 parity(ADR 0132) + 합격 게이트(본 ADR)가 자율 자본경로의 진실 토대.
- MEMORY `feedback_no_mock_data` — 데이터 부족은 mock 으로 메우지 않고 등급/skip 으로 노출.

---

## 2. 기존 자원 audit (헌법 §spec audit · MEMORY `feedback_spec_audit_existing_resources` 의무)

> 검증 시점 main(b96cf40e) 기준. 5 자원(Flyway·Kafka·Redis·STOMP·REST·ENV) 점유 audit.

### 2-A. ★ 결정적 발견 — api 는 이미 KIS 분봉 backfill 능력 보유 (대안 C 의 근거)

| 자원 | 위치(모듈) | 본 ADR 활용 |
|---|---|---|
| **`CandleRefreshService.refreshOneMinCandles` / `refreshOneMinCandlesInRange`** | **api** `service/CandleRefreshService.kt` | ★ KIS 1m(FHKST03010230) fetch + upsert(ON CONFLICT) — **이미 api 에 존재**. backfill 능력이 api 에 자급된다. |
| **`CandleRefreshService.rebuildAggregateIntervals`** | **api** | ★ 1m → 3m/5m/15m/30m/90m/1h/4h/12h **합산봉 재생성**(`CandleAggregator`) — 이미 api 에 존재. consumer `AggregateBackfillRunner` 와 동일 결과. |
| **`KisCallPriority.BACKFILL` 분기** | **api** `CandleRefreshService` | ★ 이미 priority 전파 보유(사용자=USER_PATH / 백그라운드=BACKFILL). rate limit 보호가 api 에 이미 구현. |
| **`KisRateLimiterPort.acquire` + EGW00201 backoff + 페이지 sleep** | **api** | KIS 초당 한도·EGW00201 1초 backoff·페이지 150ms 마진 — 이미 api 경로에 구현(2026-05-22 종합 fix). |
| **`CandleIntegrityChecker`** | consumer `backtest/` | 의존이 **domain 만**(`CandleRepository` port + `CandleIntegrityReport`/`CandleConfidence` 값객체 + `CandleInterval`). Spring·KIS·Kafka 의존 0 → **api 로 이동 또는 공유 모듈 추출이 무손실**(헥사고날 정합). |
| `BacktestCandlePreparePort` | domain `backtest/port/` | 인터페이스는 옳은 위치(domain). **구현 모듈만 교체**. |
| `JvmBacktestEngine` (`runIntradayRealistic`·`candlePreparePort`) | api | port 를 in-process 빈으로 주입받게 된다(required=false → 정상 주입). 엔진 로직 변경 0. |
| `BacktestRunJpaRepository.existsAutoActivationReadyRun(strategyId, userId)` | api `persistence/backtest/` | ★ **#5 기존 게이트** — "성공한 백테스트 run 존재" 판정. autopilot 경로가 동일/확장 규칙으로 재사용. |
| `StrategyService.activationBlockedReason` | api `service/strategy/` | ★ **#5 게이트 단일 출처** — 사용자 ACTIVE 전이가 이미 이 함수를 통과. autopilot 자동 승격이 동일 함수를 통과하게 묶음. |
| `AutopilotUniverseRefreshWorker` / `AutopilotUniverseRefreshService` | api `scheduler/`·`service/autopilot/` | ★ **#5 hook 지점** — 08:35 universe 재생성. 본 ADR 이 그 흐름에 "backfill→백테스트→통과분만 승격" 단계를 add. |
| `IntradayRealisticBacktestProperties` (`magicjar.backtest.intraday-realistic.*`) | api `config/` + application.yml:186 | ENV 게이트 이미 존재(enabled·backfill-enabled·max-lookback-days). 본 ADR 은 검증 게이트 ENV 만 add. |
| `discovery_scorecard`·`DiscoveryScorecardJpaRepository` | api(V77) | autopilot universe 후보 출처(read). |
| candle 테이블 | DB | OHLC·volume·source. Flyway 신규 0. |

### 2-B. 신규 자원 (최소)

| 종류 | 신규 | 사유 |
|---|---|---|
| Flyway | **0~1** | 검증 게이트 판정 결과를 영속하면 `autopilot_universe_validation`(snapshot_date·strategy_id·code·verdict·backtest_run_id) **V96 add-only** 1건이 audit·재현에 유용. **1차 권장: 응답/로그+SystemEvent 로 충분 → V96 미발행** (ADR 0131 과 동일 보수). 최신 = V95(`V95__strategy_position_trade_plan.sql`) → 다음 가용 V96. |
| 도메인 | (#5) `AutopilotActivationVerdict`·`BacktestPassCriteria` 값 객체 추가 | 합격 기준·판정 결과. |
| ENV | (#5) `magicjar.autopilot.activation-gate.{enabled,min-trades,min-win-rate,min-expectancy,max-mdd,require-confidence,lookback-days,interval}` | 검증 게이트 임계치. default OFF. |
| Kafka/Redis/STOMP | **0** | 백테스트·검증은 동기 in-process 경로. backfill 도 in-process port 직호출. |
| KIS 어댑터 | **0** | ★ api `CandleRefreshService` 가 이미 1m fetch + 합산 재생성 보유 — 대안 C 의 핵심 절약. |

### 2-C. 점유 충돌 검증

| 자원 | 신규 점유 | main 충돌 | 결론 |
|---|---|---|---|
| Flyway | V96(미발행 권장) | 최신 V95 | 충돌 0 |
| ENV | `magicjar.autopilot.activation-gate.*` add-only | autopilot.* 내 미점유 | 충돌 0 |
| Bean(api) | `BacktestCandlePreparePort` 구현 1개(api) | consumer 에 동명 구현 존재 → **삭제 또는 비활성**(중복 빈 충돌 회피 — §3-A2) | 처리 후 충돌 0 |
| REST/Kafka/STOMP | 0 | — | 신규 0 |

---

## 3. 결정

본 ADR 은 두 결정을 묶는다. **#4(통합) 가 #5(게이트) 의 데이터/엔진 선결**이므로 라운드도 #4 → #5 순서 LOCK.

---

### #4 — 백테스트 분봉 backfill cross-process 통합

#### 3-A. 대안 비교

| 대안 | 내용 | 채택? | 사유 |
|---|---|---|---|
| **A. 백테스트 엔진을 consumer 로 이동/복제** | `JvmBacktestEngine` 을 consumer 로 옮겨 backfill 과 co-locate | ❌ | 백테스트는 **사용자 동기 REST**(`/api/backtest/run`) 진입 — api 컨트롤러·`BacktestRunJpaRepository`·`StrategyRepositoryPort`·세금/슬리피지/MTM 모델 등 백테스트 생태계 전체가 api 에 있다. 엔진만 consumer 로 옮기면 REST→consumer 프로세스 간 호출(Kafka/내부 REST)이 필요해 동기 UX 가 비동기 폴링으로 붕괴(ADR 0132 §5-D 가 이미 비동기 backfill 거부). 모듈 경계(ADR 0004 — api=REST 어댑터) 역행. |
| **B. 2-step: api 가 consumer 에 backfill 요청(Kafka cmd / 내부 REST) → 준비 후 백테스트** | api→consumer backfill 명령, 완료 대기 후 엔진 실행 | ❌ | 프로세스 간 채널 신설(Kafka 토픽 또는 내부 REST) + 완료 신호 대기(폴링/콜백) + 부분 실패 복구 → **동기 REST 백테스트에 비동기 분산 복잡도**. consumer 다운 시 백테스트 전면 불가(가용성 결합). ADR 0132 §5-D "동기 선수집이 결과 일관성·단순성 우위" 정신 역행. mock 금지 가드와 별개로 운영 부채 큼. |
| **C. (채택) backfill port 구현을 api in-process 로 — 기존 api KIS 분봉 능력 재사용** | `BacktestCandlePrepareAdapter`(+`CandleIntegrityChecker`) 를 api 로 이동(또는 공유 모듈), api 의 `CandleRefreshService` 1m fetch + 합산 재생성 + `BACKFILL` priority 를 재사용 | ✅ | **api 가 이미 KIS 1m fetch + upsert + 합산봉 재생성 + BACKFILL priority 를 보유**(§2-A 실측) → 신규 KIS 어댑터 0. port 가 호출자(엔진)와 같은 프로세스 → `@Autowired(required=false)` 가 정상 주입. 동기 REST UX 보존. 프로세스 간 채널 0. 모듈 경계 정합(api=REST+외부 어댑터 — ADR 0004). |

**채택 = C.** 근거 종합:
1. **이미 있는 능력 재사용** — api `CandleRefreshService` 가 FHKST03010230(1m) fetch·upsert·합산 재생성·`KisCallPriority.BACKFILL`·EGW00201 backoff 를 전부 보유. consumer adapter 는 동일 능력의 **위치만 잘못된 중복**이었다.
2. **KIS rate limit 안전** — api 경로도 `KisRateLimiterPort.acquire(BACKFILL)` 를 타므로 라이브 매매(USER_PATH)·warmup(REALTIME_MARKET) 쿼터 침범 0. 단일 rate limiter(Redis token bucket)는 프로세스 무관 전역 공유이므로 api↔consumer 어느 쪽이 fetch 하든 전역 한도는 동일하게 보호된다(채널 이동이 한도를 늘리지 않음).
3. **모듈 경계(ADR 0004)** — 백테스트 오케스트레이션·REST 는 api 책임. KIS fetch 어댑터는 "api 가 외부 어댑터를 보유"하는 기존 정합(`CandleRefreshService` 자체가 api 의 KIS 어댑터). consumer 전용이 아니다.
4. **운영 단순성** — 프로세스 간 채널 0, 가용성 결합 0, 동기 결과 일관성 보존.

#### 3-A1. 구현 형태 — port 구현의 api 이전 (신규 능력 0)

INTRADAY_REALISTIC backfill 의 본질은 "(게이트 ON 시) universe×interval×[from,to] 의 분봉 gap 을 KIS 1m BACKFILL 로 채우고 합산 재생성한 뒤, 종목별 정합성 보고를 반환"이다. api 는 이 4단계를 모두 수행할 부품을 이미 가진다:

| 단계 | api 기존 부품 |
|---|---|
| gap 산출(DB 분봉 범위 확인) | `CandleJpaRepository`(api) + `CandleIntegrityChecker`(domain 의존만 → api 이동) |
| KIS 1m fetch(BACKFILL) | `CandleRefreshService.refreshOneMinCandlesInRange(code, ["1m"], BACKFILL, range)` |
| 합산봉 재생성 | `CandleRefreshService.rebuildAggregateIntervals(code, targets)` |
| lookback clamp(KIS 1년 ∧ ENV) | `IntradayRealisticBacktestProperties.max-lookback-days` (이미 api config) |
| 정합성 보고 | `CandleIntegrityChecker.check(...)` (domain 의존만) |

→ **`BacktestCandlePreparePort` 의 api 구현(`BacktestCandlePrepareApiAdapter`)** 은 위 부품을 조립하는 **얇은 어댑터**다. 신규 KIS 호출 코드 0. `prepareAndReport` 시그니처(ADR 0132 R1 LOCK)는 **불변** — 엔진 호출부 변경 0.

> backend-core/market-data 협의: `CandleRefreshService` 의 1m fetch + 합산 재생성 경로를 backfill 어댑터가 호출하기 좋게 **internal entrypoint** 가 필요하면 add-only public 메서드 1개 노출(기존 `refresh`/`recollect` 와 별개 — backfill 전용·`BACKFILL` priority 고정). 기존 메서드 시그니처 변경 0.

#### 3-A2. consumer 중복 구현 처리 (빈 충돌 회피 LOCK)

api 에 `BacktestCandlePreparePort` 구현 빈이 생기면, **consumer 의 `BacktestCandlePrepareAdapter` 는 그대로 두면 무해**하다(consumer 프로세스에서 백테스트 엔진을 부르지 않으므로 dead bean). 그러나 혼선·유지 부채 회피를 위해:

- **권장: consumer `BacktestCandlePrepareAdapter`·`CandleIntegrityChecker`(consumer 복제분) 삭제** + `CandleIntegrityChecker` 를 domain 의존만 유지한 채 api 로 이동(또는 indicator-impl/공유 모듈 추출은 over-engineering — api 단독 사용이므로 api 로 이동이 최소). consumer `BacktestIntradayProperties`·`KisMinuteBackfillRangePort`(consumer adapter 가 쓰던) 의 다른 사용처 여부 확인 후 정리(다른 사용처 있으면 유지).
- **단일 빈 LOCK**: 같은 프로세스(api) 에 동일 port 구현이 2개면 Spring `NoUniqueBeanDefinitionException`. api 에는 구현 **정확히 1개**(`BacktestCandlePrepareApiAdapter`)만 존재하도록 보장. consumer 구현은 다른 프로세스라 충돌 안 하나, 코드 위생상 삭제 권장.

#### 3-A3. KIS rate limit·운영 (자본경로 보호)

- backfill 은 **반드시 `KisCallPriority.BACKFILL`**(최저). 라이브 매매·warmup·ingestion 이 항상 우선(전역 Redis token bucket 공유).
- **장중(09:00~15:30) 보수 페이싱**: 다종목 1m backfill 은 호출 폭증. ADR 0132 §D2-3 정합 — 장중에는 백테스트 backfill 페이싱을 더 보수적으로(또는 사용자 경고). 검증 게이트(#5)의 일일 자동 backfill 은 **개장 전(08:35 universe refresh 직후)** 에 수행해 장중 라이브 매매와 시간 분리(§3-B3).
- **lookback bound**: KIS 1m 보관 최대 1년 ∧ ENV `max-lookback-days`(default 30 거래일) 로 clamp(거짓 결과 차단). 종목당 fetch 폭증 제어.

---

### #5 — 전략 ACTIVE 검증 게이트 (autopilot)

#### 3-B. 결정 — autopilot 승격을 INTRADAY_REALISTIC 백테스트 합격에 묶음

owner=AUTOPILOT 전략(또는 그 universe 후보 종목)을 **ACTIVE 로 승격(자본 노출)하기 전**, 그 전략 룰로 INTRADAY_REALISTIC 백테스트(ADR 0132)를 돌려 **합격 기준을 통과한 경우에만 ACTIVE 를 유지/승격**한다. 기존 사용자 게이트(`existsAutoActivationReadyRun`)와 동일 정신을, autopilot 자동 경로에 **자동·반복**으로 적용한다.

#### 3-B1. 합격 기준 (`BacktestPassCriteria`) — 단타 특성·과적합 고려

ADR 0132 의 `INTRADAY_REALISTIC` 결과 + `CandleIntegrityReport` 를 입력으로 다음 모두를 만족해야 **PASS**:

| 기준 | default 임계치(ENV 조정) | 근거 |
|---|---|---|
| **표본(최소 거래수)** | `min-trades` ≥ **20** | 단타는 거래가 잦으나 표본 부족 시 승률이 우연. 20 미만이면 `INSUFFICIENT_SAMPLE`(통계적 무의미 — PASS 불가). |
| **승률** | `min-win-rate` ≥ **0.40** (40%) | 단타는 손익비(R:R)로 승부 — 고승률 요구는 과적합 유인. TP(+2~3%)/SL(−2%) 손익비를 감안해 40% 보수. **단독 기준 아님**(기대값과 AND). |
| **기대값(expectancy)** | `min-expectancy` > **0** (1 거래당 순기대 수익 > 0, 수수료·세금·슬리피지 차감 후) | ★ 핵심 — 승률이 낮아도 손익비가 좋으면 양수 가능. ADR 0132 가 비용 모델(세금/수수료/슬리피지)을 적용하므로 **net expectancy** 가 진짜 알파. |
| **MDD** | `max-mdd` ≤ **0.15** (15%, MTM equity 기준 — ADR 0056 §2-7 amend) | 단타 전략이 큰 drawdown 을 내면 자본 안전 위협. MTM 곡선(미실현 포함)으로 측정. |
| **정합성(confidence)** | `require-confidence` = **OK** (LOW_CONFIDENCE 도 허용 옵션) | ★ ADR 0132 정합 — `confidence < OK`(결측률 높음)이면 **결과 자체가 신뢰 불가** → PASS 불가(또는 LOW_CONFIDENCE 는 사용자 명시 시만 통과). INSUFFICIENT_DATA 는 항상 PASS 불가. mock 으로 메우지 않은 정직한 데이터 위에서만 판정. |

- **AND 결합**: 전부 만족해야 PASS. 하나라도 미달이면 FAIL + 미달 사유 코드(`INSUFFICIENT_SAMPLE`·`LOW_WIN_RATE`·`NEGATIVE_EXPECTANCY`·`MDD_EXCEEDED`·`LOW_CONFIDENCE`·`INSUFFICIENT_DATA`) 동봉.
- **과적합 가드(단타 특성)**: 짧은 윈도우·과최적화 risk(ADR 0117) 를 감안해 **lookback-days(default 30 거래일)** 의 단일 in-sample 결과로 판정하되, 1차는 단순·결정적 우선. **OOS(out-of-sample) 분할·walk-forward 는 post-MVP(§7)** — scorecard·백테스트 데이터가 누적된 뒤. 1차는 "net expectancy > 0 + 표본 ≥ 20 + MDD ≤ 15% + confidence OK" 의 보수적 AND 로 거짓 양성을 막는다.
- **임계치 출처 단일화**: 모든 임계치는 `magicjar.autopilot.activation-gate.*` ENV 단일 출처. drift 0.

#### 3-B2. 판정 적용 지점 — "전략 단위" 게이트 (종목 단위 X · 1차)

| 후보 | 내용 | 채택? |
|---|---|---|
| **전략 단위(채택)** | owner=AUTOPILOT 전략을 그 전략의 현재 universe(codes) 로 INTRADAY_REALISTIC 백테스트 → 전략 종합 결과가 합격 기준 통과 시 ACTIVE 유지, FAIL 시 BUY_BLOCKED/PAUSE | ✅ — 기존 게이트(`existsAutoActivationReadyRun` 는 전략 단위)와 동형. ADR 0131 universe 가 "전 전략 동일 deduped TOP N"(전략 단위 universe)이라 정합. 운영 단순. |
| 종목 단위 | universe 후보 종목 each 를 전략 룰로 백테스트 → 통과 종목만 codes 에 넣음 | ❌(1차) — 종목×전략 폭증(N×M 백테스트)·codes 필터 복잡. ADR 0131 의 "전 전략 동일 universe" 모델과 충돌. post-MVP §7. |

**채택 = 전략 단위.** autopilot 전략은 universe(codes) 전체에 대한 종합 백테스트 결과로 PASS/FAIL 판정. (종목별 alpha 필터는 발굴 scorecard 가 이미 1차 필터 역할.)

#### 3-B3. universe 재생성(ADR 0131) 과의 연계 — "backfill→백테스트→통과분만 ACTIVE"

ADR 0131 의 08:35 `AutopilotUniverseRefreshWorker` 흐름에 검증 단계를 **append**한다(게이트 ON 시):

```
08:30  DiscoveryScorecardScheduler.runSnapshot           (기존 ADR 0117/0121)
08:35  AutopilotUniverseRefreshService                    (기존 ADR 0131)
         step1  deduped TOP N 합성 → owner=AUTOPILOT codes UPDATE
         step2  ★(본 ADR #5) 검증 게이트 ON 시:
                 for each owner=AUTOPILOT 평가 전략:
                   a. BacktestCandlePreparePort.prepareAndReport(codes, interval, lookback)   ← #4 (api in-process)
                   b. JvmBacktestEngine.run(INTRADAY_REALISTIC, 그 전략·codes·lookback)
                   c. BacktestPassCriteria 판정 → PASS/FAIL(+사유)
                   d. PASS  → status 유지 ACTIVE (+ backtest_run 영속 = existsAutoActivationReadyRun 충족)
                      FAIL  → status BUY_BLOCKED (매수 억제 · 보유 lot 은 plan/안전망 청산 — hold-until-exit)
09:00  개장
09:01  ActiveStrategyIntradayWarmupRunner                 (기존)
```

- **a 단계(backfill)는 개장 전(08:35~09:00)** 에 수행 → 장중 라이브 매매와 KIS 호출 시간 분리(rate limit 안전). lookback 30 거래일 × N 종목 × ~5 call = 종목당 ~150 call 을 `BACKFILL` priority 로 페이싱.
- **FAIL 처리 = BUY_BLOCKED(보수)**: ACTIVE→강제 PAUSE 가 아니라 **BUY_BLOCKED**(매수만 억제·기존 status). 보유 lot 은 ADR 0131 hold-until-exit 정합(plan/안전망 청산). 검증 실패가 보유분 강제 청산을 유발하지 않음(자본 안전).
- **멱등**: 같은 영업일 1회만(ADR 0131 의 `AtomicReference<LocalDate>` 가드 패턴 공유). 같은 codes→같은 backfill→같은 백테스트→같은 판정(결정적).

#### 3-B4. 기존 사용자 게이트와의 정합 (drift 0)

- 사용자 전략 ACTIVE 전이는 현행 `StrategyService.activationBlockedReason` → `existsAutoActivationReadyRun` 그대로(**byte-동일·zero-regression**). 본 ADR 은 **autopilot 자동 경로에만** 검증 게이트를 add.
- autopilot PASS 시 `backtest_run` 을 영속하면 `existsAutoActivationReadyRun` 도 자동 충족 → **두 게이트가 하나의 진실(성공한 백테스트 run)로 수렴**. 별도 판정 저장소 불요(V96 미발행 권장 근거).
- 즉 autopilot 은 "성공한 백테스트 있어야 ACTIVE" 규율을 **매일 자동으로 재검증**해 따른다 — 사용자 전략은 1회 수동, autopilot 은 일일 자동. 동일 규율·다른 트리거.

#### 3-B5. 게이트 default · 롤백 · owner 격리

- **ENV 게이트 `magicjar.autopilot.activation-gate.enabled` default `false`(보수 LOCK)**. ADR 0131/0132 와 동일 — REAL 자동매매 자본 경로에 영향을 주는 변경은 명시 승인 전까지 비활성. off 면 universe refresh 가 검증 단계 skip(ADR 0131 거동 byte-동일).
- **#4(통합)는 게이트와 무관하게 항상 적용**(엔진이 port 를 in-process 로 주입받는 인프라 수정). 단 INTRADAY_REALISTIC 자체는 `magicjar.backtest.intraday-realistic.enabled`(ADR 0132·default false) 게이트 종속 — off 면 LEGACY 강등이라 backfill 도 발화 0. 즉 #4 의 가시적 효과도 ADR 0132 게이트 ON 이 전제.
- **owner 격리(zero-regression LOCK)**: 검증 게이트는 **owner='AUTOPILOT' 전략만**. USER/SYSTEM 전략은 현행 수동 게이트 그대로(touch 0).
- **롤백**: `activation-gate.enabled=false` 1줄 → 검증 단계 no-op = ADR 0131 거동 복귀. #4 인프라는 잔존(port in-process 주입은 무해 — INTRADAY_REALISTIC 게이트 off 면 호출 자체 0). 롤백 앵커 v3.14.x.
- **fail-safe**: 검증 중 백테스트 자체가 에러/INSUFFICIENT_DATA 면 → **보수적으로 FAIL(BUY_BLOCKED)** 처리(거짓 PASS 로 미검증 종목을 ACTIVE 두지 않음) + WARN SystemEvent. backfill 전량 실패(KIS 장애)면 universe refresh 의 codes 갱신은 ADR 0131 fail-safe(기존 universe 유지)와 결합 — 검증도 skip 하지 않고 FAIL 측으로(자본 보수). silent 진행 0(헌법 §11-2).

---

## 4. 모듈 경계 (ADR 0004 정합)

| 모듈 | 책임 | 본 ADR 변경 |
|---|---|---|
| **domain**(순수) | `BacktestCandlePreparePort`(기존·불변)·`BacktestPassCriteria`·`AutopilotActivationVerdict` 값객체 add-only. `CandleIntegrityReport`/`CandleConfidence`(기존). | port 인터페이스 변경 0. |
| **api**(REST+외부 어댑터+백테스트 오케스트레이션) | ★ `BacktestCandlePrepareApiAdapter`(port 구현 — `CandleRefreshService` 1m fetch+합산 재생성+`BACKFILL` 재사용) · `CandleIntegrityChecker`(consumer→api 이동, domain 의존만) · `JvmBacktestEngine`(port in-process 주입·로직 불변) · `StrategyService`/`AutopilotUniverseRefreshService` 검증 게이트 hook · `BacktestPassCriteria` 평가 · ENV. | #4·#5 주력. |
| **consumer** | `BacktestCandlePrepareAdapter`·복제 `CandleIntegrityChecker` **삭제(정리)** — 다른 사용처 확인 후. KisRestCandleAdapter 의 `fetchOneMinuteCandlesForBackfill` 가 consumer 다른 경로(SymbolDetailMinuteGapFill 등)에서 쓰이면 유지. | dead bean 정리. |
| **frontend**(선택·R3) | autopilot status 화면에 "오늘 universe 검증 결과(PASS/FAIL·사유·backtest confidence)" 노출(관측 가시화 — mock 금지 정직 노출). | 후속. |

> 모듈 경계 핵심: 백테스트·backfill 오케스트레이션·KIS fetch 어댑터는 **모두 api**(api 가 이미 KIS 어댑터 보유 — `CandleRefreshService`). consumer 는 실시간 WS·Kafka 소비 전용으로, 백테스트 동기 경로와 분리. 청산 두뇌·plan 구성은 strategy-engine 순수(ADR 0132 — 본 ADR 변경 0).

---

## 5. 대안 종합 (요약)

- **#4**: A(엔진→consumer 이동) ❌ 동기 REST·api 생태계 역행 / B(2-step Kafka·REST) ❌ 분산 복잡·가용성 결합 / **C(api in-process·기존 KIS 능력 재사용) ✅**.
- **#5 합격기준**: 단일 승률 게이트 ❌(과적합·고승률 유인) / **net expectancy>0 + 표본≥20 + MDD≤15% + confidence OK 의 보수 AND ✅** / OOS·walk-forward (post-MVP §7).
- **#5 적용 단위**: 종목 단위 ❌(N×M 폭증·ADR 0131 모델 충돌) / **전략 단위 ✅**(기존 게이트 동형).
- **#5 FAIL 처리**: ACTIVE→강제 청산 ❌(비계획 청산·자본 위험) / **BUY_BLOCKED(매수 억제·hold-until-exit) ✅**.

---

## 6. 검증 (정직 evidence — 할루시네이션 금지 · MEMORY `feedback_verification_strict`)

단위 테스트로 끝내지 않는다. 게이트 ON 후 MOCK→canary 에서:

### #4 (통합)
1. api 프로세스에서 `candlePreparePort != null`(in-process 주입) 확인 — INTRADAY_REALISTIC 백테스트 시 backfill 발화 로그(`BacktestCandlePrepare 시작 ...`).
2. 인트라데이 이력 **없던** 다종목(universe 16종) 백테스트 → KIS 1m BACKFILL fetch + 합산 재생성 + 정합성 보고가 종목별로 채워지는지(000650 외 종목도 결과 생성) DB·로그 evidence.
3. `KisCallPriority.BACKFILL` 로 발행돼 라이브 매매(USER_PATH)·warmup 쿼터 침범 0 — rate limiter 메트릭(BACKFILL 버킷만 소모) 확인. EGW00201 시 backoff 확인.
4. lookback clamp(요청 from 이 30 거래일 초과 과거면 clamp + `LOOKBACK_CLAMPED` 경고) 확인.
5. consumer dead bean 정리 후 api/consumer 모두 정상 부팅(빈 충돌 0·다른 사용처 회귀 0).

### #5 (검증 게이트)
6. 08:35 universe refresh → 검증 게이트 ON 시 owner=AUTOPILOT 전략 each INTRADAY_REALISTIC 백테스트 발화 로그 + `BacktestPassCriteria` 판정(PASS/FAIL·사유) SystemEvent.
7. PASS 전략 → ACTIVE 유지 + `backtest_run` 영속 → `existsAutoActivationReadyRun` 충족 확인. FAIL 전략 → BUY_BLOCKED 전이 + **보유 lot 강제 청산 안 됨**(hold-until-exit) 확인.
8. **owner 격리**: USER/SYSTEM 전략 status·게이트 무변경(byte-동일) 확인.
9. INSUFFICIENT_DATA/백테스트 에러 시 보수적 FAIL(BUY_BLOCKED) + WARN — 거짓 PASS 0.
10. 게이트 off 1줄 → 검증 단계 no-op = ADR 0131 거동 byte-동일 복귀(롤백 실증).
11. **KIS 진실원 크로스체크(MEMORY `feedback_kis_ground_truth_crosscheck`)**: 검증 통과 후 실제 라이브 거래가 백테스트가 예측한 TP/SL/EOD 거동과 일치하는지(parity 사후 검증 — ADR 0132 §8 연결).

- **SLO 영향(헌법 §9-1)**: 백테스트·검증은 동기 REST·개장 전 배치. autopilot 시그널→주문 경로 무변경. backfill 은 BACKFILL 버킷으로 라이브 쿼터 보호. account API·Outbox 무영향.
- **rollback plan(헌법 §9-2)**: `activation-gate.enabled=false` 1줄. 즉시.
- **관측 매트릭스(헌법 §9-3)**: 검증 SystemEvent(INFO PASS·WARN FAIL/INSUFFICIENT) + backfill BACKFILL 버킷 메트릭 + autopilot 일일 손익 KIS 대조.

---

## 7. post-MVP (후속 트랙 후보)

1. **OOS / walk-forward 검증** — in-sample/out-of-sample 분할로 과적합 직접 측정(ADR 0117 정합). 백테스트 데이터 누적 후.
2. **종목 단위 게이트** — universe 후보 종목 each 를 전략 룰로 검증해 통과 종목만 codes 에 편입(종목별 alpha 필터). N×M 백테스트 비용 감당 가능해진 뒤.
3. **프리셋별 동적 임계치** — scorecard forward-test hit rate 연동 합격 기준 자동 조정(ADR 0131 §7 정합).
4. **`autopilot_universe_validation` 테이블(V96~)** — 일자별 전략 검증 판정 이력 audit·재현(1차는 backtest_run + SystemEvent 로 충분).
5. **비동기 대용량 backfill admin 경로** — 1년 전수 backfill 은 별도 admin 비동기(ADR 0132 §5-D post-MVP).

---

## 8. 라운드 경계 (구현 plan 요약 — 상세는 spec/plan)

| 라운드 | 책임 에이전트 | 내용 | 선결 |
|---|---|---|---|
| **R1** | domain | `BacktestPassCriteria`·`AutopilotActivationVerdict` 값객체 + `BacktestPassResult`(verdict+사유코드) add-only. `BacktestCandlePreparePort` 불변 확인. | — |
| **R2a (#4 핵심·선결)** | backend-core + market-data | `BacktestCandlePrepareApiAdapter`(api · `CandleRefreshService` 1m fetch+합산 재생성+`BACKFILL` 재사용) + `CandleIntegrityChecker` consumer→api 이동(domain 의존만) + `JvmBacktestEngine` in-process 주입 검증 + consumer dead bean 정리(다른 사용처 확인) + `CandleRefreshService` backfill entrypoint(필요 시 add-only public 1개) | R1 |
| **R2b (#5)** | strategy-engine + backend-core | `BacktestPassCriteria` 평가기(api `service/autopilot/`) + `AutopilotUniverseRefreshService` 검증 단계 append(backfill→INTRADAY_REALISTIC 백테스트→판정→PASS=ACTIVE 유지+backtest_run 영속 / FAIL=BUY_BLOCKED) + `StrategyService.activationBlockedReason` 정합 확인 + ENV 8건 + 멱등·fail-safe·owner 격리 | **R2a 선결** |
| **R3** | strategy-engine + qa | 테스트(합격기준 경계·격리·멱등·fail-safe·lookback clamp·빈 주입) + MOCK 라이브 검증(§6 evidence 일괄) + (선택) frontend autopilot status 검증 결과 배지 | R2a·R2b |

---

## 9. 롤백

- **#5**: `magicjar.autopilot.activation-gate.enabled=false` 1줄 → universe refresh 검증 단계 no-op = ADR 0131 거동 byte-동일.
- **#4**: INTRADAY_REALISTIC 게이트(`magicjar.backtest.intraday-realistic.enabled`·ADR 0132·default false) off 면 backfill 호출 0(LEGACY 강등). in-process port 주입은 잔존하나 호출 0 으로 무해.
- 스키마 변경 0(V96 미발행 권장) → down SQL 불요. 롤백 앵커 v3.14.x.
