# ADR 0114 — 주문 집행 정책(marketable limit) + 포지션 청산 "항상" 보장

- 상태: **Proposed (2026-06-07)** — 별도 브랜치 전용. Codex 병렬 개발 후 우열 비교 머지 예정이라 머지 전 Accepted 금지(브랜치 머지 시점에 architect가 Accepted 승격).
- 작성자: architect
- 결정자: 사용자(최고 관리자) — "시장가로만 사고 있지 않나? + 포지션 보유 중 시스템이 긴장 상태인가?" 2질문 + §3 룰·§5 P1~P4 결재 완료
- 영역: 주문 집행(진입 가격 정책·미체결 취소·손절/익절 가격 유형) + 자동매매 청산 "항상" 보장(readiness·전략 상태·구독·시간대 게이트 분화) — **자본 path (사용자 결재)**
- 입력 권위: `docs/assessments/2026-06-07-order-execution-policy-and-position-vigilance.html` (코드 전수 인용 + KIS open-trading-api 실측 평가)
- 관계:
  - **ADR 0111 amend 아님** — 본 ADR은 진입/청산의 **가격·집행·게이트 정책**(0111은 수량·평단 exit 모델). 0111 `ScaleInExitCalculator`(평단 stop/take 평가) 출력을 본 ADR EntryPricePolicy(진입 가격 산정)·게이트 분화가 감싼다.
  - ADR 0067(T-RISK — Signal→RiskGateway→OrderIntent 의무 게이트 + `BUY_BLOCKED`)·ADR 0068(T-O — 주문 실행 + KIS REST/WS 체결 추적)
  - ADR 0105(캔들 reconciliation + data readiness gate — P1 분화 대상)
  - ADR 0106(RiskPlan 필수화 — 본 ADR 진입 가격 산정의 선행 게이트)
  - ADR 0109(REAL canary hard cap — 진입 스킵·재시도 0회와 상호작용)
  - ADR 0110(전략별 포지션 귀속 `strategy_position` — P2·P3 position 기반 폴백·HOLDING 등록의 토대)
  - ADR 0102(spec 발행 시 main 영속 자원 audit 5단계 — §10 audit 의무)
  - ADR 0048(호가 구독 라우터 H0STASP0 LRU)·ADR 0028/0029(Ingestion·Redis current candle)

---

## §1. 맥락

### 1-1. 두 질문의 코드 실태 (assessment evidence)

사용자 질문 ① "자동매매가 시장가로만 사고 있지 않나?" — **사실**이다. `OrderRouterKafkaListener.kt:948-952`에서 ADR 0067 publisher가 `priceType`을 미발행하므로 default가 채워진다:

```kotlin
// OrderRouterKafkaListener.kt:949-952
val priceTypeName = payload["priceType"]?.toString()
val priceType =
    priceTypeName?.let { name -> OrderPriceType.values().firstOrNull { it.name == name } }
        ?: OrderPriceType.MARKET   // BUY·SELL 공통 default
```

즉 시그널 경로의 가격 결정 정책이 부재해 진입·청산 전부 **시장가 고정**이고 슬리피지·스프레드 보호가 0이다. 반면 `OrderPriceType(MARKET/LIMIT)` + `limitPrice` 배선은 도메인→Kafka(`OrderRouterKafkaListener.kt:489·491·716·718`)→KIS adapter까지 **전 구간 이미 완성**되어 있다(canary notional cap도 `intent.priceType` 분기 — `OrderRouterKafkaListener.kt:654-656`). 빠진 것은 시그널 단계의 가격 산정뿐이다.

사용자 질문 ② "포지션 보유 중 시스템이 긴장 상태인가?" — 청산 평가는 **backend(consumer) 독립**으로 브라우저가 닫혀도 매분 동작하나(견고), "항상"의 보장에 게이트 4중 의존이 있어 구멍이 있다. `RealtimeSignalGenerator.onBoundaryClosed()`의 실제 게이트 순서는:

```
strategyLookup.findActiveByCodeAndInterval(code, interval)   // ① 전략 조회 — ACTIVE·BUY_BLOCKED만
  → if (activeStrategies.isEmpty()) return                    //    (RealtimeSignalGenerator.kt:124-132)
  → market-hours gate (정규장 아니면 return)                  // ② RealtimeSignalGenerator.kt:136-143
  → data readiness gate (!ready 면 return)                    // ③ RealtimeSignalGenerator.kt:145-161
  → ScaleInExitCalculator.evaluate() → StopLoss/PartialTP/TakeProfit
```

`ConsumerStrategyJpaRepository.findActiveByCodeAndInterval`은 `status IN ('ACTIVE','BUY_BLOCKED')` + `codes @> :codeJson` + `intervals @> :intervalJson`로 필터하므로 **DISABLED 전략은 조회 제외**된다. 그 결과 게이트 어느 하나라도 걸리면 보유 포지션의 손절 평가 자체가 skip된다.

### 1-2. KIS 국내주식 OpenAPI에 서버 예약형 스탑로스 없음 (구조적 함의 — KIS-first raw fetch 정합)

assessment §2-2가 KIS open-trading-api 공식 스펙으로 확정한 결과:

| 후보 | 실체 | 가격 트리거 스탑로스? |
|---|---|---|
| 주식예약주문 `CTSC0008U` | **시간 예약** — 15:40~익영업일 07:30 접수, 장 시작 시 전송(기간예약 최대 30일) | ❌ 가격 조건 트리거 아님 |
| 조건부지정가 `ORD_DVSN=02` | 장중 지정가 → 15:20 시장가 전환(KRX 호가 유형) | ❌ |
| HTS/MTS 자동감시주문 | 단말 전용 | ❌ OpenAPI 미노출 |

