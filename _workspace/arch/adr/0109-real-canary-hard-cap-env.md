# ADR 0109 — REAL canary hard cap ENV 게이트

- 상태: **Accepted (2026-06-05)**
- 작성자: architect
- 결정자: 사용자(최고 관리자) — canary 실행 결재와 동반
- 영역: 운영 안전 게이트 (자본 path) + ENV 정책
- 관계:
  - runbook: `docs/operations/real-canary-runbook.md`
  - spec: `_workspace/specs/2026-06-05-real-canary-spec.md`
  - ADR 0043(KillSwitch) · ADR 0067(Risk Gateway) · ADR 0068(Order Execution 3중 게이트) · ADR 0095(SLO·alert) · ADR 0096(DR/backup) · ADR 0106(RiskPlan 필수) · ADR 0102(자원 audit)
  - readiness: `docs/assessments/2026-06-05-autotrading-readiness.html` (REAL canary +5 P1 게이트)

## 1. Context

readiness 재평가(2026-06-05)는 종합 87·MOCK 95·REAL 78로 격상하면서, REAL 무인 운용의 마지막 게이트로 "소액 1일 canary evidence"를 지목했다(REAL 점수 상한 78 유지 사유 = canary evidence 0).

canary는 실 자본 최소 단위로 "전략이 실주문을 내고, 체결되고, 알림이 오고, 멈출 수 있는가"를 1회 증명하는 행위다. 그 본질은 **실수해도 손실이 작게 캡됨**이다.

현행 코드(spec §2·§3 실측)는 다음을 이미 갖췄다: 3중 게이트(TradingModeGateImpl), KillSwitch 재검증(fail-closed), 장운영시간 게이트(AUTO), KIS 체결 sync(REAL), RiskPlan 필수(ADR 0106), RiskGateway 룰 7종(POSITION_LIMIT·DAILY_LOSS_LIMIT·MAX_CONCURRENT_POSITIONS·TRADE_FREQUENCY 등), TRADE 즉시 Slack(order.filled/rejected P0).

그러나 **canary 전용 전역 hard cap이 없다**(spec §3 GAP-1·2·3):

1. OrderRouter는 `intent.quantity <= 0`만 검사하고 **수량 상한이 없다**(`OrderRouterKafkaListener` L212).
2. notional 상한은 POSITION_LIMIT(RiskGateway)이 사용자 RiskConfig budget 기준으로만 차단한다 — canary 절대 cap이 아니다.
3. 일일 REAL 주문 횟수 전역 상한이 없다(RiskConfig `maxTradesPerDay`는 전략당 50 default).

RiskConfig/POSITION_LIMIT는 전략·사용자 단위 **정책 게이트**이지, "canary 1회 동안 실수로라도 이 이상은 절대 안 나간다"는 **운영 안전선**이 아니다. 전략 설정 오류·budget 오산·다전략 누적을 마지막에 강제 차단하는 게이트가 필요하다.

## 2. Decision

REAL canary 전용 hard cap을 ENV 3건으로 신설한다. consumer OrderRouter에서 RiskConfig·전략 설정과 **독립적으로** 강제한다.

| ENV | 의미 | reject reason |
|-----|------|----------------|
| `MAGICJAR_CANARY_MAX_ORDER_QTY` | REAL 주문 1건 수량 절대 상한 | `CANARY_QTY_CAP` |
| `MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW` | REAL 주문 1건 notional(qty × price) 절대 상한 | `CANARY_NOTIONAL_CAP` |
| `MAGICJAR_CANARY_MAX_ORDERS_PER_DAY` | 당일 REAL 주문 횟수 전역 상한 | `CANARY_DAILY_ORDER_CAP` |

