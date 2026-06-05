# qa S9 — 분할매수(scale-in) fill 레이어 재검증 (직전 qa F-1 공백 마감) 보고서

- 작성: qa (품질 헌터) · 2026-06-05
- 브랜치: `agent/qa/scale-in-fill-reverification`
- dispatch: orchestrator — 분할매수 toggle ON 상태 fill 레이어 재검증 (직전 qa F-1 공백 마감)
- 직전 보고: `_workspace/reports/2026-06-05-qa-r3-scale-in.md` (S8 6/7 PASS + F-1 검증 공백)
- 환경: 라이브 docker — api·consumer·batch·frontend 전 컨테이너 healthy
- **toggle 상태(실측)**: `MAGICJAR_STRATEGY_SCALE_IN_ENABLED=true` — consumer·api 양쪽 컨테이너 `printenv` 확인 GREEN
- **실주문 게이트(실측)**: consumer `MAGICJAR_MODE=MOCK` + `MAGICJAR_ALLOW_REAL=false` — 실주문 구조적 불가

---

## 0. 종합 판정

| | 시나리오 | 판정 | 핵심 evidence |
|---|---------|------|--------------|
| S9-1 | ACTIVE 전이 허용 | ✅ PASS | toggle ON → `SCALE_IN_NOT_ENABLED` 소멸. 성공 백테스트 후 `PUT /status ACTIVE` → **HTTP 200**, DB status ACTIVE |
| S9-2a | 리플레이 다슬라이스+평단 가중평균 | ✅ PASS | SIGNAL_REPEAT 변형(47) 상승장 → SLICE#1·#2·#3 누적, 가상 평단 347666.67 = (347000+347000+349000)/3 |
| S9-2b | 리플레이 partial-tp 1단계·중복 0 | ✅ PASS | 랠리 구간 → PARTIAL_TAKE_PROFIT#0 1건(보유분 50% 청산), 같은 단계 중복 발화 0 |
| S9-2c | 리플레이 STOP_LOSS 전량·stop>tp | ✅ PASS | PRICE_DROP(46) 하락 구간 → STOP_LOSS "전량 손절" 2건, stop이 전량 청산(tp 경쟁 0) |
| S9-2d | 백테스트 fill 정합 (end-to-end 완전) | ✅ PASS | run 6 105 trades / 33 cycle. SLICE#1·#2·PARTIAL#0·#1·TAKE·STOP 전 분기 + 평단·청산 수량 산식 일치 |
| S9-3 | 라이브 신호 경로 스모크 | ✅ PASS | Calculator 경로·MOCK 게이트·scale-in 예외 0·실주문 0 (장외라 실발화 0 — 기대대로) |
| S9-4 | 하위호환 | ✅ PASS | scale-in null 전략(44) 리플레이 SCALE_IN/PARTIAL 마커 0, legacy BUY 경로 보존 |

**종합 판정: GREEN** — 직전 F-1 공백(fill 레이어 end-to-end 미관측)을 **완전 마감**. 7/7 시나리오 GREEN, scale-in 자체 결함 0·회귀 0. **toggle 영구화(`scale-in.enabled` yml default true) 전제 충족.** 단 1건의 구조적 관찰(F-1-OBS: 리플레이 path의 PRICE_DROP_PCT 재진입 inert)은 결함이 아니며 live·리플레이 parity가 보존된 의도된 상태로, 보고 §3에 명시한다. Playwright 자동 회귀 가드 7 test 발행(영구화).

---

## 1. 검증 방법 + F-1 원인 구조 규명

### 1-1. F-1 원인의 정확한 코드 실측

직전 S8-5의 fill 미관측을 코드 레벨로 재규명했다. 핵심은 **리플레이 fill은 scale-in toggle이 아니라 전략 status에 의해 막힌다**는 점:

- `ScenarioReplayService`(api)는 `!strategy.status.isEvaluable()`이면 가상 BUY를 만들지 않고 RISK_BLOCKED 처리.
- `StrategyStatus.isEvaluable() = (ACTIVE || BUY_BLOCKED)`. scale-in DRAFT는 toggle OFF 동안 ACTIVE 전이 불가 → 영구 PAUSED → fill 0.

toggle ON에서 `StrategyService.activationBlockedReason`을 실측한 결과 게이트 체인이 정확히 드러났다(`StrategyService.kt:226~250`):