→ **서버에 스탑로스를 맡길 방법이 없다.** magicJar(consumer) 자체가 스탑로스 장치이고, consumer가 멈추면 손절도 멈춘다. 따라서 §4 게이트 분화(청산 "항상" 보장)는 부가 기능이 아니라 자본 안전의 본체다.

### 1-3. 본 사이클의 위치

본 ADR은 R1~R3 사이클의 **설계 본체**다. §3 룰(marketable limit·스프레드 게이트·추격 금지)과 §5 P1~P5(게이트 분화·HOLDING owner)는 ADR 0111(수량/평단 exit 모델)을 깨지 않고 그 **앞단(진입 가격)·바깥(게이트)**을 보강한다. P1·P2는 룰 변경과 무관하게 선행 가능한 안전 결함이다(assessment §6).

---

## §2. 대안 비교 (사용자 결재분 — 채택안 LOCK)

### 2-1. 진입 가격 정책

| 항목 | orchestrator 초안 | 채택안(사용자 추천 룰) | 채택 사유 |
|---|---|---|---|
| 진입 가격 | 지정가(기준 모호) | **Marketable limit** — 매도1호가에 걸되 상한(`기준가 × (1+maxEntrySlippctPct)`) 초과면 스킵 | 체결 노리되 상한 보호. 소극적 "1호가-밑 지정가"의 미체결 추격 문제 해소 |
| 진입 실패 | 타임아웃 시 시장가 전환 옵션 | **취소/스킵 — 따라가지 않음** | 시장가 전환은 슬리피지 캡 자기 부정 |
| 스프레드 게이트 | 없음(누락) | **maxSpreadPct(0.3%) 초과 진입 금지** | 유동성 경고 필터 — 초안 결함 |
| 손절 | 시장가 | **시장가 유지** | 체결 확실성 > 가격(KIS에 스탑지정가 없음 §1-2) |
| 익절(PartialExit 포함) | 지정가 검토 | **지정가** | 익절은 가격 보호 우위 |
| 부분체결 | 누락 | **체결 수량만 포지션 인정** | 현행 코드도 수량 정확(`executedQuantity.coerceAtLeast(0)`) — 정책 명문화 |
| 설정화 | 없음 | 전략별 `maxEntrySlippctPct`·`entryTimeoutSec`·`maxSpreadPct` | RiskPlan JSONB(ADR 0111) 패턴 정합 |

### 2-2. 청산 "항상" 보장 — P1~P5 게이트 분화

| 순위 | 위험(assessment §4·§5) | evidence | 채택 복구안 |
|---|---|---|---|
| **P1** | readiness fail-closed가 **손절까지 skip** | `RealtimeSignalGenerator.kt:145-161` — 진입/청산 구분 없이 `!ready` 면 일괄 return | 게이트를 진입/청산 **분화** — 보유 포지션 SELL 평가는 readiness 미충족이어도 **진행**(상태별 허용 매트릭스 §4-1). fail-closed는 "신규 자본 투입(BUY) 차단"에만 |
| **P2** | DISABLED 전략의 보유 포지션 **방치** | `ConsumerStrategyJpaRepository.findActiveByCodeAndInterval` — `status IN ('ACTIVE','BUY_BLOCKED')`만 조회 → DISABLED 전략 포지션은 청산 평가 대상에서 사라짐 | 전략 상태와 무관하게 **strategy_position 보유 종목은 무조건 청산 평가 대상**(§4-2 position 기반 폴백). BUY만 상태 게이트 |
| **P3** | HOLDING owner 부재 → 보유 종목 **evict 가능** | `RealtimeSubscriptionRouter.kt:231-244` — enum에 DETAIL·STRATEGY만. `findEvictionCandidateLocked`(168-184)는 position 미고려 | owner enum에 **`HOLDING` 신설**(최우선·evict 금지). 체결 시 자동 등록/전량 청산 시 해제 |
| **P4** | NXT 시간대 손절 **차단** | `RealtimeSignalGenerator.kt:136-143` — market-hours gate가 BUY/SELL 미분화 | gate를 **BUY 한정**으로 분리(`sell-enabled` env). NXT 유동성 리스크 명기 — default 보수적 true 유지 |
| **P5** | 폴링 fallback **1d 한정** | `PollingWorker.kt:76` — 5초 REST 폴링이 1d봉만 fetch → 1m 청산 재료 안 됨 | 보유 종목 한정 1m 폴링 경로(HOLDING owner와 함께) — **post-MVP 슬롯**(§9) |

### 2-3. 권장 시작 기본값 (보수 시작 → 체결률 데이터로 완화)

| 파라미터 | 기본값 | 비고 |
|---|---|---|
| 진입 | marketable limit (매도1호가, 상한 = `기준가 × (1+slippage)`) | 상한 초과 시 스킵 |
| `maxEntrySlippctPct` | **0.2** | 0.2~0.3 중 보수쪽 |
| `entryTimeoutSec` | **15** | 미체결 시 취소 — 시장가 전환 없음 |
| 재시도 | **0회** | 허용 시에도 기준가 = 원 신호 가격(현재가 갱신 = 추격 금지) |
| `maxSpreadPct` | **0.3** | 초과 시 진입 금지 + 호가 잔량 약하면 스킵 |
| 손절 / 익절 | 시장가 / 지정가 | — |

