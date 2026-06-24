# plan — 백테스트 backfill 통합(#4) + 전략 ACTIVE 검증 게이트(#5)

- 권위: ADR 0133 + spec `2026-06-24-backtest-validation-gate.md`
- 순서 LOCK: **R1 → R2a(#4 선결) → R2b(#5) → R3**. R2a 가 R2b 의 데이터/엔진 선결(통합 없이는 검증 백테스트가 backfill 0).
- 머지 정책: `--rebase` · PR `--base main` · 각 agent inline `-c user.email`(worktreeConfig race).
- 배포: orchestrator. 게이트 default OFF 라 머지 자체는 거동 무변(byte-동일).

---

## R1 — 도메인 값객체 (domain · 단일 PR · 테스트 0 OK)

**에이전트: domain (backend-core 모자)**

- [ ] `BacktestPassCriteria`(domain/backtest) — `minTrades:Int=20`·`minWinRate:Double=0.40`·`minExpectancy:BigDecimal=0`·`maxMdd:Double=0.15`·`requireConfidence:CandleConfidence=OK`. ENV 에서 주입될 불변 값객체.
- [ ] `AutopilotActivationVerdict` enum — `PASS`·`FAIL` + 사유코드 `ActivationFailReason`(`INSUFFICIENT_SAMPLE`·`LOW_WIN_RATE`·`NEGATIVE_EXPECTANCY`·`MDD_EXCEEDED`·`LOW_CONFIDENCE`·`INSUFFICIENT_DATA`·`BACKTEST_ERROR`).
- [ ] `BacktestPassResult`(verdict + 미달 사유 리스트 + 측정 메트릭 echo: trades·winRate·expectancy·mdd·confidence).
- [ ] `BacktestCandlePreparePort` 시그니처 **불변** 확인(주석 add only — "구현 모듈이 consumer→api 로 이동, 인터페이스 불변" ADR 0133 §3-A1).
- 산출: domain add-only. Spring·JPA import 0(헥사고날). PR `--base main`.

> 라운드 룰 3(R1 = 도메인+시그니처) 정합. 테스트는 R3 일괄.

---

## R2a — #4 backfill cross-process 통합 (api in-process · **R2b 선결**)

**에이전트: backend-core(주) + market-data(KIS fetch·정합성 협의)**

- [ ] `BacktestCandlePrepareApiAdapter`(api/service/backtest 또는 backtest/) — `BacktestCandlePreparePort` 구현. `prepareAndReport(universe, interval, from, to, backfillEnabled)`:
  - lookback clamp(`IntradayRealisticBacktestProperties.maxLookbackDays` ∧ KIS 1년) — consumer adapter 와 동일 로직 이식.
  - backfillEnabled 시 각 code: `CandleRefreshService` 의 1m BACKFILL fetch + 합산봉 재생성 호출(아래 entrypoint).
  - `CandleIntegrityChecker.check(...)` 로 종목별 보고 반환. fail-soft(종목 실패해도 계속·등급으로 노출·mock 0).
- [ ] `CandleRefreshService` backfill entrypoint(필요 시 add-only public 메서드 1개) — `BACKFILL` priority 고정 1m range fetch + 합산 재생성. 기존 `refresh`/`recollect`/`refreshRange` 시그니처 변경 0. (이미 `refreshOneMinCandlesInRange`·`rebuildAggregateIntervals` 가 private/internal 이면 어댑터가 호출 가능한 최소 진입점만 노출.)
- [ ] `CandleIntegrityChecker` consumer→**api 이동**(domain 의존만 — `CandleRepository`·`CandleIntegrityReport`·`CandleConfidence`·`CandleInterval`). api 의 `CandleRepository` 빈으로 주입.
- [ ] `JvmBacktestEngine` — `candlePreparePort` 가 api in-process 빈으로 **정상 주입**되는지 확인(엔진 로직 변경 0 · `@Autowired(required=false)` 유지로 부팅 안전).
- [ ] **consumer dead bean 정리**: `BacktestCandlePrepareAdapter`(consumer)·복제 `CandleIntegrityChecker`(consumer) 삭제. **단** `KisMinuteBackfillRangePort`/`fetchOneMinuteCandlesForBackfill`(consumer KisRestCandleAdapter)·`AggregateBackfillRunner`·`BacktestIntradayProperties`(consumer) 의 **다른 사용처 grep 확인 후** — 다른 사용처 있으면 유지, 없으면 backtest 전용분만 정리.
- [ ] **빈 단일성 LOCK**: api 에 `BacktestCandlePreparePort` 구현 **정확히 1개**. 부팅 시 `NoUniqueBeanDefinitionException` 0 확인.
- 산출: #4 통합. PR `--base main`. 테스트 0 OK(R3 일괄). 게이트 영향 0(INTRADAY_REALISTIC off 시 호출 0).

> 협업: market-data 가 KIS 1m fetch·정합성(기대봉수·등급) 정합 리뷰. backend-core 가 어댑터 조립·엔진 주입·consumer 정리 주도.

---

## R2b — #5 검증 게이트 (api · **R2a 선결**)

**에이전트: strategy-engine(합격기준·universe 연계) + backend-core(영속·게이트 hook)**

- [ ] `AutopilotActivationGateService`(api/service/autopilot) — `evaluate(strategy, backtestResult, integrityReports): BacktestPassResult`. `BacktestPassCriteria`(ENV 주입) AND 판정: trades≥min · winRate≥min · **expectancy>0(net)** · mdd≤max · confidence≥require. 미달 사유코드 수집.
- [ ] `AutopilotUniverseRefreshService` 검증 단계 **append**(게이트 ON 시): codes UPDATE(기존 ADR 0131) 후 owner=AUTOPILOT 평가 전략 each:
  - a. `BacktestCandlePreparePort.prepareAndReport(codes, interval, lookback)`  ← R2a
  - b. `JvmBacktestEngine.run(INTRADAY_REALISTIC, 전략·codes·lookback)`
  - c. `AutopilotActivationGateService.evaluate(...)` → PASS/FAIL
  - d. PASS → ACTIVE 유지 + `backtest_run` 영속(=`existsAutoActivationReadyRun` 충족) / FAIL → BUY_BLOCKED(매수 억제 · 보유 lot hold-until-exit)
  - 멱등(ADR 0131 `AtomicReference<LocalDate>` 패턴 공유) · 단일 트랜잭션 split-brain 방지.
- [ ] **fail-safe**: 백테스트 에러/INSUFFICIENT_DATA → 보수 FAIL(BUY_BLOCKED) + WARN SystemEvent(거짓 PASS 0).
- [ ] **owner 격리**: owner='AUTOPILOT' 한정 SQL where. USER/SYSTEM touch 0.
- [ ] `StrategyService.activationBlockedReason` — 사용자 경로 byte-동일 확인(변경 0). PASS backtest_run 영속이 기존 게이트와 수렴함을 주석으로 명시.
- [ ] ENV 8건(`magicjar.autopilot.activation-gate.*`) application.yml add — default `enabled=false`·`min-trades=20`·`min-win-rate=0.40`·`min-expectancy=0`·`max-mdd=0.15`·`require-confidence=OK`·`lookback-days=30`·`interval=1m`.
- 산출: #5 게이트. PR `--base main`. 테스트 0 OK(R3). 게이트 OFF → ADR 0131 거동 byte-동일.

> 협업: strategy-engine 이 합격기준·universe 연계 주도, backend-core 가 backtest_run 영속·status 전이·게이트 정합.

---

## R3 — 테스트 일괄 + MOCK 검증 (+ frontend 선택)

**에이전트: strategy-engine + qa (+ frontend 선택)**

- [ ] 단위: 합격기준 경계(trades 19/20·winRate 0.39/0.40·expectancy −1/0/+1·mdd 0.15/0.16·confidence OK/LOW/INSUFFICIENT) · owner 격리 · 멱등 · fail-safe(에러→FAIL) · lookback clamp · 빈 주입(api candlePreparePort != null).
- [ ] MOCK 라이브(ADR 0133 §6 evidence 일괄):
  - #4: 인트라데이 이력 없던 다종목 INTRADAY_REALISTIC 백테스트 → backfill 발화·결과 생성·BACKFILL 버킷만 소모·consumer 정리 후 양쪽 부팅.
  - #5: 08:35 검증 발화·PASS=ACTIVE+backtest_run / FAIL=BUY_BLOCKED+보유 lot 미청산·USER/SYSTEM 격리·INSUFFICIENT_DATA→FAIL·롤백 no-op.
  - parity 사후: 검증 통과 전략의 라이브 거래가 백테스트 예측 TP/SL/EOD 와 일치(KIS 진실원 대조).
- [ ] (선택) frontend: autopilot status 화면에 "오늘 universe 검증 결과(PASS/FAIL·사유·confidence)" 배지 — mock 금지 정직 노출.
- 산출: PR `--base main`. 룰 4(테스트 마지막 일괄) 정합.

---

## 머지 체크리스트 (ADR 0049 — Actions 0건 정상)
1. PR diff 일독(영역·라인·예상 외 변경 0).
2. `./gradlew check` pre-push 자동(GREEN). api·consumer 빈 충돌 0 부팅.
3. 해당 모듈 테스트 통과.
4. 머지 후 worktree·로컬 브랜치 정리(헌법 §2).
5. CHANGELOG 라인(헌법 §4).

## 게이트 ON 절차 (배포 후 orchestrator)
1. #4 검증(INTRADAY_REALISTIC `enabled=true` + 다종목 backfill evidence).
2. #5 검증(`activation-gate.enabled=true` · MOCK 08:35 검증 → canary).
3. 사용자/orchestrator 명시 승인 전까지 default OFF 유지.