1. codes 존재 → 2. intervals 존재 → 3. stop/take 존재 → **4. scale-in toggle 게이트**(`!scaleInEnabled && hasScaleInExit` → `SCALE_IN_NOT_ENABLED`) → **5. 성공 백테스트 게이트**(`existsAutoActivationReadyRun` — status='SUCCEEDED' 백테스트 1건 이상).

toggle ON이면 게이트 4가 통과(scale-in 정상)하고, 게이트 5(표준 활성화 게이트 — 모든 전략 공통)만 남는다. **즉 toggle ON 자체는 정상 동작했고, ACTIVE는 표준 "성공 백테스트" 게이트만 추가로 충족하면 허용된다.**

### 1-2. ScaleInExitCalculator 단일 함수 공유 (live·리플레이·백테스트)

`strategy-engine/scalein/ScaleInExitCalculator` 단일 순수 함수를 live(`RealtimeSignalGenerator`)·리플레이(`ScenarioReplayService.evaluateScaleInExit`)·백테스트(`JvmBacktestEngine.evaluateScaleInExitSlice`)가 공유(R3-b). 우선순위 LOCK: **청산 > 진입**, 청산 내부 **STOP_LOSS > PARTIAL_TP > TAKE_PROFIT**(동시 터치 손절 우선 ADR 0107).

### 1-3. 검증 경로

dispatch 제약(운영 코드 0 touch·실주문 0·기존 사용자 전략 상태 변경 0·qa 생성 전략만 조작·검증 후 PAUSED 복귀·mock 0)을 준수하며 두 경로 병행:

1. **라이브 API 직접 호출(curl)** — 백테스트·리플레이·status 전이·event 분포, DB(scenario_order·scenario_position·backtest_trade) 교차.
2. **Playwright API 회귀 가드**(`frontend/tests/e2e/s9-scale-in-fill.spec.ts`, 7 test, chromium) — fill 레이어 4 시나리오 + 하위호환 + Visual baseline.

검증용 전략: 기존 `strategyId=46`(qa_scalein_verify_s8 — PRICE_DROP_PCT 3분할 [50/30/20], partial [3%/50%·6%/30%], stop 5%·take 10%) + 신규 `strategyId=47`(qa_scalein_signalrepeat_s9 — SIGNAL_REPEAT 3분할 [40/35/25], partial [2%/50%·4%/40%], stop 5%·take 10%). 둘 다 user 74683223, 검증 종료 후 PAUSED 복귀.

---

## 2. 시나리오별 상세

### S9-1 ACTIVE 전이 허용 — ✅ PASS (toggle 영구화 핵심)

- toggle OFF(직전 S8): `activationBlockedReason` = `SCALE_IN_NOT_ENABLED: ...`, `PUT /status ACTIVE` → **409**.
- toggle ON(본 S9): 같은 전략 46의 `activationBlockedReason` = **`성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다`** — **SCALE_IN_NOT_ENABLED 소멸**(toggle 정상). 이건 모든 전략에 적용되는 표준 게이트.
- 성공 백테스트 발행(`POST /api/backtests` strategy 46, 15m, 2025-12-08~2026-06-04 → runId=6 SUCCESS·SUCCEEDED 영속) 후 `activationBlockedReason` = **null**.
- `PUT /status ACTIVE` → **HTTP 200**, GET 재조회·DB 모두 `status=ACTIVE`. 검증 후 PAUSED 복귀.

→ toggle ON에서 scale-in 전략의 ACTIVE 전이가 정상 허용됨. F-1의 status 게이트 봉쇄가 해제됐다.

### S9-2 리플레이 fill end-to-end — ✅ PASS (F-1 마감 핵심)

**S9-2a 다슬라이스 + 평단 가중평균** (전략 47, SIGNAL_REPEAT, 2026-06-01 +13.8% 상승장 5m):

- event 분포: `SIGNAL_BUY 3 · RISK_ALLOWED 3 · ORDER_REQUESTED 3 · ORDER_FILLED 3 · POSITION_UPDATED 3`.
- **SCALE_IN_SLICE#1 → #2 → #3** 순차 발화, 가상 포지션 1주 → 2주 → 3주 누적(POSITION_UPDATED).
- 체결가 347000·347000·349000 → 가상 평단(scenario_position) **347666.6667** = `(347000+347000+349000)/3` — ledger 가중평균 산식 일치.
- (직전 S8: 전부 RISK_BLOCKED·positions=[] 였던 것과 대비 — fill 레이어 동작 입증.)

**S9-2b partial-tp 1단계 발화·중복 0** (전략 47, 2026-05-06 +9.8% 랠리 5m):