---

## §3. 결정 — 진입 가격 정책

### 3-1. 진입 = marketable limit (LIMIT 발행, 시장가 미사용)

BUY 시그널이 RiskGateway ALLOWED를 통과하면 OrderIntent에 `priceType=LIMIT` + `limitPrice`를 산정해 발행한다. 산정 규칙(EntryPricePolicy §6):

1. **상한** `cap = priceAtSignal × (1 + maxEntrySlippctPct/100)`.
2. 매도1호가 `ask1 ≤ cap` 이면 `limitPrice = ask1`(상한 이내 marketable limit — 매도1호가에 걸어 즉시 체결 의도).
3. `ask1 > cap` 이면 **진입 스킵**(`SIGNAL_SKIPPED_ENTRY_PRICE_CAP` audit) — 시장가 미발행.
4. **스프레드 게이트**: `(ask1 - bid1) / mid × 100 > maxSpreadPct` 이면 진입 스킵(`SIGNAL_SKIPPED_SPREAD`). 호가 잔량이 약하면(임계 — R2 결정) 동일 스킵.
5. 호가 미가용(호가 source 부재·stale)이면 진입 스킵(`SIGNAL_SKIPPED_NO_QUOTE`) — **시장가 fallback 금지**(슬리피지 캡 자기 부정 회피).

DISABLED는 BUY만 차단되므로 본 진입 경로는 ACTIVE 전략에서만 발화한다(SELL은 §4-2).

### 3-2. 진입 미체결 = entryTimeoutSec 후 취소 (시장가 전환 금지)

LIMIT 주문 후 `entryTimeoutSec`(default 15초) 내 미체결이면 **취소**한다(`ApiKisOrderCancelAdapter.cancel` 기존 존재 — `ApiKisOrderCancelAdapter.kt:47·59` `TTTC0013U`/`VTTC0013U`). 시장가 전환 금지. 재시도 기본 0회. 재시도 허용 시에도 기준가는 **원 신호 가격 유지**(현재가 재산정 = 추격이므로 금지). 미체결 취소·재시도 워커의 위치·트리거는 R2가 결정(§9).

### 3-3. 손절 = 시장가 / 익절(PartialExit 포함) = 지정가

`ScaleInExitCalculator.evaluate()`(ADR 0111) 출력별 가격 유형:

| exit decision | priceType | limitPrice 기준 |
|---|---|---|
| `StopLoss` | **MARKET** | — (체결 확실성 우선 — KIS에 스탑지정가 없음 §1-2) |
| `PartialTakeProfit` | **LIMIT** | **트리거 가격** = `평단 × (1 + triggerProfitPct/100)` |
| `TakeProfit` | **LIMIT** | **트리거 가격** = `평단 × (1 + takeProfitPct/100)` |

익절 지정가 기준은 **트리거 가격**(매수1호가가 아니라 익절 발화 조건 가격)으로 LOCK한다 — 평단 기준 결정론(백테스트/리플레이 재현성)을 위해 ScaleInExitCalculator가 내부적으로 산출 가능한 값을 쓴다. 익절 LIMIT 미체결 처리(취소·시장가 전환 여부)는 R2 결정 의제(보수 default: 미체결 유지 — 손절 게이트가 하한 보호).

### 3-4. 부분체결 = 체결 수량만 포지션 인정

현행 체결통보→`position`/`strategy_position` 동일 tx upsert가 `executedQuantity.coerceAtLeast(0)`로 이미 수량 정확(assessment §4-8). 본 ADR은 이를 **정책으로 명문화**할 뿐 코드 변경 0(R2는 회귀 테스트로 보강).

---

## §4. 결정 — 청산 "항상" 보장 (게이트 분화)

### 4-1. P1 — data readiness 게이트 진입/청산 분화

ADR 0105 §3-6 readiness 4 status(`READY`/`RECONCILING`/`STALE`/`FAILED`)에 대해, **BUY(신규 자본 투입)는 현행 fail-closed 유지**(READY만 허용), **보유 포지션 SELL 평가는 status별 허용**한다:

| status | BUY(진입) | SELL(보유 청산 평가) | 사유 |
|---|---|---|---|
| `READY` | 허용 | 허용 | 현행 |
| `RECONCILING` | skip(fail-closed) | **진행** | gap fill 중이어도 손절 도달 시 평가해야 손실 확대 차단. 현재가는 Redis current candle(`candle:current` ADR 0029)·tick으로 평가 가능 |
| `STALE` | skip | **진행(권장안)** | 마지막 candle stale이라도 보유분 손절은 평가 — 단 평가에 쓰는 현재가는 최신 tick/quote 우선(stale aggregate 아님) |
| `FAILED` | skip | **조건부 진행** — 현재가(tick/quote) 가용하면 SELL 평가 진행, 가용 0이면 skip + audit/alert(KillSwitch 검토) | KIS fetch 반복 실패라도 현재가가 있으면 손절은 평가. 현재가조차 없으면 평가 불가(데이터 공백) |

**대안 비교**:

| 대안 | 채택 | 거부 사유 |
|---|---|---|
| (채택) SELL은 RECONCILING/STALE 무조건 진행 + FAILED는 현재가 가용 시 진행 | ✅ | 손절 "항상" 보장 본체 — readiness는 1m **확정 캔들** 정합 신호일 뿐, 청산 트리거 현재가는 tick/Redis current로 별도 확보 가능 |
| SELL도 STALE까지만 허용(FAILED는 skip) | ✗(보조) | FAILED라도 tick이 살아 있으면 손절 가능한데 막음 — 자본 보호 공백. 단 alert는 양쪽 다 발행 |
| 현행 유지(진입/청산 동일 fail-closed) | ✗ | **P1 Critical** — 손절가 도달해도 평가 skip → 손실 확대 |

