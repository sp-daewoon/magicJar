# spec — REAL Canary (실계좌 소액 1일 canary) + 코드 가드 갭 매트릭스

- 작성: architect (2026-06-05)
- 브랜치: `agent/architect/real-canary-runbook`
- 동반 산출물: `docs/operations/real-canary-runbook.md` · ADR 0109(canary hard cap ENV)
- 트리거: readiness 재평가(`docs/assessments/2026-06-05-autotrading-readiness.html`) 잔여 P1 게이트 +5 — REAL canary evidence

---

## 1. 목적 / 범위 / 비범위

### 1-1. 목적

REAL 무인 자동매매 진입의 마지막 게이트인 "소액 1일 canary"를 안전하게 수행할 절차서(runbook)를 발행하고, 그 수행에 **현행 코드가 충분한지** 갭을 식별한다. readiness 평가가 REAL 점수 상한 78을 해제하기 위해 요구하는 "시그널→리스크→KIS 결과 0/1건 단위 완전 추적 evidence" 산출의 전제 조건을 정리한다.

### 1-2. 범위 (본 사이클)

- canary runbook 발행 (사전조건·파라미터·시작·모니터링·중단·evidence·rollback).
- 코드 가드 갭 매트릭스 식별 (§3) — 현행 코드를 읽고 "있다/없다" 판정.
- canary hard cap 정책 결정 ADR 0109 발행 (ENV 신설 결정).

### 1-3. 비범위 (NOT in scope)

- **canary 실행 자체** — 최고 관리자 결재·참여 하에 별도 진행 (실 자본 변동 행위).
- **갭 구현** — §3에서 "신규 필요"로 판정된 가드의 코드 구현은 본 task 외. orchestrator가 후속 dispatch.
- 전략별 포지션 귀속(readiness Phase C-2) — 별 ADR·사이클.
- 운영 코드 touch 0 (본 PR은 md only — spec 사이클).

---

## 2. 현행 코드 실측 (추측 0 — 실제 파일 인용)

canary 자본 흐름의 각 게이트를 현행 코드에서 직접 확인했다.

| 게이트 | 코드 | 동작 (실측) |
|--------|------|-------------|
| 3중 게이트 | `consumer/.../order/TradingModeGateImpl.kt` L48-89 | 조건1 `MAGICJAR_MODE==REAL` 아니면 MOCK fallback / 조건2 `MAGICJAR_ALLOW_REAL==true` 아니면 `RealGateDenied("MAGICJAR_ALLOW_REAL=false")` / 조건3 `consentAt==null`이면 `CONSENT_NOT_GIVEN` |
| OrderRouter 흐름 | `consumer/.../order/OrderRouterKafkaListener.kt` L121-450 | 멱등성→3중게이트→`quantity<=0` reject→KillSwitch(fail-closed, port 미합류·예외 시 ON 간주)→장운영시간(AUTO만)→KIS sync(REAL)→INSERT→KIS submit |
| 수량 검증 | `OrderRouterKafkaListener.onOrderIntent` L212-216 | **`intent.quantity <= 0` 만 검사. 상한 없음** |
| RiskGateway | `consumer/.../risk/RiskGatewayEvaluator.kt` | KILL_SWITCH / DAILY_LOSS_LIMIT / MAX_CONCURRENT_POSITIONS / TRADE_FREQUENCY_MINUTE·DAY / POSITION_LIMIT / SELL_WITHOUT_POSITION / SELL_QUANTITY_UNAVAILABLE — 모두 사용자별 `RiskConfig` 기반 |
| RiskConfig | `domain/.../risk/RiskConfig.kt` | `maxPositionAmount`·`maxPositionPercent`·`dailyLossLimit(Amount\|Percent)`·`maxConcurrentPositions`·`maxTradesPerMinute`·`maxTradesPerDay`. **canary 전역 hard cap 필드 없음** |
| KIS 주문 어댑터 | `consumer/.../order/KisOrderHttpAdapter.kt` | REAL=TTTC0012U(BUY)/TTTC0011U(SELL), MOCK=VTTC. rate limit USER_PATH + hashkey + 5xx→PENDING_RECONCILE |
| RiskPlan 필수 | ADR 0106 + strategy `fixed_stop_loss_pct`·`take_profit_pct` | BUY 전 RiskPlan 없으면 fail-closed |
| KillSwitch 토글 | `api/.../service/KillSwitchService.kt` | `PUT /api/system-config/kill-switch` DB + Kafka `killswitch.engaged.v1` |
| 알림 | `consumer/.../notification/NotificationDispatcher.kt` L72-77 | order.filled.v1·order.rejected.v1 = **P0 즉시 Slack, REAL/MOCK/PAPER 공통** |
| 동의 | `api/.../controller/order/RealTradingConsentController.kt` | `PUT/GET /api/user-account/real-trading-consent` |