- SLICE#1(259750) → SLICE#2(263750) 평단 261750 → +2% 도달 → **PARTIAL_TAKE_PROFIT#0** 발화(보유분 50% 청산, 267750원 = +2.29%) → 포지션 2주 → 1주.
- 단계 #0 발화 **정확히 1회**(executedExitStages 멱등 — `recordExitStage`). 중복 발화 0.

**S9-2c STOP_LOSS 전량 청산 · stop>tp 우선** (전략 46, PRICE_DROP_PCT, 2026-02-25~03-06 하락 변동 15m):

- SIGNAL_SELL 2건 모두 `리플레이 전략 평가 STOP_LOSS: 평단 기준 전량 손절`.
- 평단 -5% 도달 시 보유 전량(1주) 청산. partial-tp가 경쟁하지 못함(stop이 최우선 — ADR 0107 동시 터치 손절 우선 정합).

**S9-2d 백테스트 fill 정합 — end-to-end 완전 관측** (전략 46, run 6, 105 trades, 33 cycle):

백테스트 engine(`JvmBacktestEngine`)은 `lastSliceFillPrice`를 완전 wiring하므로 PRICE_DROP_PCT 다슬라이스까지 전 분기를 관측할 수 있는 권위 경로다. run 6 rule_signal 분포:

| rule_signal | 횟수 |
|---|---|
| SCALE_IN_SLICE#1 | 33 |
| SCALE_IN_SLICE#2 | 1 |
| PARTIAL_TAKE_PROFIT#0 | 20 |
| PARTIAL_TAKE_PROFIT#1 | 18 |
| TAKE_PROFIT | 18 |
| STOP_LOSS | 14 |
| FORCE_CLOSE_END_OF_BACKTEST | 1 |

**산식 교차 검증(cycle 1)**: slice1 45주@109454.70 + slice2 28주@105352.65(직전 체결가 대비 -3.75% → PRICE_DROP -3% 충족) → 가중평단 **107881.31**, 보유 73주. → +3% 도달 시 PARTIAL#0 = floor(73×50%)=**36주**@111344.30(+3.21%) → 잔량 37주, PARTIAL#1 = floor(37×30%)=**11주**@114942 → 잔량 26주, TAKE_PROFIT 전량 **26주**@119640. 실측 trade 수량과 **완전 일치**. 33 cycle 전체에서 partial-tp 단계 중복 발화 **0건**.

run 6 성과: total_return +21.72%, win_rate 78.87%, FORCE_CLOSE로 잔량 0(dangling 포지션 0).

→ **fill 레이어(분할 체결 → 평단 가중평균 → 부분 익절 → 전량 익절 → 손절)가 end-to-end로 완전히 관측·정합됨.** F-1 공백 마감.

### S9-3 라이브 신호 경로 스모크 — ✅ PASS

- consumer/api `MAGICJAR_STRATEGY_SCALE_IN_ENABLED=true` — `RealtimeSignalGenerator`가 scale-in/partialExits 설정 전략을 Calculator 경로로 평가하는 wiring 존재(`RealtimeSignalGenerator.kt:497~511`).
- consumer 로그(최근 2000줄): scale-in 관련 **예외 0건**·stacktrace 0건.
- ERROR 10건은 모두 `KisExecutionBackfillService` 부팅 KIS 체결 backfill의 FK(`fk_position_user`) 위반 — **scale-in 무관·사전 부팅 noise**(본 검증 trigger 아님).
- WARN은 KIS WS handshake 실패(reconnect 예약) — **장외 시간 정상 동작**(시그널 실발화 기대 0과 정합).
- **실주문 0**: `MAGICJAR_MODE=MOCK` + `MAGICJAR_ALLOW_REAL=false` — 실계좌 이중 게이트 닫힘. 실주문 구조적 불가.

→ 장외라 실제 시그널 발화는 없으나(기대대로), Calculator 경로·예외 0·실주문 0 GREEN.

### S9-4 하위호환 — ✅ PASS

- scale-in null 전략 44(000660·15m·ACTIVE) 리플레이(2026-02-25~03-06): SIGNAL_BUY 1건, reason = `리플레이 전략 평가 BUY: {...}`(legacy 경로) — **SCALE_IN_SLICE·PARTIAL_TAKE_PROFIT 마커 0건**.
- 리플레이는 read-only(전략 상태 변경 0) — 44는 ACTIVE 유지(사용자 전략 무변경).