핵심 불변식: **fail-closed는 "신규 자본 투입(BUY) 차단"에만 적용한다. 이미 투입된 자본의 회수(SELL)는 막지 않는다.** readiness가 아니라 "청산 트리거에 쓸 현재가가 있는가"가 SELL 평가의 진짜 게이트다.

### 4-2. P2 — 전략 상태 무관 청산 평가 (position 기반)

`strategy_position` 보유 종목(quantity>0)은 **전략 status와 무관하게** 청산 평가 대상이다. BUY만 status 게이트(`ACTIVE`만 진입 — `BUY_BLOCKED`는 ADR 0067 BUY 차단, `DISABLED`도 BUY 0).

**대안 비교**:

| 대안 | 설명 | 판정 |
|---|---|---|
| **A. position-driven 폴백(권장)** | 청산 평가의 진입점을 "전략 조회"가 아니라 "보유 strategy_position 조회"로 보강. position이 있으면 그 strategy의 exit 설정(stop/take/partialExits)을 status 무관하게 로드해 ScaleInExitCalculator 평가. BUY는 별도로 ACTIVE 전략 조회 유지 | ✅ **권장** — position이 청산 평가의 1차 권위(ADR 0110 strategy_position 토대). DISABLED여도 보유분은 무조건 청산 |
| B. DISABLED를 SELL-only로 조회 포함 | `findActiveByCodeAndInterval`에 `DISABLED`를 SELL 평가용으로 추가 + 코드 분기에서 BUY suppress | ✗ 보조 — 전략 status에 청산 책임을 계속 결합(전략이 DELETED/누락이면 또 공백). position-driven이 더 견고 |

권장안 A는 ADR 0110 `StrategyPositionPort`(전략별 lot ledger)를 청산 평가 진입점으로 승격한다 — "전략이 살아 있어야 청산된다"가 아니라 "포지션이 있으면 청산된다"로 불변식 전환. 전략 정의(exit 설정) 자체가 삭제된 극단 케이스는 R2가 fallback(전역 default stop 또는 KillSwitch alert) 결정.

### 4-3. P3 — RealtimeDemandOwner에 `HOLDING` 신설

`RealtimeSubscriptionRouter.kt:231-244` enum에 `HOLDING`을 추가하고 **최우선 priority(evict 절대 금지)**로 둔다:

| owner | priority(낮을수록 우선) | evict |
|---|---|---|
| **`HOLDING`(신규)** | **0(최우선)** | **절대 금지** |
| `DETAIL` | 10 | 가능 |
| `STRATEGY` | 40 | 가능 |

- **등록**: 체결로 `strategy_position`/`position`이 생기면(quantity>0) 해당 code를 HOLDING owner로 자동 등록 → KIS WS 구독 보장(tick·1m 캔들 생성 → 청산 평가 재료).
- **해제**: 전량 청산(quantity=0)되면 HOLDING demand 해제(grace TTL은 R2 결정).
- **evict 불변식**: `findEvictionCandidateLocked`(`RealtimeSubscriptionRouter.kt:168-184`)는 HOLDING owner code를 eviction 후보에서 **무조건 제외**. 41 slot 경합에서도 보유 종목은 끊기지 않는다.

호가(H0STASP0) 구독도 HOLDING 종목은 보장 대상(진입은 끝났지만 익절 LIMIT 산정엔 호가 불요 — 트리거 가격 기준이므로 SELL은 호가 의존 0. 진입만 호가 필요 §3-1). 호가 LRU(ADR 0048)는 진입 후보(STRATEGY)·DETAIL 위주 유지.

### 4-4. P4 — market-hours gate BUY 한정 분리

`RealtimeSignalGenerator.kt:136-143` market-hours gate를 BUY 한정으로 분리한다:

- 신규 env `magicjar.signal.market-hours-gate.sell-enabled` (default **true** — 보수 유지).
- `sell-enabled=true`(default): 현행과 동일(SELL도 정규장만). `false`: NXT 시간대(08~09시·15:30~20시) 보유 포지션 SELL 평가 허용.
- **NXT 유동성 리스크 명기**: NXT 시간대는 호가 얇음 → 손절 시장가가 큰 슬리피지 위험. 따라서 default를 즉시 false로 두지 않고 **사용자가 NXT 유동성·종목 특성 보고 전환**하도록 env로 노출(assessment §5 P4 "사용자 결정 필요").
- BUY는 항상 market-hours gate 적용(NXT 진입 금지 유지).

### 4-5. P5 — 보유 종목 1m 폴링 fallback (post-MVP)

`PollingWorker.kt:76`의 1d 한정 폴링은 WS 장애 + evict 동시 발생 시 1m 청산 재료 공백. HOLDING owner가 evict를 막으므로 P5 우선도는 낮아진다(P3로 대부분 cover). 보유 종목 한정 1m 폴링 경로는 **§9 post-MVP 슬롯**(HOLDING owner와 함께 설계).

---

## §5. RiskPlan 설정 3종 (JSONB add-only · 하위호환)