1. **활성 조건**: `MAGICJAR_MODE=REAL` + 해당 ENV > 0 일 때만 게이트 활성.
2. **fail-open**: ENV 미설정 또는 ≤ 0이면 게이트 통과(일반 REAL 운용 — canary 모드 아님). 깜빡 미설정 risk는 runbook §1-3 P-3 사전 체크리스트로 보완.
3. **강제 위치**: OrderRouter Step 3(3중 게이트) 이후 ~ Step 5(INSERT) 이전 `CanaryHardCapGate`. 초과 시 REJECTED + audit + order.rejected.v1.
4. **일일 횟수 카운트**: `order_request` 당일 `mode='REAL'` count — DB 단일 권위(Redis 카운터 미사용 — drift 회피).
5. **notional price source**: `limitPrice`(LIMIT) 또는 `priceAtSignal`(MARKET) 사용.
6. **RiskConfig와 독립**: hard cap은 canary 안전선, RiskConfig는 운용 정책. 둘 다 통과해야 주문이 나간다(더 엄격한 쪽 적용).

## 3. Consequences

- canary 중 전략·budget 오류로 인한 과대 주문이 OrderRouter 단에서 강제 차단된다.
- ENV 3건은 `.env.example` add-only. Flyway·Kafka·Redis·STOMP·REST 신설 0 (spec §4 audit).
- canary 종료 후 `.env`에서 주석 처리하면 비활성(MOCK에서는 자동 비활성 — REAL 모드 조건).
- 구현은 본 ADR 발행 후 backend-core 후속 dispatch (consumer OrderRouter, ~90 LOC + 테스트).
- 무인 운용 본격 진입 시, hard cap을 상시 운영 cap으로 승격할지는 별 ADR로 검토(fail-open → fail-closed 전환 포함).

## 4. 운영 ADR 양식 3 슬롯 (헌법 §9-5)

### 4-1. SLO 영향

직접 SLO 수치 영향 0. canary 안전선 강화로 S-5(KillSwitch 자동 ON)·자본 path 결함 발생 확률을 낮춘다. canary evidence 수집을 통해 readiness REAL 점수 상한(78) 해제 입력을 제공한다.

### 4-2. rollback plan

본 ADR은 정책 결정 — 본 PR(ADR + runbook + spec + README + CHANGELOG)은 운영 코드 0 touch. revert 시 단일 PR revert. 후속 구현 PR(CanaryHardCapGate)은 별도 revert — ENV 미설정 시 fail-open이므로 코드 revert 후에도 기존 RiskConfig 게이트는 그대로 동작(자본 보호 공백 0).

### 4-3. 관측 매트릭스

canary 1회 수행 후:

| 측정 항목 | 목표 | 측정 방법 |
|----------|------|----------|
| hard cap reject 발생(의도적 과대 시그널 시) | 초과 시 REJECTED | `order_audit_event` reason `CANARY_*_CAP` |
| canary 중 cap 초과 실주문 | 0건 | `order_request mode=REAL` qty·notional·count vs ENV |
| KillSwitch 자동 ON | 0건 | `killswitch.engaged.v1` |
| 자본 path 1건 단위 추적 완주 | 100% | runbook §6 evidence SQL |

## 5. 트레이드오프

| 결정 | 채택 사유 | 거부 대안 |
|------|----------|----------|
| 전용 ENV (RiskConfig와 독립) | 운영 cap 오염 0 + canary 종료 후 비활성 용이 + Flyway 불요 | RiskConfig 필드 추가 → Flyway + 도메인 + 상시 운용 영향 |
| fail-open (ENV 없으면 통과) | 일반 REAL 운용 차단 회피 + runbook 체크리스트 보완 | fail-closed → REAL 진입을 canary ENV에 묶음(일반 운용 차단) |
| 일일 카운트 DB 조회 | order_request 단일 권위 | Redis 카운터 → drift risk |
| 구현 후속 dispatch | spec 사이클 md only(개발 룰 R2 정합) | 본 PR에 코드 동반 → 2영역 PR 위반 |

## 6. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-06-05 | **ADR 0109 Accepted** — REAL canary 전용 hard cap ENV 3건(`MAGICJAR_CANARY_MAX_ORDER_QTY`·`_MAX_ORDER_NOTIONAL_KRW`·`_MAX_ORDERS_PER_DAY`) 신설. REAL 모드 + ENV>0 활성 / fail-open. OrderRouter CanaryHardCapGate 후속 구현. runbook + spec 동반. 운영 코드 0 touch. | architect (readiness REAL canary P1 게이트 정합) |
