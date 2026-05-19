# strategy-engine 시스템 점검 보고서 (2026-05-19)

> **작성**: strategy-engine sub-agent (magicjar-strategy, opus 4.7 1M)
> **task**: T-20260519-1648-worktree-agent-a2926806777d7c9cd
> **브랜치**: agent/strategy/system-audit-2026-05-19
> **base sha**: 2a2225d (main, v3.6.16 마감)
> **영역 경계**: strategy-engine 단독 — 지표·DSL·백테스트·주문 로직·매매일지. backend-core(영속)·market-data(KIS)·chart-curator(캔들 정합성) 침범 0.
> **목적**: ADR 0083(indicator-impl) + ADR 0084(주문 path P0·P1·P2) + ADR 0082(T-MAN) baseline 마감 시점(v3.6.16) 정합 검증 + hotfix 의제 발굴.

---

## 0. Executive Summary

v3.6.16 마감 시점 strategy-engine 영역은 자동매매 본체 path를 완성도 있게 갖추고 있습니다. ADR 0083 indicator-impl 모듈 분리 정합, ADR 0084 P0 4건(hashkey·PENDING_RECONCILE·WS race·DLQ) 마감, ADR 0082 T-MAN B-2-extended(`OrderSource` AUTO/MANUAL 이중 정합) 합류 모두 코드 레벨 evidence로 확인했습니다.