ADR 0111 RiskPlan/StrategyDefinition JSONB 패턴 정합으로 entry 정책 3 필드를 add-only 추가한다. 미설정(null)이면 **현행 동작**(= 전역 default 적용)이라 기존 전략 0 변경(하위호환 LOCK):

| 필드 | 타입 | default(null 시 전역) | 의미 |
|---|---|---|---|
| `maxEntrySlippctPct` | `BigDecimal?` | 0.2 | 진입 상한 = 기준가 × (1+이값/100) |
| `entryTimeoutSec` | `Int?` | 15 | LIMIT 미체결 취소 타임아웃 |
| `maxSpreadPct` | `BigDecimal?` | 0.3 | 스프레드 초과 시 진입 스킵 |

전역 default는 env(`magicjar.order.entry.*`)로 주입 → 전략별 null이면 전역, 전략별 설정 시 override(ADR 0111 RiskPlan override 패턴). 저장 위치는 StrategyDefinition의 진입 정책 슬롯(R1이 `EntryPolicy` 묶음 객체 vs RiskPlan 평면 필드 중 결정 — §9). **JSONB add-only이므로 신규 Flyway 마이그레이션 불요 가능성 높음**(§10 audit 결론).

---

## §6. EntryPricePolicy 순수 계산기 (domain — ScaleInExitCalculator 선례)

ADR 0111 §2-4 `ScaleInExitCalculator`(strategy-engine, 순수 함수)와 동일 패턴으로 **진입 가격 산정을 순수 계산기로 추출**한다. live(consumer)·백테스트(ADR 0056 슬리피지 모델)·리플레이(ADR 0107)가 단일 함수를 호출 → "리플레이에선 스킵됐는데 라이브는 진입" 의심 원천 차단.

위치: `domain` 또는 `strategy-engine`(ScaleInExitCalculator와 동일 모듈 권장 — R1 결정). Spring·JPA·IO 의존 0, 입력→출력 결정론.

### 6-1. 계약 시그니처 초안 (R1 확정 대상 — LOCK 아님)

```kotlin
// EntryPriceInput — 호출자가 호가·기준가·설정을 주입(함수 내부 조회 0)
data class EntryPriceInput(
    val priceAtSignal: BigDecimal,        // 원 신호 기준가(추격 금지 — 재시도도 이 값 고정)
    val ask1: BigDecimal?,                // 매도1호가(없으면 진입 스킵)
    val bid1: BigDecimal?,                // 매수1호가(스프레드 계산용)
    val askQty1: Long?,                   // 매도1호가 잔량(약하면 스킵 — 임계 R2)
    val maxEntrySlippctPct: BigDecimal,   // 전략 override 또는 전역 default(0.2)
    val maxSpreadPct: BigDecimal,         // 전략 override 또는 전역 default(0.3)
    val quoteAsOf: Instant?,              // 호가 신선도(stale 판정 — 임계 R2)
    val evalAt: Instant,                  // 결정론 위해 시각 주입
)

// EntryPriceDecision — sealed (스킵 사유 audit 코드 명확)
sealed interface EntryPriceDecision {
    data class Enter(val limitPrice: BigDecimal) : EntryPriceDecision   // priceType=LIMIT 발행
    sealed interface Skip : EntryPriceDecision {
        data object PriceCapExceeded : Skip   // ask1 > cap → SIGNAL_SKIPPED_ENTRY_PRICE_CAP
        data object SpreadTooWide : Skip      // 스프레드 > maxSpreadPct → SIGNAL_SKIPPED_SPREAD
        data object ThinLiquidity : Skip      // 잔량 약함
        data object NoQuote : Skip            // 호가 미가용/stale → SIGNAL_SKIPPED_NO_QUOTE (시장가 fallback 금지)
    }
}

object EntryPricePolicy {
    fun evaluate(input: EntryPriceInput): EntryPriceDecision
}
```

- **순수성 LOCK**(ScaleInExitCalculator §순수성 1:1): Clock·IO·Spring 0. `quoteAsOf`·`evalAt`는 입력 주입. 동일 입력 → 동일 출력.
- **시장가 fallback 0**: 어떤 Skip 사유에서도 MARKET 발행 0(슬리피지 캡 자기 부정 회피 — §3-1 5).
- **재시도 시 priceAtSignal 불변**: 재시도 입력도 원 신호 가격(추격 금지 — §3-2).
- AI 호출 0(룰 기반 자본 흐름 순수 함수 — ADR 0023 정합).

호출자(consumer `RealtimeSignalGenerator` BUY 경로 + 백테스트 fill model + 리플레이)는 호가를 주입 source(§10 Redis quote cache)에서 읽어 본 함수에 넘기고, `Enter`면 OrderIntent `priceType=LIMIT`+`limitPrice` 발행, `Skip`이면 audit만.

---

## §7. R1/R2/R3 라운드 분할 (개발 룰 3 정합)

한 PR 4 layer 동시 변경 금지(개발 룰 3). R1·R2는 테스트 0건 OK, R3 일괄(개발 룰 4):