---

## 3. 코드 가드 갭 매트릭스 ★ 핵심

canary에 필요한 가드를 현행 코드 기준으로 "충분 / 신규 필요"로 판정한다.

| # | 필요 가드 | 현행 상태 (실측) | 판정 | 영역·예상 LOC |
|---|-----------|------------------|------|----------------|
| **GAP-1** | canary 주문 1건 **수량 hard cap** (전략·RiskConfig와 별개 전역 cap) | OrderRouter는 `quantity<=0`만 검사(L212). 상한 0. RiskConfig에도 절대 수량 cap 필드 없음(notional budget만) | **신규 필요** | consumer OrderRouter + ENV 바인딩. ~30 LOC |
| **GAP-2** | canary 주문 1건 **notional hard cap** (qty × price) | POSITION_LIMIT(RiskGateway)이 `unitCost > remainingBudget` 차단하나, 이는 사용자 RiskConfig budget 기반이지 canary 전역 절대 cap 아님 | **신규 필요** | consumer OrderRouter(price 필요 — limitPrice 또는 priceAtSignal). ~25 LOC |
| **GAP-3** | **일일 REAL 주문 횟수 전역 상한** | RiskConfig `maxTradesPerDay`(전략당, default 50)는 있으나 전역 REAL cap 아님. OrderRouter에 일일 REAL 카운트 게이트 없음 | **신규 필요** | consumer OrderRouter — `count order_request WHERE mode=REAL AND requested_at::date=today` 조회 + cap 비교. ~35 LOC (DB 조회 추가) |
| **GAP-4** | REAL 첫 주문 시 즉시 Slack | order.filled.v1·order.rejected.v1 모두 P0 즉시 Slack(REAL/MOCK 공통, NotificationDispatcher L72-77). REAL ACK(주문 접수) 단계 별도 알림은 미존재하나, 체결·거절은 즉시 도달 | **기존 자산으로 충분** | — (canary 중 ACK 가시성은 runbook §3-3 로그 관찰로 보완) |
| **GAP-5** | KillSwitch 즉시 중단 경로 | `PUT /api/system-config/kill-switch` + OrderRouter Step 4 재검증(fail-closed) + R3 runbook | **기존 자산으로 충분** | — |
| **GAP-6** | 이중 게이트 폐쇄(ALLOW_REAL=false) | TradingModeGateImpl 조건2 즉시 `RealGateDenied` | **기존 자산으로 충분** | — |
| **GAP-7** | 손실 한도 차단 | RiskGateway DAILY_LOSS_LIMIT(amount/percent) | **기존 자산으로 충분** (canary는 dailyLossLimitAmount 소액 설정) |
| **GAP-8** | 동시 보유 1종목 제한 | RiskGateway MAX_CONCURRENT_POSITIONS(`maxConcurrentPositions=1` 설정 시) | **기존 자산으로 충분** |
| **GAP-9** | 장운영시간 차단 | OrderRouter `isWithinTradingHours`(AUTO) + RealtimeSignalGenerator 게이트(PR #717) | **기존 자산으로 충분** |
| **GAP-10** | RiskPlan 필수 | ADR 0106 fail-closed | **기존 자산으로 충분** |
| **GAP-11** | KIS 체결-DB sync(REAL) | OrderRouter `ensureKisExecutionSyncIfReal` fail-closed | **기존 자산으로 충분** |

### 3-1. 판정 요약

- **신규 필요: 3건** — GAP-1(수량 cap)·GAP-2(notional cap)·GAP-3(일일 REAL 횟수 cap). 영역 = consumer OrderRouter + ENV. 합산 예상 ~90 LOC + 테스트.
- **기존 자산으로 충분: 8건** — KillSwitch·이중게이트·손실한도·동시보유·장운영시간·RiskPlan·KIS sync·TRADE 알림.

### 3-2. 신규 필요 3건의 본질

GAP-1·2·3는 모두 **"canary 전용 전역 hard cap"** 한 묶음이다. RiskConfig/POSITION_LIMIT는 전략·사용자 단위 정책 게이트이지, "canary 1회 동안 실수로라도 이 이상은 절대 안 나간다"는 운영 안전선이 아니다. canary의 본질이 "실수해도 손실이 작게 캡됨"이므로, 전략 설정 오류·budget 오산·다전략 누적을 OrderRouter 단에서 강제 차단하는 마지막 방어선이 필요하다.

**구현 위치 권고**: OrderRouter Step 3(게이트) 이후 ~ Step 5(INSERT) 이전에 `CanaryHardCapGate` 추가. ENV 미설정(또는 ≤0)이면 게이트 통과(일반 운용 — fail-open)하되, REAL 모드 + canary ENV 설정 시에만 활성. reject reason `CANARY_QTY_CAP`·`CANARY_NOTIONAL_CAP`·`CANARY_DAILY_ORDER_CAP`. audit event type 재사용(`INVALID_ORDER_INTENT`) 또는 신규 1건.

> 이 fail-open 설계의 risk(canary ENV 깜빡 미설정 → cap 미적용)는 runbook §1-3 P-3 사전 체크리스트로 보완한다. fail-closed로 가려면 "REAL 모드인데 canary ENV 없으면 거부"가 되나, 이는 일반 REAL 운용을 막으므로 canary 단계 한정 fail-open이 타당하다. 무인 운용 본격 진입 시 hard cap을 상시 운영 cap으로 승격하는 별 ADR 검토.

---

## 4. 자원 audit (ADR 0102 — 5단계 절차)

본 spec이 제안하는 신규 자원의 main 점유 현황.

| 자원 유형 | 신설 제안 | main 점유 audit (실측) | 충돌 |
|-----------|-----------|------------------------|------|
| **ENV** | `MAGICJAR_CANARY_MAX_ORDER_QTY`·`MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW`·`MAGICJAR_CANARY_MAX_ORDERS_PER_DAY` | `.env.example` grep `CANARY` = 0건. `MAGICJAR_MODE`·`MAGICJAR_ALLOW_REAL`만 존재(L6-7) | 충돌 0 (add-only) |
| **Flyway** | 없음 (GAP-3 일일 카운트는 기존 `order_request` 조회로 충분) | 최신 V63(`scenario_replay`). V64 미발행 | N/A — 신규 마이그레이션 불요 |
| **Kafka** | 없음 | order.intent/requested/rejected/filled 기존 토픽 재사용 | 충돌 0 |
| **Redis** | 없음 (GAP-3 카운트는 DB 권위 — Redis 캐시 불요) | — | 충돌 0 |
| **STOMP** | 없음 | — | 충돌 0 |
| **REST** | 없음 (KillSwitch·consent·system-config 기존 엔드포인트 재사용) | `PUT /api/system-config/kill-switch`·`/api/user-account/real-trading-consent` 존재 | 충돌 0 |

→ 신규 필요 가드(GAP-1·2·3)는 **ENV 3건 add-only + consumer 코드만**. Flyway·Kafka·Redis·STOMP·REST 신설 0. 구현 후속 dispatch 시 자원 충돌 위험 0.

---

## 5. ADR 발행 결정

canary hard cap ENV 신설은 **새 정책 결정**(기존 ADR 0043·0095·0096·0106 조합으로 커버되지 않는 신규 ENV 게이트)이므로 ADR 0109로 발행한다(다음 빈 번호 — 최신 0108 확인). runbook 자체는 기존 ADR 조합 정합이나, hard cap ENV의 존재·이름·fail-open 의미는 ADR로 LOCK해야 후속 구현 dispatch와 사용자 결재 기준이 명확하다.

---

## 6. 후속 dispatch 권고 (orchestrator)

1. **backend-core** — `CanaryHardCapGate` 구현 (GAP-1·2·3, consumer OrderRouter, ENV 3건, ~90 LOC + 단위 테스트). ADR 0109 정합.
2. **사용자 결재** — canary 실행 일정·파라미터 LOCK 값(수량·notional·일일횟수·손실한도) 확정.
3. **qa** — canary 흐름 시뮬레이션(MOCK staging)에서 hard cap reject 경로 1회 재현(Visual/E2E 선택).

---

## 7. 트레이드오프

| 결정 | 채택 | 거부 대안 / 사유 |
|------|------|------------------|
| hard cap을 canary 전용 ENV로 | 기존 RiskConfig와 독립 — 운영 cap 오염 0 + canary 종료 후 비활성 용이 | RiskConfig 필드 추가 → Flyway + 도메인 변경 + 상시 운용에도 영향. canary 1회 목적엔 과함 |
| fail-open(ENV 없으면 통과) | 일반 REAL 운용 차단 회피 + runbook 체크리스트 보완 | fail-closed → REAL 진입 자체를 canary ENV에 묶음. 무인 운용 본격화 후 별 ADR로 승격 |
| GAP-3 카운트 DB 조회 | order_request mode=REAL 당일 count — 단일 권위 | Redis 카운터 → DB와 drift risk + 복잡도 ↑ |
| 신규 가드 구현 본 task 외 | spec 사이클 = md only(개발 룰 정합) | 본 PR에 구현 동반 → 한 PR 2영역(md+code) 위반 |