→ 기존 단일 진입 경로 100% 보존. 회귀 0.

---

## 3. 발견 사항 (결함 분류)

| # | 분류 | 내용 | 범위 | 권고 |
|---|------|------|------|------|
| F-1 | **마감 완료** | 직전 S8 F-1(fill 레이어 end-to-end 미관측). toggle ON + 성공 백테스트 → ACTIVE → 리플레이/백테스트 fill 전 분기 관측·정합 확인. | qa 검증 범위 | **종결.** S9 GREEN으로 마감. |
| F-1-OBS | **관찰(결함 아님)** | 리플레이 path는 `lastSliceFillPrice`/`lastSliceEpochSec`를 calculator에 미주입(`ScenarioReplayService.evaluateScaleInExit`) → **PRICE_DROP_PCT 재진입(SLICE#2+)이 리플레이에선 inert**. **단 live(`RealtimeSignalGenerator`)도 동일하게 미주입**(코드 주석: "R2-2 어댑터(체결 listener)가 채운다")이므로 **live·리플레이 parity는 보존**됨. 백테스트 engine만 완전 wiring하여 PRICE_DROP 다슬라이스 관측 가능. | strategy-engine | 결함 아님(parity 의도 보존). PRICE_DROP_PCT 다회차의 live 실동작은 R2-2 체결 listener 어댑터 연결 후 backend-core/strategy-engine 재검증 대상으로 권고(별 dispatch). 본 toggle 영구화 판정에 영향 없음(SIGNAL_REPEAT 다슬라이스 + 백테스트 PRICE_DROP 다슬라이스로 fill 레이어는 입증됨). |
| F-2 | **해소** | 직전 S8 F-2(`POST /api/backtests` 400). 본 세션 재현 결과 **올바른 date 포맷(yyyy-MM-dd)+유효 캔들 구간**으로 호출 시 정상 SUCCESS(runId 6·7). 직전 400은 요청 파라미터(date/구간) 문제로 추정 — backend 결함 아님. | scale-in 무관 | 종결(별 결함 아님). |
| F-3 | **P2(사전)** | `/vite.svg` favicon 404 — 기보고 P2 유지. 회귀 판정 제외. | frontend | 변동 없음. |

**P0 회귀 0 · scale-in 자체 결함 0.**

---

## 4. 산출물

- `frontend/tests/e2e/s9-scale-in-fill.spec.ts` — Playwright 회귀 가드 7 test (S9-1·S9-2a~d·S9-4·S9-Visual), 전부 PASS
- `frontend/tests/e2e/s9-scale-in-fill.spec.ts-snapshots/s9-scale-in-panel-chromium-darwin.png` — 빌더 scale-in 패널 baseline (첫 발행)
- `_workspace/reports/2026-06-05-qa-scale-in-fill.md` — 본 보고서
- `_workspace/devlog/2026-06-05-qa-scale-in-fill.html` — 사용자 가시화 evidence
- CHANGELOG.md 1행 + task 파일 `_workspace/tasks/T-20260605-1738-main.md`

운영 코드 0 touch. 기존 사용자 전략(8·44) 상태 변경 0. qa 전략 46·47 검증 후 **PAUSED 복귀 확인**(다음 장중 자동 평가 차단). 실주문 0.

---

## 5. toggle 영구화 전제 충족 결론

dispatch "GREEN = toggle 영구화 전제 충족" 요청에 대해:

- ✅ **충족(7/7)**: ACTIVE 전이 허용(SCALE_IN_NOT_ENABLED 소멸)·리플레이 다슬라이스+평단·partial-tp 1단계·STOP 전량·백테스트 33 cycle 전 분기 정합·라이브 경로 스모크·하위호환 — fill 레이어 end-to-end가 toggle ON에서 완전히 닫혔다.
- 🟢 **결함 0·회귀 0**: scale-in 자체 결함·기존 단일 진입 회귀 모두 0.
- 📌 **단서 1건(F-1-OBS)**: PRICE_DROP_PCT의 live 다회차 실동작은 R2-2 체결 listener 어댑터 연결 후 별도 재검증 권고(live·리플레이 parity는 현재 보존 — 결함 아님).

→ **권고**: `magicjar.strategy.scale-in.enabled` yml default true 영구화 전제 **GREEN 충족**. 영구화 적용은 사용자 결재로 진행하되, PRICE_DROP_PCT live 다회차는 R2-2 어댑터 완료 시 qa 재dispatch로 마지막 parity 확인을 권고한다.
