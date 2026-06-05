# qa R3-c — 분할매수(scale-in) 트랙 전 흐름 시나리오 + Visual 검증 보고서

- 작성: qa (품질 헌터) · 2026-06-05
- 브랜치: `agent/qa/r3-scale-in-verification`
- dispatch: orchestrator — 분할매수 트랙 R3-c (헌법 §9-2 frontend 핵심 화면 PR Visual 자동 의무)
- 검증 대상: spec `_workspace/specs/2026-06-05-scale-in-exit-ownership-spec.md` + PR #742~#747 (R1~R3 머지 완료)
- 환경: 라이브 docker — api·consumer·batch·frontend 전 컨테이너 healthy, Flyway **V64**(strategy_position)·**V65**(strategy scale_in_config·partial_exits JSONB) 적용 GREEN
- **toggle 상태**: `magicjar.strategy.scale-in.enabled` = **OFF**(env 미설정 → default false). 이 상태의 기대 동작까지 검증 대상.

> 참고: spec 본문은 Flyway V65·V66 계획이었으나 orchestrator 확정(ADR 0102 재확인)으로 **V64·V65**로 당겨졌다(market_calendar V64 미머지 별 브랜치). DB 실측 flyway_schema_history 최신 = V65, strategy_position + strategy JSONB 컬럼 모두 존재.

---

## 0. 종합 판정

| | 시나리오 | 판정 | 핵심 evidence |
|---|---------|------|--------------|
| S8-1 | 빌더 입력 패널 + 경계값 | ✅ PASS | 비중 합 100→emerald / 99→red, PRICE_DROP_PCT 하락폭 노출, 분할익절 단계 cap 5 |
| S8-2 | 저장→DRAFT round-trip | ✅ PASS | POST → status=PAUSED + JSONB 저장, GET 재조회 동일, DB raw 일치 |
| S8-3 | ACTIVE 차단 UX | ✅ PASS | PUT /status ACTIVE → HTTP 409 + `SCALE_IN_NOT_ENABLED`, PAUSED 유지 |
| S8-4 | 전략별 포지션 패널 | ✅ PASS | GET /position → `[]`, "보유 없음" 표시 (mock 0) |
| S8-5 | 리플레이 분할/익절 | 🟡 부분 PASS | scale-in 결정(`SCALE_IN_SLICE#1`) 노출·calculator 경로 확인 / fill 흐름은 PAUSED 게이트로 관측 불가 |
| S8-6 | 하위호환 | ✅ PASS | scale-in 미설정 전략 카드 분할 배지 미노출·회귀 0 |
| S8-7 | Visual 회귀 | ✅ PASS | baseline 3종 첫 발행 + 재실행 diff 0 GREEN |

**종합 판정: GREEN (조건부)** — dispatch "toggle 해제 전제 충족"의 핵심 6 시나리오(진입 UX·저장·활성화 게이트·포지션 귀속·하위호환·Visual)는 모두 GREEN. **단 S8-5의 청산(stop/partial-tp)·다슬라이스 fill 진행은 toggle OFF + DRAFT(PAUSED) 상태의 리플레이로는 구조적으로 관측 불가**(아래 §S8-5 상세). 이 한 가지는 toggle 해제(ACTIVE 허용) 또는 backtest 경로 재검증으로만 종결 가능하다. 발견 결함은 **scale-in 자체 결함 0** — P0/P1 회귀 없음. 별건 관찰 2건(P2·검증범위 외) 보고.

---

## 1. 검증 방법

dispatch 제약(운영 코드 0 touch·실주문 0·기존 ACTIVE 전략 상태 변경 0·신규 DRAFT·리플레이 sandbox만 OK·mock 0)을 준수하기 위해 두 경로를 병행:

1. **라이브 API 직접 호출**(curl) — S8-2 round-trip, S8-3 서버 거부, S8-5 리플레이, 경계값 서버 권위. DB는 psql 실측 교차.
2. **Playwright E2E/Visual**(`frontend/tests/e2e/s8-scale-in-verification.spec.ts`, chromium 1440×900, ko-KR/Asia-Seoul) — S8-1 빌더 UX, S8-3 round-trip 화면 반영, S8-4 포지션 패널, S8-6 하위호환, S8-7 baseline.