| 라운드 | 범위 | 담당 | 테스트 |
|---|---|---|---|
| **R1** | 도메인: `EntryPricePolicy` 순수 계산기 + `EntryPriceInput`/`EntryPriceDecision` sealed + RiskPlan 진입 설정 3종(JSONB add-only) + 전역 default env 시그니처 | strategy-engine(+domain) | 계산기 단위 테스트만(순수 함수 — R1 허용) |
| **R2** | 어댑터·영속: ① 호가 source(Redis quote cache §10) ② BUY 경로 EntryPricePolicy 호출 + OrderIntent `priceType=LIMIT`/`limitPrice` 발행 ③ P1 readiness 분화 ④ P2 position-driven 청산 ⑤ P3 HOLDING owner + 등록/해제/evict 제외 ⑥ P4 sell-enabled env ⑦ 미체결 타임아웃 취소 워커(`ApiKisOrderCancelAdapter` 재사용) ⑧ 손절 MARKET/익절 LIMIT 가격 유형 발행 | backend-core + market-data | R2 테스트 0건 OK |
| **R3** | 검증 일괄: 백테스트 슬리피지 모델 정합(EntryPricePolicy 공유) + 시나리오 리플레이(진입 스킵·타임아웃 취소·DISABLED 청산·readiness 분화·NXT SELL) + qa 회귀 | qa(+strategy-engine 리플레이) | 데이터 흐름 시나리오 일괄 |

R2는 add-only 충돌 표면이 분리되므로(EntryPricePolicy 호출 vs owner enum vs readiness 분화 vs 취소 워커) 일부 병렬 가능 — 단 `RealtimeSignalGenerator` 단일 파일에 P1·P2·entry 호출이 모이므로 orchestrator가 충돌 행렬 재검토 후 분할(사용자 메모리 `feedback_orchestrator_active_parallel` 정합).

---

## §8. 영향도 매트릭스

### 8-1. 기존 ADR 영향

| ADR | 영향 | 대응 |
|---|---|---|
| 0067(T-RISK Signal→RiskGateway→OrderIntent) | LOW — OrderIntent에 priceType/limitPrice 채워 발행(배선 이미 완성) | RiskGateway 게이트 불변. publisher가 default MARKET 대신 EntryPricePolicy 결과 발행 |
| 0068(T-O 주문 실행) | LOW — LIMIT 주문·취소(`ApiKisOrderCancelAdapter` 재사용)는 기존 경로 | 미체결 타임아웃 취소 워커 신규(R2) |
| 0105(readiness gate) | **MEDIUM** — §3-6 fail-closed를 BUY 한정으로 분화 | §4-1 본 ADR이 청산 SELL 진행 예외 명문화. ADR 0105 §3-6 본문은 BUY 기준으로 재해석(amend 불요 — 본 ADR이 SELL 분화 권위) |
| 0106(RiskPlan 필수화) | 0 — 진입 가격은 RiskPlan 손절/익절 preflight **후** 산정 | 게이트 순서 보존(0106 fail-closed 먼저, EntryPricePolicy 나중) |
| 0109(canary hard cap) | LOW — 진입 스킵 = 주문 0건(cap 미소진). 재시도 0회 = 추가 cap 소진 0 | canary 시 스킵률 관측(§관측). 미체결 취소도 cap 영향 0(체결 전 취소) |
| 0110(strategy_position) | **MEDIUM** — P2 position-driven 청산·P3 HOLDING 등록이 strategy_position 토대 활용 | StrategyPositionPort를 청산 평가 진입점으로 승격 |
| 0111(ScaleInExitCalculator) | LOW — 본 ADR은 0111 exit 출력에 priceType(손절 MARKET/익절 LIMIT)을 입힘 | ScaleInExitCalculator 평가 로직 불변. EntryPricePolicy는 진입 가격 별도 |
| 0048(호가 LRU)·0028/0029(Ingestion·Redis current) | **MEDIUM** — 진입 marketable limit의 호가 source 필요 | §10 Redis quote cache audit — 신규 source 신설 여부 R2 |

### 8-2. 영역 침범

| 영역 | 침범 | 회피 |
|---|---|---|
| domain/strategy-engine | EntryPricePolicy 순수 계산기 + RiskPlan 필드 | ScaleInExitCalculator 선례 1:1 — 순수성 LOCK |
| consumer | RealtimeSignalGenerator 게이트 분화 + BUY 경로 entry 호출 | add-only 보강 — 기존 평가 체인 불변 |
| 운영 코드(본 ADR) | 0 (md only) | Kotlin/TS/SQL 0 변경 — 본 PR은 ADR+README+CHANGELOG |

---

## §9. UNCERTAIN / R2·R3 결정 위임 의제

본 ADR이 LOCK하지 않고 구현 라운드가 결정할 의제(과설계 회피):

| # | 의제 | 위임 | 보수 default |
|---|---|---|---|
| U-1 | RiskPlan 진입 3종 저장 형태(`EntryPolicy` 묶음 vs RiskPlan 평면 필드) | R1 | ScaleInPlan처럼 묶음 객체 권장 |
| U-2 | 호가 잔량 "약함" 임계(askQty1 절대값 vs 주문 수량 대비 비율) | R2 | 주문 수량 ≤ askQty1 충족 권장 |
| U-3 | 호가 stale 판정 기준(quoteAsOf 경과 ms) | R2 | tick 기반 — 수 초 권장 |
| U-4 | 미체결 취소 워커 위치(consumer 스케줄러 vs OrderRouter 콜백) + 트리거 | R2 | 주문 ack 시각 + entryTimeoutSec 스케줄 |
| U-5 | 익절 LIMIT 미체결 처리(유지 vs 취소 후 재평가) | R2 | 유지(손절 게이트가 하한 보호) |
| U-6 | P2 전략 정의 자체 삭제 시 청산 fallback(전역 default stop vs KillSwitch alert) | R2 | KillSwitch alert + 보유분 보호 |
| U-7 | 보유 종목 1m 폴링 fallback(P5) | **post-MVP** | HOLDING owner가 evict 차단으로 대부분 cover |
| U-8 | HOLDING 해제 grace TTL | R2 | DETAIL grace 패턴 재사용 |
| U-9 | NXT sell-enabled default를 향후 false로 전환할지 | 사용자 결재(운영 데이터 후) | true 유지 |

