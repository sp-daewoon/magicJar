# magicjar-autopilot Phase 0 안전 하니스 — 구현 계획

> **실행 모델:** magicJar orchestrator 디스패치(도메인 에이전트별 태스크). 각 태스크 = 1 에이전트 deliverable + 단위테스트 + PR.
> 스펙: `_workspace/specs/2026-06-22-autopilot-self-augmentation.md`. 롤백 앵커: **v3.14.0**.

**목표:** 완전 자율 REAL 트레이딩의 전제 — 코드로 강제되는 안전 회로차단기·kill switch·격리·롤백을 구축하고 orchestrator가 실증.

**1원칙:** 코드 강제 > 프롬프트. 모든 한도는 코드 경로에 박는다(LLM 판단 비신뢰).

## Global Constraints
- 롤백 앵커 = `v3.14.0` (git tag immutable).
- 루프 생성물은 `strategy.owner=AUTOPILOT`로 격리 — 사용자 수동 전략·후성 오버나잇 절대 비간섭.
- 신규 ENV는 보수적 default 보유(미설정=안전측). REAL 주문 경로는 기존 MAGICJAR_MODE=REAL+ALLOW_REAL 이중 게이트 위에 Guard 추가.
- Flyway 현 최신 V90 — 신규는 V91+ (디스패치 시 충돌 재확인 의무).
- 자본 한도 default: 풀=순자산5% / 건당=풀20% / 동시보유3 / 일일손실=풀30% / 누적손실=풀50%.

---

## Task 1 — autopilot_state Flyway + 도메인 (backend-core)
**Files:** `domain/.../autopilot/AutopilotState.kt`(신규), `persistence-shared` or `api` Flyway `V91__autopilot_state.sql`, `strategy.owner` enum에 `AUTOPILOT` 추가(V88 정합 확인).
**Produces:** `AutopilotState`(killActive: Boolean, poolPct·perTradePct·maxPositions·dailyLossPct·cumLossPct: BigDecimal, cumRealizedLoss: BigDecimal, dailyRealizedLoss: BigDecimal, lastResetDate). 단일 행(account-wide) 또는 user별.
**Test:** Flyway 마이그레이션 적용 + 엔티티 라운드트립.
**검증:** `:domain:test` + Flyway up GREEN.

## Task 2 — AutonomousTradingGuard 순수 로직 (strategy-engine)
**Files:** `strategy-engine/.../autopilot/AutonomousTradingGuard.kt`(신규) + 테스트.
**Interfaces — Produces:**
- `fun evaluate(input: AutopilotGuardInput): AutopilotGuardVerdict`
- `AutopilotGuardInput(loopExposure, accountNetWorth, proposedEntryAmount, currentLoopPositions, dailyRealizedLoss, cumRealizedLoss, state: AutopilotState)`
- `AutopilotGuardVerdict`: sealed — `Allow(approvedAmount)` / `Reject(reason)` / `KillTriggered(reason)`.
**로직(순수·결정론):** ①풀 상한 초과→Reject ②건당 캡 초과→approvedAmount clamp ③동시보유 ≥max→Reject ④일일손실 ≥한도→Reject(당일halt) ⑤누적손실 ≥한도→KillTriggered. RiskBudgetCalculator와 **더 보수적 값이 이김**.
**★ 손실 한도 base = 풀**(`poolCeiling × dailyLossPct/100`·`× cumLossPct/100`), 순자산 아님 — spec §2-0-A '매우 타이트' 권위(2026-06-22 정정). 순자산 기준이면 누적 kill이 순자산 절반 손실에서야 터져 위배.
**Test:** 각 한도 경계(미만 통과·도달 거부·누적 kill)·clamp·zero-regression(autopilot 아닌 전략 무영향).
**검증:** `:strategy-engine:test` GREEN.

## Task 3 — Kill switch + Guard wiring (consumer/backend-core)
**Files:** `consumer/.../autopilot/AutopilotKillSwitch.kt`, `RiskGatewayEvaluator` 또는 매수 경로에 Guard 삽입(owner=AUTOPILOT 전략만), kill ON시 owner=AUTOPILOT PAUSED + REAL freeze.
**Interfaces — Consumes:** Task2 Guard, Task1 AutopilotState.
**로직:** owner=AUTOPILOT 매수 intent는 Guard.evaluate 통과 의무. KillTriggered→killActive=true 영속+전략 PAUSED+Slack P0. killActive시 REAL 경로 차단(페이퍼·백테스트 허용).
**Test:** Guard Reject시 intent 미발행 / kill ON시 owner=AUTOPILOT만 PAUSED·사용자 전략 보존 / 누적손실 도달 자동 kill.
**검증:** `:consumer:test` GREEN.

## Task 4 — /api/autopilot/* + 스냅샷·롤백 스크립트 (backend-core)
**Files:** `api/.../controller/autopilot/AutopilotController.kt`(GET status·POST stop[kill]·POST rollback), `scripts/autopilot-snapshot.mjs`, `scripts/autopilot-rollback.mjs`.
**Endpoints:** `GET /api/autopilot/status`(killActive·pool·손실누적·owner=AUTOPILOT 전략수) / `POST /api/autopilot/stop`(수동 kill) / `POST /api/autopilot/rollback`(owner=AUTOPILOT PAUSED→삭제+포지션 청산 트리거). Swagger 4단.
**rollback 스크립트:** git reset --hard v3.14.0 안내 + owner=AUTOPILOT 정리 + 스냅샷 복원(돈 청산은 API 경유 KIS).
**Test:** endpoint 응답·stop이 kill ON·rollback이 owner=AUTOPILOT만.
**검증:** `:api:test` GREEN.

## Task 5 — 포지션 청산 경로 (market-data)
**Files:** rollback·kill시 owner=AUTOPILOT 보유 시장가 청산 — 기존 SELL 주문 경로(`order-cash` TTTC0011U/VTTC0011U) 재사용. 신규 KIS endpoint 0.
**Test:** 청산 intent가 owner=AUTOPILOT 포지션만 대상·롱온리 정합.
**검증:** `:consumer:test`(주문 경로) GREEN.

---

## R2 검증 게이트 (orchestrator 실증 — Phase 1 진입 전 필수)
MOCK 환경에서 내가 직접:
1. 칭 위반 매수 코드 거부 실증.
2. 누적손실 한도 도달 → kill switch 자동 ON + owner=AUTOPILOT PAUSED 실증.
3. `/autopilot rollback` owner=AUTOPILOT만 되감고 사용자 전략·후성 보존 실증.
4. 스냅샷 캡처·복원 실증.

## 자기 검토 (spec 커버리지)
- 스펙 §2-0-A Guard → Task2·3 ✓ / §2-0-B kill → Task3 ✓ / §2-0-C 태깅 → Task1(owner enum)·3 ✓ / §2-0-D 스냅샷 → Task4 ✓ / §3 롤백 → Task4·5 ✓ / §4 자원 audit → Global Constraints + Task1 Flyway ✓.
- Phase 1 스킬(R3)·라이브(R4)는 본 계획 범위 밖(Phase 0 검증 후 별도).