테스트 전제로 라이브 API로 scale-in DRAFT 전략 **strategyId=46**(`qa_scalein_verify_s8`, user 74683223, 005930·15m, 3분할 [50/30/20] PRICE_DROP_PCT -3%, partialExits 2단계 [3%/50%·6%/30%], stop 5%·take 10%)을 생성. PAUSED·미활성·실주문 0 — 제약 준수.

---

## 2. 시나리오별 상세

### S8-1 빌더 입력 패널 — ✅ PASS

`/strategy/builder?strategyId=46` 로드 → 저장 모달 진입 → `ScaleInExitPanel` 조작.

- 분할 매수 토글 ON 시 입력 노출(회수 N·간격·비중·트리거). strategyId=46이 scaleIn 보유 → 토글 ON 상태로 로드됨(`toBeChecked()` PASS).
- **수동 비중 합 검증 색상**: 50/30/20(=100) → status 텍스트 "비중 합 100 / 100" + class `text-emerald-300`. 50/30/**19**(=99) → "99 / 100" + class `text-red-300`. 실측 console:
  - `[S8-1] 합100 status class=mt-1 text-xs text-emerald-300`
  - `[S8-1] 합99 status class=mt-1 text-xs text-red-300`
- **재진입 트리거 PRICE_DROP_PCT** 선택 시 "하락폭%(음수)" 입력 조건부 노출 PASS.
- **분할 익절 단계 cap 5**: "+ 단계 추가" 반복 클릭 → 단계 5개 도달 시 추가 버튼 미노출(`toHaveCount(0)`). 실측 `[S8-1] 분할익절 단계 수(cap 5): 5`.
- baseline: `s8-1-save-modal-scale-in-panel.png`(전체 모달), `s8-1-scale-in-weight-99-red.png`(경계값 RED 패널).

**경계값 서버 권위 교차**(클라이언트 검증 우회·직접 API 방어 확인):

| 입력 | 결과 | 영속 |
|------|------|------|
| totalSlices=11 (cap 10 초과) | HTTP 400 | 미영속 |
| 분할익절 6단계 (cap 5 초과) | HTTP 400 | 미영속 |
| sliceWeights 합=99 | HTTP 400 | 미영속 |
| partialExit weight 합=110 | HTTP 400 | 미영속 |

→ `qa_boundary%` 전략 0건 영속 — 거부 입력이 DB에 새지 않음(fail-closed) 확인.

### S8-2 저장→DRAFT round-trip — ✅ PASS

`POST /api/strategies`(scaleIn 3분할 [50/30/20] PRICE_DROP_PCT + partialExits 2단계) → 응답 status **PAUSED**(ACTIVE 아님 — 신규 전략은 PAUSED 시작).

- **GET 재조회**(`GET /api/strategies/46`): scaleIn = `{"totalSlices":3,"sliceWeights":[50,30,20],"reentryTrigger":"PRICE_DROP_PCT","priceDropPct":-3.0,"minSliceIntervalSec":60}`, partialExits = `[{"triggerProfitPct":3.0,"exitWeightPct":50},{"triggerProfitPct":6.0,"exitWeightPct":30}]` — 저장 입력과 **완전 동일**.
- **DB raw 교차**(`SELECT scale_in_config, partial_exits FROM strategy WHERE id=46`): JSONB 값이 응답과 일치(round-trip 권위). 키 순서만 JSONB 정규화로 재배열(`priceDropPct`·`sliceWeights` 위치) — 의미 동일.

→ JSONB round-trip 무손실. add-only 컬럼·하위호환 보존.

### S8-3 ACTIVE 차단 UX — ✅ PASS (toggle OFF 기대 동작 핵심)

scale-in 설정 전략 46(PAUSED) → `PUT /api/strategies/46/status {"status":"ACTIVE"}`:

- 응답 **HTTP 409 Conflict**. 활성화 거부.
- `GET /api/strategies/46` 의 `activationBlockedReason` = `SCALE_IN_NOT_ENABLED: 분할매수/분할익절 전략은 리플레이·백테스트 정합 검증 후 활성화됩니다 (현재 비활성)`.
- 거부 후 DB status **여전히 PAUSED**(전이 0).
- 화면(Playwright S8-3): 빌더가 GET 상세를 받아 scaleIn round-trip + `activationBlockedReason` 노출. 프런트 `SCALE_IN_NOT_ENABLED_NOTICE` 안내 카피("리플레이·백테스트 정합 검증 완료 후 활성화됩니다 — DRAFT 저장은 가능")가 번들에 존재.

→ spec §8-1 "API 직접 호출 구멍 차단" LOCK 충족 — UI 미노출이 아니라 **서버 검증(StrategyService.activationBlockedReason)**으로 직접 API 호출까지 막음. toggle OFF의 정확한 기대 동작.

### S8-4 전략별 포지션 패널 — ✅ PASS

- `GET /api/strategies/{46,44,8}/position` → 모두 `[]`(strategy_position 0행).
- StrategyList 카드 3개에 "전략 보유 현황" 패널 임베드. 실측 console `[S8-4] 첫 패널 텍스트: "전략 보유 현황보유 없음"` — **보유 0건 "보유 없음"**(mock 합성 0·`feedback_no_mock_data` 정합).
- baseline `s8-4-strategy-list-cards.png`: qa_scalein_verify_s8 카드에 "분할매수 3분할 · 가격 하락 재진입" + "분할익절 2단계" 요약 배지 + "보유 없음" 패널 동시 가시화.

### S8-5 리플레이 분할/익절 — 🟡 부분 PASS (주의 — 아래 권고)

전략 46(scale-in DRAFT)으로 `POST /api/scenario-replay/runs`(005930·1m·2026-06-04 00:00~06:30Z, 초기현금 1천만):

- 이벤트 type 분포: `CANDLE 246·SIGNAL_HOLD 240·SIGNAL_BUY 5·RISK_BLOCKED 5·GATE_NOT_SIMULATED 4`.
- **scale-in 결정 노출 확인**: 모든 SIGNAL_BUY message가 `리플레이 전략 평가 SCALE_IN_SLICE#1: {...}` — 즉 **ScaleInExitCalculator 경로가 리플레이 sandbox에서 toggle와 무관하게 동작**(spec §6-3·§8-1 sandbox 의도 정합). scale-in 결정 레이어는 OK.
- **그러나 fill이 발생하지 않음**: 5건 SIGNAL_BUY 직후 모두 `RISK_BLOCKED — 전략 상태가 PAUSED라 리플레이 가상 BUY 주문을 만들지 않았습니다`. positions 가상 ledger `[]`. `SCALE_IN_SLICE#2+`·`PARTIAL_TAKE_PROFIT`·`STOP_LOSS` 마커 0건.

**구조적 원인**(코드 실측): `ScenarioReplayService`는 `!strategy.status.isEvaluable()`이면 가상 BUY를 만들지 않고 RISK_BLOCKED 처리한다. `StrategyStatus.isEvaluable() = (ACTIVE || BUY_BLOCKED)`. scale-in DRAFT는 toggle OFF 동안 **ACTIVE 전이 불가(S8-3) → 영구 PAUSED → isEvaluable()=false → 리플레이 fill 0**.

→ 즉 **현재 toggle OFF 상태에서는 scale-in 전략의 "평단 변화·다회차 진입·partial-tp 발화·stop 우선" fill 흐름을 리플레이로 끝까지 관측할 수 없다.** dispatch가 "sandbox라 toggle 무관 동작해야 함"이라 했지만, 리플레이 fill은 scale-in **toggle**이 아니라 전략 **status**(PAUSED)에 의해 막힌다. 결정 레이어(calculator 진입)는 toggle 무관 동작이 맞으나, 체결 레이어는 status 게이트 하위.

**판정을 FAIL이 아닌 부분 PASS로 둔 근거**: (1) 이것은 scale-in 회귀가 아니라 리플레이의 기존 status 게이트 설계와 "리플레이 먼저" 활성화 게이트(spec §8-1)가 교차하는 지점이다. (2) calculator가 리플레이 경로에 실제 연결돼 slice 결정을 내림은 확인됐다(R3-b 정합의 핵심). (3) backtest 경로는 status 게이트가 없어 fill 관측 후보였으나 별건 400(아래 §3)으로 본 세션 미확인.

### S8-6 하위호환 — ✅ PASS

- scale-in 미설정 기존 전략(예: strategy `3`) 카드에 분할매수/분할익절 배지 **미노출**.
- `GET /api/strategies/44`(기존 ACTIVE) scaleIn=null·partialExits=null — 기존 전략 0 변경.
- 기존 전략 저장·조회·목록 화면 회귀 0(콘솔 error/pageerror 0건).

### S8-7 Visual 회귀 — ✅ PASS

baseline 3종 첫 발행(`--update-snapshots`) 후 클린 재실행 시 3 test 전부 PASS(diff 0, maxDiffPixelRatio 0.01 이내). 운영 baseline으로 등록.

---

## 3. 발견 사항 (결함 분류)

| # | 분류 | 내용 | 범위 | 권고 |
|---|------|------|------|------|
| F-1 | **검증 공백(P1·scale-in 관련)** | toggle OFF + DRAFT(PAUSED) 조합에서 scale-in 청산(stop/partial-tp)·다회차 fill 흐름이 리플레이로 관측 불가(§S8-5). 결정 레이어만 검증됨, 체결~평단~청산 end-to-end는 미검증. | qa 검증 범위 | toggle 해제 직전 단계에서 ACTIVE 허용 후 리플레이 재검증, **또는** backtest 경로(F-2 선결) 재검증을 dispatch에 추가. toggle 해제 전제의 마지막 미충족 항목. |
| F-2 | **별건(검증범위 외)** | `POST /api/backtests` 가 strategy 44(scale-in 무관 ACTIVE)·46 모두 HTTP 400(message 없음, ~6s 소요 후). scale-in 특이성 0 — 기존 backtest API/param 이슈로 추정. | scale-in 무관 | strategy-engine/backend-core 별도 dispatch 권고. 본 트랙 GREEN 판정에 영향 없음(scale-in 회귀 아님). |
| F-3 | **P2(사전 결함)** | `/vite.svg` favicon 404(s7 보고 기존). S8 회귀 판정에서 제외(known-noise 필터). | frontend | 기보고 P2 유지. |
| F-4 | **주의(Visual 안정성)** | s8-4 full-page baseline에 DART 정정공시 toast 오버레이가 우상단에 캡처됨(transient). 재실행 시 0.01 ratio 이내로 흡수됐으나, 향후 toast 타이밍에 따라 false positive 가능. | qa baseline | 후속 baseline 갱신 시 toast 영역 `mask` 옵션 적용 권고(본 PR은 현 상태로 발행 — diff 0 확인됨). |

**P0 회귀 0 · scale-in 자체 결함 0.**

---

## 4. 산출물

- `frontend/tests/e2e/s8-scale-in-verification.spec.ts` — Playwright spec 3 test (S8-1·S8-3·S8-4·6·7)
- `frontend/tests/e2e/s8-scale-in-verification.spec.ts-snapshots/` — baseline PNG 3종
  - `s8-1-save-modal-scale-in-panel-chromium-darwin.png`
  - `s8-1-scale-in-weight-99-red-chromium-darwin.png`
  - `s8-4-strategy-list-cards-chromium-darwin.png`
- `_workspace/reports/2026-06-05-qa-r3-scale-in.md` — 본 보고서
- CHANGELOG.md 1행 + task 파일 `_workspace/tasks/T-20260605-1540-r3-scale-in-verification.md`

운영 코드 0 touch. 기존 ACTIVE 전략 상태 변경 0. 신규 DRAFT 전략 1건(strategyId=46, PAUSED, sandbox evidence — 향후 리플레이/backtest 재검증 재사용 가능).

---

## 5. toggle 해제 전제 충족 결론

dispatch "GREEN이면 toggle 해제 전제 충족 명시" 요청에 대해:

- ✅ **충족(6/7)**: 빌더 입력 UX·경계값·저장 DRAFT round-trip·ACTIVE 서버 차단·전략별 포지션 귀속·하위호환·Visual — spec §8-1 "리플레이 먼저" 게이트의 **활성화 차단 측면은 완전 검증**.
- 🟡 **미충족 1건**: scale-in 청산/다회차 fill end-to-end 관측(F-1) — 현 toggle OFF + PAUSED 구조에선 리플레이로 도달 불가. **toggle 해제 직전에 ACTIVE 허용 환경(또는 backtest F-2 선결)에서 1회 더 재검증**해야 "리플레이 = 라이브 decision" 회귀 가드가 fill 레이어까지 닫힌다.

→ **권고**: 활성화 게이트·저장·UX는 운영 투입 가능 수준 GREEN. toggle(`MAGICJAR_STRATEGY_SCALE_IN_ENABLED=true`) 실제 해제는 F-1 재검증 1라운드(qa 재dispatch)를 거친 뒤 사용자 결재로 진행.
