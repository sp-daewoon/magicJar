# ADR 0109 — REAL canary hard cap ENV 게이트

- 상태: **Accepted (2026-06-05)** · **amended (2026-06-12 — §7 cap 의미 정합: 매수만 카운트·SELL cap 면제·매도 MARKET fallback)**
- 작성자: architect
- 결정자: 사용자(최고 관리자) — canary 실행 결재와 동반 / amend(§7)도 라이브 incident 중 사용자 직접 결재
- 영역: 운영 안전 게이트 (자본 path) + ENV 정책
- 관계:
  - runbook: `docs/operations/real-canary-runbook.md`
  - spec: `_workspace/specs/2026-06-05-real-canary-spec.md`
  - ADR 0043(KillSwitch) · ADR 0067(Risk Gateway) · ADR 0068(Order Execution 3중 게이트) · ADR 0095(SLO·alert) · ADR 0096(DR/backup) · ADR 0106(RiskPlan 필수) · ADR 0102(자원 audit)
  - amend(§7) 관계: ADR 0110(전략별 포지션 귀속 — SELL 보유량이 자연 상한인 근거) · ADR 0111(RiskPlan scale-in/exit) · ADR 0114 §3-1-5(진입 MARKET fallback 금지) + §1-2(StopLoss=MARKET — 청산 체결 우선)
  - readiness: `docs/assessments/2026-06-05-autotrading-readiness.html` (REAL canary +5 P1 게이트)
  - 구현: 매수만 카운트 = PR #927(2026-06-12, backend-core) · SELL cap 전면 면제 = hotfix F8(병렬) · 매도 MARKET fallback = hotfix F8(병렬)

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
| **(amend §7) cap을 BUY 방향에만 적용** | 청산(SELL) 봉쇄로 손실 키우는 자본 보호 역전 차단 + 숏 부재로 매도는 보유량 자연 상한 | 방향 무구분 cap → 익절·손절·DSL 청산까지 거부(11:32·13:11 incident) |
| **(amend §7) 일일 cap = 매수만·왕복 1=1** | "사고팔면 1번" 사용자 결재 + REJECTED 미카운트로 슬롯 잠식 차단 | 매수+매도+취소+거부 전부 집계 → 5번째에 청산 불가(`6>=5`) |
| **(amend §7) SELL 가격 미가용 시 MARKET fallback** | 청산은 체결 확실성이 본질(ADR 0114 §1-2) | SELL도 진입처럼 스킵 → `PRICE_UNAVAILABLE`로 청산 차단(13:31 incident) |

