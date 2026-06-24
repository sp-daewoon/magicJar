# spec — 백테스트 backfill cross-process 통합(api 자급) + 전략 ACTIVE 검증 게이트

- 권위 ADR: `_workspace/arch/adr/0133-backtest-validation-gate.md`
- 관련: ADR 0132(INTRADAY_REALISTIC·분봉 backfill·정합성) · ADR 0131(autopilot universe 재생성) · ADR 0130/0129(plan·하드닝) · ADR 0056(엔진) · ADR 0004(모듈 경계)
- 게이트 default: **OFF** (#5 `activation-gate.enabled=false` · #4 효과는 ADR 0132 `intraday-realistic.enabled` 종속)
- 자본 경로: #5 간접 최고위험(어떤 전략이 REAL ACTIVE 가 되는가). 백테스트 주문 발행 0.
- 코드 0(본 문서는 설계) · 구현은 plan 라운드별 task · 배포는 orchestrator

---

## 1. 문제 (orchestrator 실증 2026-06-24)

### #4 — backfill cross-process 단절
- `JvmBacktestEngine`(**api**) 가 `BacktestCandlePreparePort` 를 `@Autowired(required=false)` 로 주입하는데, 구현 `BacktestCandlePrepareAdapter` 는 **consumer 에만** 존재 → api 프로세스에서 빈 null → INTRADAY_REALISTIC 백테스트가 backfill 0 → 분봉 보유 1종목(000650)만 검증 가능, universe 다종목 INSUFFICIENT_DATA.
- 근본: 헥사고날 port 구현이 **호출자와 다른 프로세스**(인터페이스 위치는 옳음·구현 모듈만 오류).

### #5 — 검증 없는 자동 승격
- ADR 0131 의 08:35 universe refresh 가 매일 codes 를 갈아끼우지만, **새 종목이 그 전략 룰로 통과하는지 검증 없음**. 기존 사용자 게이트("성공한 백테스트 있어야 ACTIVE" — `existsAutoActivationReadyRun`)를 autopilot 자동 경로가 안 따름.

---

## 2. 결정 요약 (ADR 0133 §3)

### #4 = 대안 C (api in-process · 기존 KIS 능력 재사용)
- api `CandleRefreshService` 가 **이미** KIS 1m(FHKST03010230) fetch + upsert + 합산봉 재생성 + `KisCallPriority.BACKFILL` 분기를 보유(§audit 실측).
- → `BacktestCandlePreparePort` 의 **api 구현**(`BacktestCandlePrepareApiAdapter`) 을 둔다 — 위 부품 조립하는 얇은 어댑터. 신규 KIS 호출 코드 0.
- `CandleIntegrityChecker`(domain 의존만) consumer→api 이동. consumer dead bean 정리.
- port 시그니처(`prepareAndReport`) **불변** → 엔진 호출부 변경 0. `@Autowired(required=false)` 가 정상 in-process 주입.

### #5 = 전략 단위 검증 게이트
- owner=AUTOPILOT 전략을 ACTIVE 승격 전 INTRADAY_REALISTIC 백테스트 → `BacktestPassCriteria` 통과 시만 ACTIVE 유지.
- **합격 기준(AND)**: `min-trades≥20` · `win-rate≥0.40` · **`expectancy>0`(net)** · `MDD≤0.15`(MTM) · `confidence=OK`(LOW_CONFIDENCE 옵션).
- universe 재생성(08:35) 흐름에 append: `codes UPDATE → backfill → 백테스트 → PASS=ACTIVE 유지+backtest_run 영속 / FAIL=BUY_BLOCKED(hold-until-exit)`.
- 기존 게이트와 정합: PASS 시 `backtest_run` 영속 → `existsAutoActivationReadyRun` 자동 충족(두 게이트 단일 진실 수렴).

---

## 3. 자원 (ADR 0133 §2)

### 재사용 (LOCK — 신규 생성 금지)
| 자원 | 모듈 | 용도 |
|---|---|---|
| `CandleRefreshService.refreshOneMinCandlesInRange`·`rebuildAggregateIntervals` | api | #4 KIS 1m fetch + 합산 재생성 |
| `KisCallPriority.BACKFILL`·`KisRateLimiterPort` | api(전역 Redis bucket) | rate limit 보호(라이브 쿼터 침범 0) |
| `CandleIntegrityChecker` | consumer→**api 이동** | 정합성 보고(domain 의존만) |
| `BacktestCandlePreparePort` | domain | 인터페이스 불변 |
| `JvmBacktestEngine`(`runIntradayRealistic`) | api | port in-process 주입(로직 불변) |
| `BacktestRunJpaRepository.existsAutoActivationReadyRun` | api | #5 기존 게이트 |
| `StrategyService.activationBlockedReason` | api | #5 게이트 단일 출처 |
| `AutopilotUniverseRefreshService`/`Worker` | api | #5 hook(08:35) |
| `IntradayRealisticBacktestProperties` | api+yml:186 | ENV 게이트(기존) |

### 신규 (최소)
| 자원 | 종류 | 비고 |
|---|---|---|
| `BacktestCandlePrepareApiAdapter` | api/backtest | port 의 api 구현(얇은 조립) |
| `BacktestPassCriteria`·`AutopilotActivationVerdict`·`BacktestPassResult` | domain 값객체 | #5 합격 기준·판정 |
| `AutopilotActivationGateService`(가칭) | api/service/autopilot | 백테스트 결과 → PASS/FAIL 평가 |
| `magicjar.autopilot.activation-gate.{enabled,min-trades,min-win-rate,min-expectancy,max-mdd,require-confidence,lookback-days,interval}` | ENV/yml | default OFF + 임계치 |
| Flyway V96 | (미발행 권장) | backtest_run + SystemEvent 로 충분 |

### 충돌 검증
- Flyway 최신 V95 → V96 미발행 권장(충돌 0).
- api 동일 port 구현 **정확히 1개**(`BacktestCandlePrepareApiAdapter`) — 빈 충돌 회피 LOCK. consumer 구현 삭제(다른 프로세스라 충돌 안 하나 위생상).
- ENV `autopilot.activation-gate.*` add-only(미점유).
- KIS 어댑터·Kafka·STOMP 신규 0.

---

## 4. 안전 게이트 (헌법 §9)

- **#5 default OFF** — owner=AUTOPILOT 한정 · USER/SYSTEM zero-regression.
- **FAIL=BUY_BLOCKED**(강제 청산 X · hold-until-exit — ADR 0131 정합).
- **fail-safe**: 백테스트 에러/INSUFFICIENT_DATA → 보수적 FAIL(거짓 PASS 0) + WARN SystemEvent. silent 진행 0.
- **rate limit**: backfill `BACKFILL` 버킷 + 개장 전(08:35~09:00) 수행으로 장중 라이브 분리 + lookback clamp(KIS 1년 ∧ ENV 30 거래일).
- **롤백**: `activation-gate.enabled=false` 1줄(no-op = ADR 0131 거동). #4 인프라는 INTRADAY_REALISTIC off 시 호출 0 으로 무해.
- **mock 금지**: 결측 봉 보간 0(ADR 0132 §D3-2). confidence < OK 면 PASS 불가.

---

## 5. 검증 의무 (ADR 0133 §6 — MOCK→canary evidence)

- #4: api `candlePreparePort != null` + 다종목 backfill 발화 + BACKFILL 버킷만 소모 + lookback clamp + consumer 정리 후 양쪽 부팅.
- #5: 08:35 검증 발화 + PASS=ACTIVE+backtest_run 영속 / FAIL=BUY_BLOCKED + 보유 lot 미청산 + USER/SYSTEM 격리 + INSUFFICIENT_DATA→보수 FAIL + 롤백 no-op + KIS 진실원 parity 사후 대조.

---

## 6. 라운드 (상세는 plan)

| R | 에이전트 | 내용 | 선결 |
|---|---|---|---|
| R1 | domain | 값객체 add-only | — |
| **R2a(#4)** | backend-core+market-data | api port 구현·CandleIntegrityChecker 이동·엔진 주입·consumer 정리 | R1 |
| R2b(#5) | strategy-engine+backend-core | 합격기준 평가·universe refresh append·ENV·격리·fail-safe | **R2a** |
| R3 | strategy-engine+qa | 테스트+MOCK 검증(+frontend 배지 선택) | R2a·R2b |