다만 **외부 코드 리뷰 P1 3건(#2 outbox · #8 STOMP 영속 · #9 MDC traceId)** + **ADR 0084 P2 2건(#6 정정 · #10 MarketSession+토큰 mutex)** 후속 잔류, 그리고 strategy-engine 영역 자체 내부 정합 hotfix 3건이 확인되었습니다. 자본 잠재 risk는 0건(P0 모두 마감) — P1·P2는 운영 회고·관측성 보강 의제로 W4 진입 전 일괄 또는 단독 patch cut 후보입니다.

---

## 1. 지표 라이브러리 (indicator-impl 모듈)

### 1-1. 구조 (ADR 0083 Accepted 2026-05-19 정합)

| 항목 | evidence | 상태 |
|---|---|---|
| 모듈 신설 | `settings.gradle.kts:14-21` — `indicator-impl` include LOCK | ✅ |
| 의존 방향 | `api/build.gradle.kts:14` + `consumer/build.gradle.kts:14` 모두 `implementation(project(":indicator-impl"))` | ✅ |
| 도메인 순수성 | `indicator-impl` → `domain`만 (ADR 0004 §의존 방향 정합) | ✅ |
| 컨테이너 수 불변 | api·batch·consumer 3개 유지 (라이브러리 jar) | ✅ |

### 1-2. 지표 81개 정합 매트릭스 (KIS 80 + Native 5, 중복 5종 `ma·ema·rsi·macd·bb` Native 우선)

| 분류 | 개수 | 파일 | line | KIS raw 인용 |
|---|---|---|---|---|
| TREND | 20 | `IndicatorTrend.kt` | 622 | `indicators.py` L15~L1469 |
| MOMENTUM | 20 | `IndicatorMomentum.kt` | 706 | `indicators.py` L282~L1283 |
| VOLUME | 12 | `IndicatorVolume.kt` | 334 | `indicators.py` |
| VOLATILITY | 12 | `IndicatorVolatility.kt` | 291 | `indicators.py` |
| OSCILLATOR | 8 | `IndicatorOscillator.kt` | 301 | `indicators.py` |
| OTHER | 8 | `IndicatorOther.kt` | 200 | `indicators.py` |
| **KIS 합계** | **80** | 6 파일 | 2,454 | — |
| Native | 5 | `Native{Ma,Ema,Rsi,Macd,BollingerBands}.kt` + `IndicatorMath.kt` | 392 | KIS 1:1 정합 drift<0.001% |

### 1-3. 소스 우선순위 (ADR 0021 §3-4 정합)

- `NativeIndicatorSource.priority = 50` → `KisStrategyBuilderSource.priority = 100` (낮은 값 우선)
- 중복 5종(`ma·ema·rsi·macd·bb`)은 Native 우선 — KIS는 cross-check fixture(`_workspace/strategy/cross-check/kis-indicator-fixture.json`)로만 검증
- 사용자 노출 endpoint(`GET /api/indicators?name=...`) 80지표 전체 200 응답 가능 (ADR 0083 dogfooding evidence 마감)

### 1-4. 카탈로그 정합 (KIS raw 줄번호 의무)

`_workspace/strategy/indicator-catalog.md` 1507 line 카탈로그가 80지표 정확히 정합:
- `ma`(L15) · `rsi`(L282) · `macd`(L312+L339+L365 multi-output) · `supertrend`(L872 — period=10 KIS default) · `regression_slope`(L1469)
- **헌법 §3 KIS-first** raw 줄번호 인용 의무 충족 — strategy-engine 자체 추론 0

### 1-5. 발견 hotfix 의제 (P3 — 정합성 점검)

- (없음) — 81지표 catalog·소스·테스트 정합 LOCK

---

## 2. DSL 조건식 (KIS Strategy Builder DSL 1:1 port)

### 2-1. AST 구조 (`domain/strategy/dsl/` 13 파일)

| Kotlin | KIS raw (parser.py) | 비고 |
|---|---|---|
| `DslNode` (sealed root) | — | Kotlin sealed |
| `IndicatorRef` | `Indicator` | rename 회피 (ADR 0021 도메인 충돌) |
| `LiteralValue` | `Value` | rename (Kotlin 키워드 충돌) |
| `Condition` | `Condition` L251 | THRESHOLD·CROSSOVER·BREAKOUT·PATTERN 4 type |
| `CompositeCondition` | `CompositeCondition` | AND/OR/NOT |
| `ArithmeticExpr` | `ArithmeticExpr` | +/-/*///%  |
| `Operator` · `ArithmeticOperator` · `LogicalOperator` · `ConditionType` | enum | KIS 매핑 1:1 |
| `DslStrategyDefinition` | `StrategyDefinition` | rename (Kotlin Definition 충돌) |

### 2-2. 평가기 (`JvmStrategyEvaluator`)

- ADR 0022 + ADR 0047 sandbox LOCK 정합
- **timeout**: 실시간 100ms / 백테스트 1000ms (`magicjar.dsl.eval.timeout-ms` · `magicjar.dsl.backtest-timeout-ms`)
- **CPU budget**: 50ms (`ThreadMXBean.getThreadCpuTime`)
- **auto-disable**: 1회 timeout 즉시 + CPU budget 누적 5회 위반 시
- **graceful HOLD**: 캔들 부족 시 `insufficient_data`

### 2-3. 패턴 통합 (ADR 0054 pattern-engine 모듈 정합)

- `PatternRecognizer` domain port → `JvmPatternRecognizer` (`pattern-engine` 모듈) `@Component("patternRecognizer")` 빈
- **57 pattern detectors** 확인 (`pattern-engine/src/main/.../detector/*Detector.kt` 57건) — ADR 0025 §M.2 57패턴 카탈로그 1:1 정합
- consumer + api 양쪽 모듈이 `implementation(":pattern-engine")` — DSL `consecutive(...)` PATTERN type 분기 정합

### 2-4. YAML parser (`KisYamlDslParser`)

- KIS `parser.py` YAML → AST 변환 1:1 port
- fixture 테스트(`KisDslParserFixtureTest`) 통과

### 2-5. 발견 hotfix 의제 (P3 — 문서·SoT)

- **DSL-1 (P3, doc-only)**: `_workspace/strategy/dsl-spec.md` 파일 부재 — strategy-engine 에이전트 charter `출력` 섹션에 명시되어 있으나 SoT 부재. v3.7.0 W4 진입 전 발행 권장 (1 PR · architect + strategy 영역 add-only).

---

## 3. 백테스트 엔진 (`JvmBacktestEngine`)

### 3-1. 흐름 (ADR 0056 §2-1 LOCK 정합)

| step | 책임 | LOCK |
|---|---|---|
| 1. 전략 조회 | `StrategyRepositoryPort.findById` | `BacktestException.StrategyNotFoundException` |
| 2. 모델 lookup | `BacktestModelRegistry.{tax,commission,slippage}Model` | `KisDirectCommissionModel` + `KoreanStockTaxModel` + `FixedRateSlippageModel` 등 |
| 3. 캔들 전체 조회 1회 | `CandleJpaRepository.findByCodeAndIntervalAndTsBetween` | 매 t마다 재조회 절대 금지 (성능 보호) |
| 4. 시뮬레이션 초기화 | `cash·trades·positions·equityCurve` | — |
| 5. universe 순회 + t 슬라이스 | `LookaheadGuard.safeSlice(allCandles, t)` | 룩어헤드 금지 LOCK |
| 6. BUY/SELL 평가 | `BacktestEvaluatorBridge.evaluateBacktest()` (timeout 1000ms) | 직접 walkRoot 호출 0 |
| 7. timeout 누적 | `timeoutCount++`, quota 50 시 abort | `BacktestResult.Aborted(reason="TIMEOUT_QUOTA_EXCEEDED")` |
| 8. 미종료 포지션 강제 청산 | 마지막 캔들 가격 + `trigger="FORCE_CLOSE_END_OF_BACKTEST"` | — |
| 9. 성과 계산 | `BacktestPerformanceCalculator.calculate` | MDD·Sharpe·승률·평균 손익비 |

### 3-2. 안전장치 5건

- ✅ **결정론 보장**: 시드 0 — 매 t 슬라이스 평가 deterministic
- ✅ **mock 합성 0** (`feedback_no_mock_data` 정합): 캔들 0건 시 `BacktestResult.Aborted(reason="NO_CANDLE_DATA")` 반환 + `log.warn`
- ✅ **룩어헤드 가드**: `LookaheadGuard.safeSlice(allCandles, t)` — t 이후 캔들 차단
- ✅ **abort quota**: timeout 50회 누적 시 즉시 중단 (CPU budget 보호)
- ✅ **수수료·세금·슬리피지 모델**: KIS direct 0.014952% + 한국 주식 SELL 0.18% + Fixed rate 슬리피지

### 3-3. 한계 (현행 1차 LOCK)

- **SizingPolicy.AllCapital 단일** — 분할매수·피라미딩 미지원 (post-MVP)
- **1 universe × 1 종목 순차** — 병렬 시뮬레이션 미지원
- **포지션 중복 불가** — 보유 중 BUY 신호 무시
- **KOSPI 가정** — KOSDAQ 세금 차이 미반영 (DEFAULT_MARKET="KOSPI")
- **영업일 근사** — 주말 제외만, KRX 공휴일 미반영 (`MarketCalendar` 테이블 연동 post-MVP)

### 3-4. 발견 hotfix 의제

- **BT-1 (P2 — 운영 회고)**: KRX 공휴일 미반영 영업일 계산이 Sharpe·CAGR 산식 약 ~5% 오차 가능. T-MS1·ADR 0013 MarketSessionFsm 본격 구현 후 합류 가능 — 단독 hotfix 가치 낮음, W4 T-AI baseline 합류 후보.
- **BT-2 (P3 — doc-only)**: 차터에 명시된 `_workspace/strategy/backtest-report-schema.json` 부재. `BacktestResult.Success`/`Aborted` JSON 직렬화 스키마 발행 권장 — frontend 결과 UI 호환성 보강.

---

## 4. 주문 로직 — OrderRouterKafkaListener 12 step 매트릭스

### 4-1. ADR 0068 §14-2 LOCK 정합 evidence

| step | 책임 | 코드 위치 | LOCK |
|---|---|---|---|
| 1. payload deserialize | `deserialize()` | L104~L116, L565~L619 | poison pill skip + ADR 0082 `source` 필드 (default AUTO) |
| 2. 멱등성 가드 | `OrderIdempotencyGuard.acquire(idemKey, 60)` | L130~L152 | Redis SETNX 60s |
| 3. TradingModeGate 3중 게이트 | `tradingModeGate.resolveMode(userId)` | L154~L162 | MAGICJAR_MODE + MAGICJAR_ALLOW_REAL + RealTradingConsent (ADR 0068 §14-2) |
| 4. KillSwitch 재검증 | `killSwitchQueryPortProvider.getIfAvailable()?.isOn` | L164~L180 | fallback false + log.warn |
| 5. order_request INSERT (REQUESTED) | `persistRequested(intent, mode)` | L182~L207 | `PersistResult.{NotWired,SaveFailed,Ok}` sealed 분기 |
| 6. order.requested.v1 publish | `orderRequestedKafkaProducer.publish(saved)` | L209~L210 | — |
| 7. KIS REST submit (5xx 1회 retry) | `submitWithRetry(intent, mode)` | L212~L214, L460~L526 | ADR 0084 P0-2 PENDING_RECONCILE 분기 LOCK |
| 8. order_request UPDATE (ACK or REJECTED or PENDING_RECONCILE) | `updateKisAck` 또는 `updateStatus` | L288~L297, L233~L254 | timeout exception chain 검사 (reactor wrapped 포함) |
| 9. audit KIS_ACK / KIS_REJECT / KIS_EXCEPTION / KIS_RECONCILE_PENDING | `writeAudit(...)` | L299~L310, L256~L269 | `OrderAuditEventType` enum + payloadJson `source` 마커 |
| 10. (FILLED 통보는 별도 listener) | `KisExecutionWsListener` (consumer/ws) | — | strategy-engine 영역 외 |
| 11. Kafka commit | `ack.acknowledge()` | L323 | MANUAL_IMMEDIATE |
| 12. Micrometer counter | — | (ObjectProvider 미합류 시 graceful) | TODO §78-12 — backlog 잔류 |

### 4-2. ADR 0084 P0 4건 마감 evidence

| P0 의제 | strategy-engine 책임 | 코드 위치 | 상태 |
|---|---|---|---|
| #3 PENDING_RECONCILE | `submitWithRetry` 5xx/timeout/wrapped-timeout → `DenyReason.KisTimeoutPendingReconcile` | L451~L526 | ✅ 마감 |
| #3 PENDING_RECONCILE worker | `OrderReconciliationWorker.reconcileTick` 1분 cron + grace 5분 + EXPIRY 15분 | `consumer/order/OrderReconciliationWorker.kt` | ✅ 마감 |
| #7 DLQ | `OrderDltMonitorListener` + `KafkaErrorHandlerConfig` | `consumer/order/OrderDltMonitorListener.kt` | ✅ 마감 (backend-core 영역 — strategy 영역 외) |
| #11 hashkey | `KisHashKeyAdapter` + `KisOrderHttpAdapter` | (market-data 영역) | ✅ 마감 (영역 외) |
| #4 WS race + backfill | `KisExecutionWsListener` event_id UQ + backfill | (market-data 영역) | ✅ 마감 (영역 외) |

### 4-3. ADR 0082 T-MAN B-2-extended 통합 evidence

- `OrderIntent.source: OrderSource` 1급 시민화 (deserialize default AUTO, backward compat)
- step 1 직후 + ACK + REJECTED + IDEMPOTENCY_SKIPPED 모든 audit에 `source` JSON 마커 + DB 컬럼 이중 정합
- AUTO/MANUAL 운영자 audit log L119~L127 + L312~L320 + L439~L445
- Phase 1c task `T-20260519-0139-t-man-phase1c-consumer-source` 마감 정합

### 4-4. 자본 보호 LOCK 정합

| 보호 | evidence | 상태 |
|---|---|---|
| `enable.auto.commit=false` | application.yml ack-mode=MANUAL_IMMEDIATE | ✅ |
| `max.poll.records=1` | application.yml | ✅ |
| `concurrency=1 per partition` | application.yml | ✅ |
| `isolation.level=read_committed` | application.yml | ✅ |
| persistRequested 실패 시 listener 중단 | L185~L207 `NotWired·SaveFailed` 모두 `ack.acknowledge()` 후 return | ✅ |
| unexpected 예외 ack 보류 | L324~L334 outer catch에서 ack 미호출 → Kafka 재처리 | ✅ |

### 4-5. 발견 hotfix 의제

- **OR-1 (P1)**: ADR 0084 P0 4건 마감 후 P1 3건(#2 outbox · #8 STOMP 영속 · #9 MDC traceId) 잔류. backlog §78-2·§78-8·§78-9. v3.6.17 patch cut 후보. strategy-engine 영역은 #2 일부(`OrderRequestedKafkaProducer·OrderRejectedKafkaProducer` outbox 패턴 적용) 협력 가능.
- **OR-2 (P2)**: ADR 0084 P2 2건(#6 정정 — RVSE_CNCL_DVSN_CD="01" · #10 MarketSession+토큰 mutex). W4 직전 묶음 cut 후보.
- **OR-3 (P3 — observability)**: step 12 Micrometer counter `magicjar_order_processed_total{result=ACK|REJECTED|SKIPPED|PENDING_RECONCILE}` 미합류. ObjectProvider 패턴으로 graceful skip 가능 — backlog §78-12 등록 권장.

---

## 5. 매매일지 (T-J) 정합

### 5-1. Phase 1 마감 evidence (ADR 0072 §9-2 + CHANGELOG 2026-05-14)

| 산출물 | 위치 | 상태 |
|---|---|---|
| Flyway V29 `trading_journal` + `journal_note` + 멱등성 UQ | `api/src/main/resources/db/migration/V29__trading_journal_and_note.sql` | ✅ |
| 도메인 entity | `domain/journal/{TradingJournalEntry,JournalNote,JournalEventType}.kt` | ✅ |
| domain port | `domain/journal/port/TradingJournalRepository.kt` (abstract LOCK) | ✅ |
| JPA adapter (UQ 충돌 skip) | `api/persistence/journal/TradingJournalRepositoryAdapter.kt` L35~L55 DataIntegrityViolation catch | ✅ |
| `JournalEventConsumer` 5 토픽 listen | `api/messaging/journal/JournalEventConsumer.kt` 5건 `@KafkaListener` | ✅ |
| `JournalSummaryComposer` 한국어 합성 | `api/messaging/journal/JournalSummaryComposer.kt` 5 분기 | ✅ |
| REST 3 endpoint | `api/controller/journal/TradingJournalController.kt` (GET entries · GET entry · POST notes) | ✅ |
| frontend `/journal` 페이지 | `frontend/src/pages/TradingJournalPage.tsx` + features/journal/ | ✅ (Phase 3 마감) |

### 5-2. 5 Kafka 이벤트 listen 매트릭스 (`JournalEventConsumer`)

| 토픽 | JournalEventType | source_table | 멱등 UQ |
|---|---|---|---|
| `order.filled.v1` | `ORDER_FILLED` | `order_execution` | (sourceTable, sourceId, eventType) |
| `order.rejected.v1` | `ORDER_REJECTED` | `order_request` | 〃 |
| `order.cancelled.v1` | `ORDER_CANCELLED` | `order_request` | 〃 |
| `signal.generated.v1` | `SIGNAL_GENERATED` | `signal` | 〃 |
| `risk.violation.v1` | `RISK_VIOLATION` | `risk_event` | 〃 |

`ux_trading_journal_source` UQ 위반 시 `TradingJournalRepositoryAdapter.saveEntry` null 반환 → `JournalEventConsumer` log.warn + skip. Kafka at-least-once 안전.

### 5-3. 발견 hotfix 의제

- **TJ-1 (P3 — doc)**: `chart_snapshot` 테이블이 strategy charter (T-J §)에 명시되어 있으나 V29 SQL에 부재. ADR 0072 §9-2는 `journal_note` 1:N만 정의 — `chart_snapshot` backlog §60 잔류. W4 진입 후 LWC v4 차트 캡쳐 path 합류 후 발행 가능.
- **TJ-2 (P3 — observability)**: `realizedPnl` 계산이 `order.filled.v1` payload에서 직접 가져오는데, 일부 케이스(분할 체결)에서 누적 합산 누락 가능성. backend-core 영역 — strategy-engine 단독 의제 아님.

---

## 6. 신호 생성 (RealtimeSignalGenerator)

### 6-1. ADR 0066 §7-1·§7-2 정합

- `StrategyLookupPort.findActiveByCodeAndInterval(code, interval)` → `JvmStrategyEvaluator.evaluate` → `SignalDedupeGuard.tryAcquire` (Redis SETNX 30s) → DB INSERT + Kafka publish
- **HOLD 영속 0** (ADR 0066 §9-2 — noise 회피)
- `BoundaryClosingKafkaListener` 5건 (5m·15m·30m·90m·1h) trigger
- Phase 2 의존(`StrategyLookupPort` · `SignalRepository`)은 `ObjectProvider.ifAvailable` graceful no-op

### 6-2. 발견 hotfix 의제

- (없음) — 본 영역은 W3 T-SIG 마감 후 안정 baseline 운영 중

---

## 7. ADR cross-ref 매트릭스 (v3.6.16 마감 시점)

| ADR | 상태 | strategy-engine 영향 | hotfix risk |
|---|---|---|---|
| 0021 IndicatorSource 추상화 | Accepted | ✅ 정합 | 0 |
| 0022 DSL evaluator | Accepted | ✅ 정합 | 0 |
| 0023 Signal Engine 룰 매칭 only (AI 호출 0) | Accepted | ✅ AI import 0 검증 — strategy 영역 grep 0건 | 0 |
| 0025 57패턴 카탈로그 | Accepted | ✅ 57 detector 정합 | 0 |
| 0047 DSL sandbox | Accepted | ✅ timeout·CPU budget·auto-disable LOCK | 0 |
| 0054 pattern-engine 모듈 | Accepted | ✅ 정합 | 0 |
| 0056 백테스트 아키텍처 | Accepted | ✅ JvmBacktestEngine LOCK 정합 | 0 |
| 0066 T-SIG | Accepted | ✅ RealtimeSignalGenerator 정합 | 0 |
| 0067 T-RISK | Accepted | ✅ RiskGatewayEvaluator + RiskViolationKafkaProducer | 0 |
| 0068 T-O | Accepted | ✅ OrderRouterKafkaListener 12 step LOCK | 0 |
| 0072 T-J + T-PERF | Accepted | ✅ JournalEventConsumer 5건 마감 | 0 |
| 0082 T-MAN B-2-extended | Accepted | ✅ OrderSource AUTO/MANUAL 이중 정합 마감 | 0 |
| 0083 indicator-impl 모듈 | Accepted | ✅ 6 모듈 매트릭스 마감 | 0 |
| 0084 주문 path 안정성 P0·P1·P2 | Accepted (P0 마감, P1·P2 잔류) | 🟡 P1 3건 + P2 2건 후속 | **OR-1·OR-2** |

---

## 8. hotfix 의제 종합 (우선순위 매트릭스)

### P0 (자본 잠재 risk — 즉시)
- **(없음)** — ADR 0084 P0 4건 모두 v3.6.16 마감 evidence 확인 완료

### P1 (단기 — 정합성·관측성, v3.6.17 patch cut 후보)
| ID | 의제 | 영역 | 추정 사이즈 |
|---|---|---|---|
| OR-1 (#2) | order outbox — 4 producer 통합 + Flyway V37 `order_event_outbox` | backend-core 主 + strategy 협력 (`OrderRequestedKafkaProducer·OrderRejectedKafkaProducer` 패턴 적용) | M |
| OR-1 (#8) | STOMP 영속 — Flyway V38 + frontend WebSocket hook + unread fetch | backend-core + frontend | M |
| OR-1 (#9) | MDC traceId — api·consumer Filter + Kafka header + logback | backend-core (영역 외 — strategy 협력 0) | S |

### P2 (중기 — UX·운영 편의, W4 직전 묶음 cut 후보)
| ID | 의제 | 영역 | 추정 사이즈 |
|---|---|---|---|
| OR-2 (#6) | 주문 정정 — RVSE_CNCL_DVSN_CD="01" | backend-core (controller) + strategy (`OrderRouterKafkaListener` 정정 분기) | M |
| OR-2 (#10) | MarketSession + 토큰 mutex 본격 구현 | market-data + backend-core | L |
| BT-1 | KRX 공휴일 영업일 계산 정합 (~5% Sharpe 오차 fix) | strategy + backend-core (MarketCalendar 테이블) | M |

### P3 (정합성·doc — 단독 cut 가치 낮음, 묶음 cycle 합류)
| ID | 의제 | 영역 | 추정 사이즈 |
|---|---|---|---|
| OR-3 | step 12 Micrometer counter `magicjar_order_processed_total` | strategy (`OrderRouterKafkaListener`) | XS |
| DSL-1 | `_workspace/strategy/dsl-spec.md` 발행 | strategy + architect | S |
| BT-2 | `_workspace/strategy/backtest-report-schema.json` 발행 | strategy + frontend | S |
| TJ-1 | `chart_snapshot` 테이블 (ADR 0072 §9-2 잔여, backlog §60) | backend-core + chart-curator (W4 LWC 합류 후) | L |

---

## 9. fitness gap 검증 (`feedback_v3_skepticism_no_regression` lens)

| 검증 항목 | 결과 |
|---|---|
| 자동매매에 필요한가? | ✅ 모든 의제 자동매매 본체 path (지표·DSL·백테스트·주문·매매일지) |
| v1/v2 잔재 아닌가? | ✅ v3 본체 path — v1/v2 잔재 0건 (PoC 코드는 v3.0.0에서 전면 정리됨) |
| MVP 범위 적합? | ✅ P0 마감 baseline 상태, P1·P2는 운영 회고·관측성 보강 의제 — REAL 진입 직전 묶음 cut 가능 |
| AI 호출 0 LOCK | ✅ strategy-engine 영역 LLM provider import 0 grep PASS (ADR 0023 §2-4) |
| KIS-first 정합 | ✅ 80 KIS 지표 `indicators.py` 줄번호 인용 의무 충족 (strategy 영역은 KIS 본체 호출 0 — market-data 위임) |
| 결정론 보장 | ✅ 백테스트 시드 0 deterministic, 동일 입력 → 동일 출력 |

---

## 10. 영역 침범 검증

- ✅ **backend-core 영역(영속) 침범 0**: JPA adapter·Service·Controller·Flyway 신규 추가 0 — 본 보고서는 .md only
- ✅ **market-data 영역(KIS) 침범 0**: KIS adapter·WS·REST 코드 검토만, 신규 호출 추가 0
- ✅ **chart-curator 영역(캔들 정합성) 침범 0**: 캔들 정책 변경 0
- ✅ **frontend 영역 침범 0**: React/TS 코드 변경 0

---

## 11. 산출물 매트릭스

| 산출물 | 위치 | 상태 |
|---|---|---|
| 본 보고서 | `_workspace/reports/2026-05-19-system-audit-strategy-engine.md` | ✅ |
| task 파일 | `_workspace/tasks/T-20260519-1648-worktree-agent-a2926806777d7c9cd.md` | ✅ |
| CHANGELOG row | `CHANGELOG.md` | ✅ (PR에 포함) |

---

## 12. 결론 + 권장 사이클

strategy-engine 영역은 v3.6.16 마감 시점 자동매매 본체 path를 완성도 있게 갖추고 있습니다. 자본 잠재 risk(P0) 0건, ADR 0083·0084·0082 모두 marked Accepted + 코드 evidence 정합. **다음 권장 사이클**:

1. **v3.6.17 patch (P1 묶음)** — OR-1 3건 (#2 outbox · #8 STOMP 영속 · #9 MDC traceId) backend-core 主도 + strategy 협력. 추정 ~3 PR.
2. **W4 진입 전 묶음 cut (P2)** — OR-2 + BT-1 (KRX 공휴일 정합) W4 baseline에서 합류 가능.
3. **단독 micro-fix** — OR-3 Micrometer counter는 strategy-engine 단독 XS PR 가능 (`OrderRouterKafkaListener` step 12 + ObjectProvider 패턴).

본 보고서는 evidence 인용 의무(`feedback_verification_strict`) 정합 — 모든 LOCK 검증은 코드 줄번호·CHANGELOG row·ADR 본문 인용 기반입니다.

---

**작성**: strategy-engine sub-agent (magicjar-strategy, opus 4.7 1M, 2026-05-19 KST)
**다음 액션**: 사용자/오케스트라 결정 위임 — P1 묶음 cut 진입 또는 W4 baseline 진입.