## 6. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-06-05 | **ADR 0109 Accepted** — REAL canary 전용 hard cap ENV 3건(`MAGICJAR_CANARY_MAX_ORDER_QTY`·`_MAX_ORDER_NOTIONAL_KRW`·`_MAX_ORDERS_PER_DAY`) 신설. REAL 모드 + ENV>0 활성 / fail-open. OrderRouter CanaryHardCapGate 후속 구현. runbook + spec 동반. 운영 코드 0 touch. | architect (readiness REAL canary P1 게이트 정합) |
| 2026-06-12 | **§7 amend** — cap 의미 정합 3건(라이브 incident 11:32·13:11·13:31 청산 봉쇄 역전). ① `MAX_ORDERS_PER_DAY` = 당일 **매수(BUY)만, 왕복 1회=1카운트**, REJECTED 미카운트(PR #927). ② QTY·NOTIONAL cap **SELL 전면 면제** — 숏 부재로 매도는 보유량 자연 상한(hotfix F8). ③ 매도 가격 산정 실패 시 **MARKET fallback** — 청산 체결 보장 우선, ADR 0114 §3-1-5(진입 fallback 금지)와 방향 구분 명문화(hotfix F8). ENV 이름 변경은 라이브 호환 보류 — 의미 권위는 본 amend. 문서 0 touch 코드(구현은 별 PR). | 사용자(최고 관리자) 직접 결재 · architect 정합 |

## 7. amend (2026-06-12) — cap 의미 정합: 매수만 카운트 · SELL cap 면제 · 매도 MARKET fallback

### 7-0. 배경 — 라이브 incident에서 cap의 자본 보호 역전

2026-06-12 REAL canary 가동 중, §2의 hard cap 3종이 **방향(매수/매도)을 구분하지 않아** "손실을 작게 캡한다"는 §1 본질을 정면으로 뒤집는 사고가 연쇄로 발생했다.

| 시각 | 사건 | 거부 reason |
|------|------|------------|
| 11:32 | 익절 LIMIT 발행 | `CANARY_DAILY_ORDER_CAP:5>=5` 거부 → 보유 포지션 청산 전면 차단 |
| 13:11 | DSL 청산 발행 | `CANARY_DAILY_ORDER_CAP:6>=5` 거부 |
| 13:31 | 수동 시장가 SELL | `CANARY_NOTIONAL_CAP:PRICE_UNAVAILABLE` 거부 (호가/현재가 조회 실패로 notional 산정 불가) |

cap이 **이미 산 포지션의 청산(익절·손절·DSL)을 막아** 손실을 키우는 쪽으로 작동했다. canary cap은 "신규 자본 노출 상한"이지 "보유 자본의 출구 봉쇄"가 아니다. 매수와 매도를 같은 게이트로 취급한 §2가 근본 결함이었다.

### 7-1. 결정 (최고 관리자 2026-06-12 직접 결재 — 문구 인용)

**① `MAX_ORDERS_PER_DAY` 카운트 의미 = "당일 매수(BUY)만, 왕복 1회 = 1카운트"**

> "하루 주문 제한을 매수만 포함해야지 매도까지 세려고 만든 건 아니야. 5개 제한이면 1개 종목을 사고팔면 1번 카운팅"

- §2-4 "당일 REAL 주문 횟수"의 카운트 대상을 **`side = BUY`로 한정**한다. 1종목 매수→매도 왕복은 1카운트(매수 시점에만 슬롯 소비).
- **REJECTED 미카운트**: 게이트 거부 BUY도 `status = REJECTED` row로 INSERT(handleRejected)되므로, "체결 경로에 실제 들어간 BUY"만 집계한다(`status <> REJECTED`). 거부 주문이 cap 슬롯을 잠식해 역전이 재발하는 것을 차단.
- 카운트 권위는 §2-4대로 `order_request` DB 단일 조회 유지(Redis 미사용 — drift 회피). side·status 필터는 `@Query` 리터럴 add-only — caller·port 시그니처 불변.
- 구현 = **PR #927**(2026-06-12, backend-core): `ConsumerOrderRequestJpaRepository.countRealOrdersBetween` + api 사본 `@Query`에 `side = OrderSide.BUY` + `status <> OrderStatus.REJECTED` add-only. 한도 숫자(5)·QTY cap·NOTIONAL cap 변경 0.

**② QTY cap · NOTIONAL cap의 SELL 전면 면제 — 매도는 보유량이 자연 상한**

> "매도나 익절 손절일 때는 이 조건을 보면 안 돼... 매도는 신호 나오면 팔면 끝"

- `MAGICJAR_CANARY_MAX_ORDER_QTY`·`MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW` 게이트는 **`side = BUY`에만 적용**, SELL은 전면 통과한다.
- 근거: magicJar는 **숏(공매도) 부재** — 매도는 ADR 0110 전략별 lot ledger(보유량)가 자연 상한이다. 보유분을 초과해 팔 수 없으므로 매도에 절대 cap을 거는 것은 무의미하고, 오히려 청산 봉쇄(7-0)만 유발한다.
- 매수 cap 도달 후에도 보유 포지션의 익절·손절·DSL 청산은 항상 통과 — ADR 0114 §3-2(청산 "항상" 보장)와 정합.
- 구현 = **hotfix F8**(병렬): CanaryHardCapGate의 QTY·NOTIONAL 검사에 `side == BUY` 가드 add-only.

**③ 매도 가격 산정 실패 시 MARKET fallback — 매도 방향은 체결 보장 우선**

> "조회 실패하면 그냥 시장가로 던져버리는 거지"

- 13:31 `PRICE_UNAVAILABLE` 거부(notional 산정용 가격 조회 실패로 SELL 자체가 막힘)를 차단한다. **SELL은 가격 조회 실패 시 MARKET으로 fallback**해 체결을 보장한다.
- **방향 구분 명문화**: 이는 ADR 0114 §3-1-5의 **진입(BUY) MARKET fallback 금지**(슬리피지 캡 자기 부정 회피)와 **상충하지 않는다**. 진입은 슬리피지 보호가 본질이라 호가 미가용 시 스킵(`SIGNAL_SKIPPED_NO_QUOTE`)이 옳지만, 청산은 ADR 0114 §1-2(StopLoss=MARKET)·§3-2(청산 항상 보장)대로 **체결 확실성이 본질**이므로 가격 미가용 시 MARKET이 옳다. "BUY = 가격 보호 우선 / SELL = 체결 보장 우선"이 magicJar 자본 path의 방향별 권위다.
- 구현 = **hotfix F8**(병렬): SELL 경로에서 notional 산정용 가격 조회 실패 시 `OrderPriceType.MARKET` fallback. (SELL은 ②로 NOTIONAL cap 자체가 면제되므로, fallback의 목적은 cap 통과가 아니라 가격 미확정 SELL의 체결 경로 진입 보장.)

### 7-2. ENV 이름과 의미 불일치 처리

`MAGICJAR_CANARY_MAX_ORDERS_PER_DAY`의 이름은 "당일 전체 주문"을 암시하나, 본 amend로 의미는 **"당일 매수(BUY) 횟수"**로 확정됐다. **ENV 이름 변경은 라이브 호환(이미 `.env`·runbook·docker 배선에 박힌 키) 고려로 보류**한다. 의미의 권위는 본 amend §7-1-①이며, runbook·`.env.example` 주석에 "BUY 한정 카운트" 명기로 혼동을 보완한다. 이름 리네이밍은 canary 종료 후 상시 cap 승격(§3 마지막 줄) 검토 시 함께 다룬다.

### 7-3. 게이트 방향별 적용 매트릭스 (amend 후 권위)

| 게이트 / ENV | BUY 적용 | SELL 적용 | 비고 |
|--------------|:-------:|:--------:|------|
| `MAGICJAR_CANARY_MAX_ORDER_QTY` (`CANARY_QTY_CAP`) | ✅ | ❌ 면제 | SELL = 보유량 자연 상한(ADR 0110) |
| `MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW` (`CANARY_NOTIONAL_CAP`) | ✅ | ❌ 면제 | SELL 가격 미가용 시 MARKET fallback(7-1-③) |
| `MAGICJAR_CANARY_MAX_ORDERS_PER_DAY` (`CANARY_DAILY_ORDER_CAP`) | ✅ (REJECTED 제외) | ❌ 미카운트 | 왕복 1회 = 1카운트(매수 시점만 소비) |

### 7-4. rollback (amend)

본 amend는 문서 정합 — 운영 코드 0 touch. 구현은 PR #927 + hotfix F8(별도)이며 각각 코드 revert 단독으로 되돌아간다(Flyway·Kafka·Redis·STOMP·ENV 신설 0 — `@Query` 리터럴·`side==BUY` 가드 add-only). revert 시 §2 원안(방향 무구분 cap)으로 복귀하나, 그 경우 7-0 청산 봉쇄 역전이 재발하므로 revert는 권장하지 않는다.