---

## §10. 자원 audit (ADR 0102 5단계)

본 ADR은 md only(영속 자원 신설 0)이나, R1~R3 구현이 점유할 자원을 사전 inventory한다(ADR 0102 §2-1):

### 10-1. Flyway

main HEAD audit 결과 **V71까지 점유**(`api/src/main/resources/db/migration/` — V67~V71 확인). RiskPlan 진입 3종은 ADR 0111 `scale_in_config`/`partial_exits` JSONB와 동일하게 **기존 JSONB 컬럼 add-only**(또는 risk_plan JSONB 내 키 추가)면 **신규 Flyway 마이그레이션 불요**. 신규 컬럼이 필요하면 **V72부터**(R1 audit 재확인 — 별 브랜치 점유 가능성 grep 후 next-available).

### 10-2. Kafka

`order.intent.v1` payload에 `priceType`/`limitPrice` **add-only 필드**(이미 OrderRouter가 읽는 키 — `OrderRouterKafkaListener.kt:949·954`). 신규 topic 0. 호가 source로 `market.orderbook.v1`(기존 — `KafkaTopicConfig.kt:57` H0STASP0 스냅샷, retention 1h) 재사용 검토.

### 10-3. Redis (호가 source — **핵심 audit**)

진입 marketable limit은 주문 시점 **동기 호가 조회**가 필요하다. 현행 audit:
- 호가는 KIS WS `H0STASP0` → `KisWsOrderbookParser.parseH0Stasp0`(`KisWsOrderbookParser.kt:63`) → Kafka `market.orderbook.v1` publish(`KisWebSocketBridge.kt:892-`)로만 흐르고, **동기 read용 Redis 캐시가 없을 가능성**이 높다(grep 결과 orderbook Redis key 미발견). 또한 호가 LRU slot이 NXT 포화로 `ORDERBOOK_MAX=0` 검토 중(`KisWebSocketBridge.kt:75` backlog §53).
- → **R2 결정 의제**: ① 호가 Redis cache(`orderbook:last:{code}` 등) 신설 + WS handler에서 upsert, 또는 ② KIS REST 호가 endpoint(`inquire-asking-price` `FHKST01010200` — 현재 코드 미존재, KIS-first raw fetch로 규격 확인 후 신설) 동기 호출. 진입 시점 1회 호출이라 TPS 부담 낮음(ADR 0037 10 TPS 내). **시장가 fallback 금지**이므로 호가 미가용 시 진입 스킵(§3-1 5).

### 10-4. STOMP

신규 topic 0(진입 스킵·취소 audit는 기존 signal/audit fanout 재사용).

### 10-5. ENV 신설 목록

| env | default | 용도 |
|---|---|---|
| `magicjar.order.entry.max-slippage-pct` | 0.2 | 전역 진입 상한 슬리피지(전략 null 시) |
| `magicjar.order.entry.timeout-sec` | 15 | 전역 미체결 취소 타임아웃 |
| `magicjar.order.entry.max-spread-pct` | 0.3 | 전역 스프레드 게이트 |
| `magicjar.order.entry.retry-count` | 0 | 진입 재시도 횟수(기준가 불변) |
| `magicjar.signal.market-hours-gate.sell-enabled` | true | SELL 평가 market-hours gate(P4 — false면 NXT SELL 허용) |

`.env.example` + `git grep "MAGICJAR_"`로 명명 충돌 검사(R2). `magicjar.signal.market-hours-gate.*`는 기존 `marketHoursGateEnabled`(`RealtimeSignalGenerator.kt:139`) 인근 — prefix 정합.

---

## §11. 운영 ADR 양식 3 슬롯 (헌법 §9-5)

### 11-1. SLO 영향

- 진입 스킵 신설 → 체결 건수 감소 가능(슬리피지 보호 trade-off). 진입 체결률/스킵률을 신규 관측(assessment §7 차기 지표).
- 청산 게이트 분화(P1·P2·P3) → 손절 신호→체결 지연·미평가 risk 감소(자본 보호 강화). S-1(시그널→주문) 빈도엔 큰 영향 0(진입은 오히려 스킵으로 감소).

### 11-2. rollback plan

- 본 PR(ADR+README+CHANGELOG): 운영 코드 0 touch.
- 후속 구현 rollback: EntryPricePolicy 미연결 시 OrderRouter default MARKET 복귀(현행). RiskPlan 진입 3종 JSONB는 add-only null이라 사용만 제거하면 현행 시장가 진입 복귀. P1~P4 게이트 분화는 env(`sell-enabled=true`·readiness 분화 feature toggle)로 현행 fail-closed 복귀 가능. **단 P1·P2는 안전 결함 fix이므로 rollback은 자본 보호 약화** — toggle은 두되 default는 fix 방향 권장.

### 11-3. 관측 매트릭스

| 측정 | 목표 | 방법 |
|---|---|---|
| 진입 체결률 / 스킵률 | 룰 적용 후 추적 | OrderIntent `priceType=LIMIT` 발행 vs `SIGNAL_SKIPPED_ENTRY_PRICE_CAP`/`_SPREAD`/`_NO_QUOTE` audit count |
| 평균 진입 슬리피지 | maxSlippctPct 이내 | 체결가 vs priceAtSignal diff |
| 미체결 취소율 | entryTimeoutSec 내 미체결 | 취소 주문 count |
| 손절 신호→체결 지연 | 단축(P1·P3) | STOP_LOSS signal ts vs 체결통보 ts |
| 청산 미평가 0건 | P1·P2 해소 | readiness FAILED·DISABLED 전략 보유분의 청산 평가 발화 audit |
| HOLDING evict 0건 | P3 — 보유 종목 구독 유지 | RealtimeSubscriptionRouter evict log에 HOLDING code 부재 |

---

## §12. 트레이드오프

| 결정 | 채택 사유 | 거부 대안 |
|---|---|---|
| 신규 독립 ADR(0111 amend 아님) | 0111=수량/평단 exit **모델**, 0114=가격/집행/게이트 **정책** — 책임 축 다름. amend 시 0111 권위 비대 | ADR 0111 amend(가벼움) — 권위 분산 회피 위해 독립 |
| 진입 marketable limit | 체결 노리되 상한 보호 — 소극 지정가 미체결 추격 해소 | 시장가(보호 0)·소극 1호가-밑 지정가(미체결 추격) |
| 진입 미체결 시장가 전환 금지 | 슬리피지 캡 자기 부정 회피 | 시장가 전환(체결 우선) — 캡 무력화 |
| 손절 MARKET / 익절 LIMIT | 손절=체결 확실성(KIS 스탑지정가 없음), 익절=가격 보호 | 손절 LIMIT(미체결 손실 확대 risk) |
| P1 SELL은 readiness 우회(현재가 가용 시) | fail-closed는 신규 투입 차단에만 — 자본 회수는 막지 않음 | SELL도 fail-closed(현행) — 손절 skip Critical |
| P2 position-driven 청산 | 포지션이 청산 평가 1차 권위(전략 status 결합 끊기) | DISABLED SELL-only 조회(전략 status에 청산 결합 유지) |
| P3 HOLDING owner evict 금지 | 보유 종목 구독 끊김 = 청산 재료 공백 = 자본 risk | watchlist 합집합 의존(현행 — direction-guard 위반) |
| P4 sell-enabled default true | NXT 유동성 얇음 — 사용자 데이터 후 전환 | default false 즉시(NXT 슬리피지 risk) |
| EntryPricePolicy 순수 계산기 공유 | 리플레이≠라이브 의심 차단(ScaleInExitCalculator 선례) | live/백테스트 각자 구현 → drift |
| 호가 미가용 시 진입 스킵(시장가 fallback 0) | 슬리피지 캡 정합 | 시장가 fallback(캡 자기 부정) |

---

## §13. References

- 입력 권위: `docs/assessments/2026-06-07-order-execution-policy-and-position-vigilance.html` (코드 전수 인용 + KIS open-trading-api 실측)
- 코드 evidence: `OrderRouterKafkaListener.kt:489·491·654-656·716·718·948-989` · `RealtimeSignalGenerator.kt:99-161` · `ConsumerStrategyJpaRepository.kt`(findActiveByCodeAndInterval) · `RealtimeSubscriptionRouter.kt:168-184·231-244` · `ApiKisOrderCancelAdapter.kt:47·59-81` · `KisWebSocketBridge.kt:75·425-459·892-` · `KisWsOrderbookParser.kt:63-115` · `KafkaTopicConfig.kt:14·57` · `ScaleInExitCalculator.kt`(순수 함수 패턴) · `StrategyDefinition.kt:23-41`(RiskPlan JSONB add-only 패턴)
- KIS 스펙: open-trading-api `order_resv`(`CTSC0008U` 시간 예약) · `inquire-asking-price`(`FHKST01010200` 호가 — §10-3 신설 후보, KIS-first raw fetch 확인 의무)
- 관련 ADR: 0067·0068·0105·0106·0109·0110·0111 · 0048·0028·0029·0102
- 사용자 메모리: `feedback_kis_first_policy`(KIS-only) · `feedback_v3_skepticism_no_regression`(하위호환) · `feedback_no_mock_data`(호가 미가용=스킵, mock 0) · `feedback_orchestrator_active_parallel`(R2 분할)

---

## §14. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-06-07 | **ADR 0114 신설 Proposed** — 주문 집행 정책(진입 marketable limit·상한 스킵·스프레드 게이트·미체결 취소·시장가 전환 금지·재시도 기준가 불변·손절 MARKET/익절 LIMIT·부분체결 명문화) + 청산 "항상" 보장 게이트 분화(P1 readiness BUY 한정 fail-closed·SELL은 현재가 가용 시 진행 / P2 position-driven 청산 전략 status 무관 / P3 HOLDING owner evict 금지 / P4 sell-enabled env / P5 1m 폴링 post-MVP) + RiskPlan 진입 3종(JSONB add-only 하위호환) + EntryPricePolicy 순수 계산기(domain·live/백테스트/리플레이 공유 — ScaleInExitCalculator 선례) 계약 초안 + R1/R2/R3 라운드 분할 + 자원 audit(Flyway V71까지·신규 마이그레이션 불요 가능·호가 Redis source R2 신설 의제·ENV 5종) + 운영 ADR 3 슬롯. 입력=assessment 2026-06-07(코드 전수+KIS 실측). 별도 브랜치 전용(Codex 병렬 후 비교 머지 — 머지 시 Accepted 승격). 운영 코드 0 touch(md only). 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. | architect (사용자 §3·§5 결재) |
