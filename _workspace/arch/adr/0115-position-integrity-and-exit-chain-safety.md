# ADR 0115 — 포지션 단일 권위 + 드리프트 자동보정 + 청산 사슬 replay/cooldown 안전화

- **상태**: Proposed (2026-06-08, canary 전날 — md only 설계 선행, 코드 미반영)
- **작성**: architect
- **트랙**: T-O (주문·청산 운영) · 입력: `docs/assessments/2026-06-08-mock-alarm-flood-rootcause.html`
- **관련 ADR**: 0110(strategy_position 1급 도메인) amend · 0111(RiskPlan scale-in/exit) · 0114(주문집행 정책·청산 보장) 보강 · 0070(알림 rate limit) · 0084(WS 재연결 backfill)
- **원칙**: 모드 분기(REAL/MOCK)는 **주문 액션(실돈)에만**. 정합·드리프트 보정·이벤트 안전화는 REAL/MOCK 동일 경로(2026-06-08 사용자 지시 정합).

---

## 1. 배경 — MOCK 알람 폭주 사슬 (분석 §1 입력)

2026-06-08 MOCK 전략 #8에서 단일 상류 결함(아침 `SIGNAL_SKIPPED_NO_QUOTE`)이 하류로 4단계 증폭되어 슬랙 알람 수천 건(`IDEMPOTENCY_SKIPPED` 4,920건/30분)이 발생했다. 자본 손실은 0(없는 물량을 못 판 것뿐, KIS가 정확히 거부). **근본 원인은 발단 자체가 아니라, 사슬을 끊을 안전장치의 구조적 부재**다:

| 단계 | 현행 코드 | 빈틈 |
|------|-----------|------|
| [전환] backfill 재발행 → 중복 익절 | `KisExecutionBackfillService.publishBackfillFillEvents` → `order.filled.v1` 재발행 | `AutoExitOrderCoordinator.onOrderFilled`가 재발행을 **새 체결로 오인**(replay 식별 0) |
| [누적] strategy_position 7 vs position(KIS) 5 drift | `ConsumerExecutionStrategyPositionAdapter.verifyDrift` | drift를 **WARN SystemEvent만** 발사, 보정 안 함 (ADR 0110 §2-2 "보수적 position 우선"이 로그 수준에 머묾) |
| [폭주] 유령 7주 기준 손절 매도 | `PositionStopLossTickWatcher.evaluate` | 손절가 도달 시 **매 tick `publishExitIntent`** 발행, 상태 추적·쿨다운 0 |
| [증폭] 반복 거부에도 매번 알림 | `NotificationDispatcher` | `SELL_WITHOUT_POSITION` 15분 dedupe는 있으나, N회 반복 거부 시 **종목 자동 정지 없음** |

핵심 통찰: **"변경이 방아쇠, 구조 빈틈이 화약"** (분석 §5). 오늘 켠 두 변경(진입 marketable limit → opt-in 게이트로 이미 수정 / MOCK 체결 backfill 동일화 #815)이 새 실패 모드를 노출했고, 그 화약은 ADR 0110 시점부터 있던 drift 비보정·replay 미식별 빈틈이다.

### 1-1. 자원 audit (ADR 0102 — spec 발행 전 의무)

| 자원 | 신규 필요 | 근거 |
|------|----------|------|
| **Flyway** | **불필요** | drift 보정은 기존 `strategy_position`·`position` 테이블 upsert만. replay·cooldown은 인메모리/Redis. |
| **Kafka topic** | **불필요** | `order.filled.v1` payload에 **이미 `fillSource` 필드 존재**(`OrderFilledKafkaProducer.fillSource` → `"REST_BACKFILL"`/`"WS"`). replay 식별은 기존 필드 read만. |
| **Redis** | 선택(P2) | watcher 쿨다운은 인메모리(consumer 단일 인스턴스) 우선. 다중 인스턴스 확장 시 Redis SETEX. P2-3에서 결정. |
| **ENV** | **신규 3건** | `magicjar.position.drift-auto-correct.enabled`(default true) · `magicjar.autotrading.stop-loss.tick-cooldown-seconds`(default 30) · `magicjar.notification.reject-auto-pause.threshold`(default 10). 모두 add-only, 기존 키 변경 0. |
| **REST/STOMP** | **불필요** | 내부 청산 사슬 한정. UI 계약 변경 0. |

**audit 결론**: 신규 마이그레이션·토픽 0. replay 식별이 기존 payload 필드 read만으로 가능한 것이 본 ADR 구현 경량성의 핵심 근거다.

---

## 2. 결정 (6항 — 대안 비교표 포함)

### P1-1. 포지션 단일 권위 + 드리프트 자동 보정 (★ 뿌리)

**현행** (`ConsumerExecutionStrategyPositionAdapter.verifyDrift`, L215–269):
```kotlin
if (strategySum != positionQty) {
    log.warn("STRATEGY_POSITION_DRIFT: ... (KIS/position 권위 우선 — 보정 안 함)")
    systemEventLoggerProvider...emit(SystemEvent(... action="STRATEGY_POSITION_DRIFT", WARN))
    // 보정 없음 — strategy_position 7주가 그대로 남아 유령 매도 유발
}
```

**결정**: ADR 0110 §2-2의 "drift 시 KIS(position)가 상위 권위 + 보수적 position 우선"을 **로그 수준 → 실제 정렬 동작**으로 진화시킨다. drift 감지 시 `strategy_position`을 `position`(KIS 잔고 권위)에 **자동 보정(내림)**한다.

**보정 규칙 (명시)**:
- **권위 출처**: `position.quantity`(user+code+source/mode, KIS 잔고 부트스트랩 ADR 0042 권위). position이 곧 KIS 실잔고의 내부 투영.
- **보정 방향**: `strategySum > positionQty`(유령 초과 — 이번 incident 7 > 5) → 초과분을 **내림**. `strategySum < positionQty`(미반영 — 외부 HTS 매수 등) → **보정하지 않음**(WARN만 유지). 사유: 초과는 자본 위험(유령 매도 폭주)이지만, 부족은 보수적이며 외부 주문·backfill 지연 등 정상 사유 가능 → 함부로 늘리면 없는 물량 인식 risk.
- **다전략 배분**: 동일 종목을 N전략이 운용 중일 때 초과분 내림은 **`updatedAt` 최신 행부터 차감**(가장 최근 잘못 누적된 leg를 우선 정정). 단일 전략(incident #8 — 1전략 1종목)은 그 행만 정정.
- **하한 가드**: `quantity`는 0 미만으로 내리지 않음(`coerceAtLeast(0)`). 0 도달 시 `avgPrice`·`filledLots`·`executedExitStages` reset(전량 청산과 동일 규칙 — `applySell` nextQty==0 분기 정합).
- **realized_pnl 보존**: 보정은 **수량 정렬일 뿐 손익 이벤트가 아니다**. `realized_pnl`은 절대 건드리지 않음(체결로만 변동). 보정으로 줄인 수량은 "체결되지 않았던 유령"이므로 손익 0.
- **보정 시점**: `applyExecution` 끝의 `verifyDrift` 호출 시점(체결 직후) + (P2) 주기 reconcile worker. 체결 직후 보정이 incident 핵심 경로(체결마다 정합 회복).
- **감사**: 보정 시 `STRATEGY_POSITION_DRIFT_CORRECTED` SystemEvent(WARN) — before/after qty + 보정 방향 기록. 기존 `STRATEGY_POSITION_DRIFT`(보정 불가/부족 방향)와 구분.
- **모드 무관**: REAL/MOCK 동일 경로(2026-06-08 사용자 지시). position이 권위인 것은 두 모드 공통.

**보정 주체**: `ConsumerExecutionStrategyPositionAdapter`(현 drift 감지 위치 그대로 — 책임 이동 0). `StrategyPositionPort.upsert`로 정렬.

**대안 비교**:

| 대안 | 장점 | 단점 | 판정 |
|------|------|------|------|
| **(채택) position(KIS)을 권위로 strategy_position 자동 내림** | fail-safe — 내부 ledger 버그가 실잔고 초과 SELL로 안 번짐. ADR 0110 §72 대안표와 일관 | 부족 방향 미보정(외부 주문 흡수는 별도 backfill 경로 의존) | ★ |
| strategy_position을 권위로 KIS에 주문 정정 | 내부 의도 보존 | ADR 0110 §72에서 이미 거부 — "내부 ledger 권위 → KIS 불일치 시 거절 폭주". 이번 incident가 그 폭주의 실증 | 거부 |
| WARN만 유지(현행) | 코드 변경 0 | 유령 매도 폭주의 직접 원인. incident 근본 | 거부 |
| 양방향 보정(부족도 늘림) | 완전 정합 | 없는 물량을 인식 → 다음 incident 씨앗. 외부 매수는 backfill이 정식 경로 | 거부 |

### P1-2. 이벤트 replay-safe — backfill 재발행 체결 오인 차단

**현행**: `KisExecutionBackfillService.publishBackfillFillEvents` → `orderFilledKafkaProducer.publish(request, execution, partial)`가 `order.filled.v1`를 재발행. `AutoExitOrderCoordinator.onOrderFilled`(L75)는 source·qty만 검사하고 **신선도·replay 식별 없이** 익절 SELL을 발행 → 과거 체결마다 중복 익절(incident 4·6·5·6·7주).

**결정**: backfill/복구가 재발행한 `order.filled.v1`를 청산 coordinator가 **새 체결로 오인하지 않게** 한다. 식별 메커니즘:
- **1차(권위) — `fillSource` 필드 read**: `OrderFilledKafkaProducer`는 **이미** payload에 `fillSource`(`"REST_BACKFILL"`/`"WS"`)를 직렬화한다(L71, L148–153). `AutoExitOrderCoordinator.parse`가 이 필드를 파싱하고, `fillSource == "REST_BACKFILL"`이면 **TP 선주문을 발행하지 않는다**(보유 인덱스·구독 갱신은 유지 — 청산 의사결정만 skip).
- **2차(보강) — executedAt 신선도**: payload의 `executedAt`(epochSecond, 이미 존재 L74)가 현재 시각 대비 임계(default 120초) 초과 과거면 "복구된 과거 체결"로 간주 → TP 발행 skip. WS 정상 경로는 체결 즉시 발행이므로 신선. (1차로 대부분 차단되나, 향후 다른 복구 경로 대비 fail-safe 하한.)
- **근거**: 분석 §3 orchestrator 추가 — 오늘 켠 #815 MOCK backfill이 `AutoExitOrderCoordinator`를 활성화/악화. replay-safe 없으면 backfill 도입이 이 버그를 깨운다. fillSource가 이미 있어 **신규 Kafka 필드 0, read-only 가드**로 해결.

**대안 비교**:

| 대안 | 장점 | 단점 | 판정 |
|------|------|------|------|
| **(채택) fillSource read + executedAt 신선도 2중** | 신규 필드 0(기존 payload). 권위(source) + fail-safe(신선도) | coordinator에 분기 1개 추가 | ★ |
| backfill이 `order.filled.v1` 미발행 | 원천 차단 | backfill의 STOMP FILLED fanout·잔고 갱신(`accountBalanceChanged`)도 못 함 → 화면 미반영. 발행은 필요, 소비처에서 분기가 옳음 | 거부 |
| 별도 `order.backfilled.v1` 토픽 신설 | 명시적 분리 | 신규 토픽 + 모든 소비처 재배선. fillSource로 충분 | 거부(over-engineering) |

### P2-1. watcher 쿨다운 — 매 tick 재발행 차단

**현행**: `PositionStopLossTickWatcher.evaluate`(L83–144)는 손절가 도달 시 **매 tick `publishExitIntent`** 발행. idempotency(`auto-sl:` 키)는 주문 라우터의 `idem:order:{key}`가 흡수하나, **intent publish 자체는 계속** → `IDEMPOTENCY_SKIPPED` 4,920건/30분.

**결정**: auto-sl intent 발행 후 **상태 변화 전까지 재발행 금지**. idempotency(주문)에만 의존하지 않고 **intent 생성 단계에서 차단**한다:
- watcher에 종목·전략별 **인메모리 cooldown map**(`{userId:strategyId:code → lastIntentEpoch}`) 추가. `evaluate`가 intent 발행 전 cooldown 검사 — `now - lastIntent < cooldownSeconds`(default 30)면 skip(로그 debug). 발행 성공 시 갱신.
- **상태 기반 해제**: 포지션 수량 변화(`updatedAtEpoch` 변경) 또는 손절가 미도달 복귀 시 cooldown clear(다음 정당한 손절 즉시 가능). 즉 "같은 상태로 무한 재발행"만 막고, 실제 상태 진전은 즉시 통과.
- **인메모리 우선**: consumer 단일 인스턴스 가정(현 배포). 다중 인스턴스 확장 시 Redis SETEX로 승격(P2-3 backlog).

**대안 비교**:

| 대안 | 장점 | 단점 | 판정 |
|------|------|------|------|
| **(채택) intent 단계 cooldown + 상태기반 해제** | 폭주 원천 차단. 정당한 손절은 즉시 통과 | 인메모리 상태 1개 추가 | ★ |
| idempotency만 의존(현행) | 코드 0 | 주문은 막지만 intent·로그·알림 폭주는 그대로. incident가 실증 | 거부 |
| watcher 비활성화 | 폭주 0 | 손절 안전망 손실(ADR 0114 핵심 fail-safe). 부작용으로 안전장치 끄기 = 잘못된 방향 | 거부 |

### P2-2. 알림 디바운스 + N회 거부 시 종목 자동 정지

**현행**: `NotificationDispatcher`에 이미 `SELL_WITHOUT_POSITION`·`POSITION_LIMIT` 15분 dedupe(`RISK_REPEAT_COOLDOWN_RULES`)와 cancelled 60분 윈도 ≥5건 1회 인프라가 **존재**한다. 그러나 **N회 반복 거부 시 종목 자동 정지는 없다** → 정합이 회복될 때까지 알림은 줄어도 시도는 계속.

**결정**: (a) 디바운스는 기존 인프라 재사용·확인(신규 거의 0). (b) **동일 사유 거부 N회(default 10) 누적 시 해당 종목(전략·종목 단위) 자동 정지** + 1회 알림. 정지는 P1-1 drift 자동보정이 정합을 회복하면 자연 해제(보정 후 다음 신호는 정상). 자동보정과 자동정지가 함께 작동해 incident의 "거부 폭주 + 알림 폭주"를 양쪽에서 차단.

**대안 비교**:

| 대안 | 장점 | 단점 | 판정 |
|------|------|------|------|
| **(채택) 기존 dedupe 재사용 + N회 거부 자동정지** | 인프라 재사용. 정합 회복 시 자연 해제 | 정지 상태 추적 1개 | ★ |
| 디바운스만(자동정지 없음) | 단순 | 알림은 줄지만 무익한 시도·자원 소모 계속 | 부분 |

### P2-3. PAUSED 전략 손절 가드 — 유령 포지션 발사 금지

**현행**: position-driven 손절(`PositionStopLossTickWatcher`)은 전략 status와 무관하게 보유 포지션 기준 발사(의도 — 보유 보호). 그러나 **드리프트/유령 포지션에도 발사** → PAUSED 전략의 유령 2주가 손절 매도 시도.

**결정**: position-driven 청산의 보호 의도(보유 중이면 손절 보장)는 **유지**하되, 발사 전 **포지션 권위 확인 가드** 추가:
- watcher `evaluate`가 손절 intent 발행 전, `position`(KIS 권위) 잔량 > 0 확인. P1-1 자동보정이 이미 `strategy_position`을 정렬하므로 보정 후엔 유령이 사라져 자연 해소되나, **보정 직전 tick·경합 구간**의 fail-safe로 권위 재확인 가드를 둔다(이중 안전).
- PAUSED 전략도 **실보유(position>0)면 손절 발사 유지**(보유 보호 의도 보존), **유령(position==0인데 strategy_position>0)이면 발사 금지**.

**대안 비교**:

| 대안 | 장점 | 단점 | 판정 |
|------|------|------|------|
| **(채택) position 권위 확인 후 발사** | 보유 보호 의도 보존 + 유령 발사 차단 | 발사 경로에 조회 1개(인메모리 인덱스 hit 시 경량) | ★ |
| PAUSED 전략 손절 전면 금지 | 단순 | 보유 중 손절 안전망 손실 — ADR 0114 의도 훼손. PAUSED여도 실보유는 보호해야 | 거부 |

### P2-4. missed 체결통보 — ACK 정체 주문 timeout 재조회 강화

**현행**: WS 체결통보 누락 시 주문이 ACK에서 영구 정체(incident 주문 144·176). `KisExecutionBackfillService`(#815)가 재연결 시 일부 복구하나, **재연결 트리거가 없으면(연결 유지 중 단순 통보 누락)** 정체 지속.

**결정**: ACK/REQUESTED 상태로 임계 시간(default — 기존 `EntryTimeoutCancelWorker` 정합 검토) 초과한 주문에 대해 **timeout 재조회 강화** — 재연결 hook뿐 아니라 **주기 worker**가 정체 주문 발견 시 `syncUserTodayExecutions`(이미 존재, L208) 발사로 KIS 일별체결 대조. (구현은 P2 — 기존 backfill 메서드 재사용, 신규 로직 최소.)

**대안 비교**: 기존 `EntryTimeoutCancelWorker`·`KisExecutionBackfillService.syncUserTodayExecutions` 재사용이 권위. 신규 컴포넌트 신설 거부(중복 회피).

---

## 3. 라운드 분할 (개발 룰 3 — R1 도메인+시그니처 / R2 어댑터+가드 / R3 일괄 테스트)

> 개발 룰 4(코드 먼저, 테스트 마지막 일괄) 정합 — R1·R2는 테스트 0건 OK, R3에서 사슬 시나리오 일괄.

### R1 — 보정 규칙·replay 식별 (도메인 순수 로직 + 시그니처)
1. **P1-1 보정 산정 순수 함수**: `strategy_position` 정렬 산정(초과분 내림·하한·realized 보존·다전략 updatedAt 차감 순서)을 순수 함수로. domain 또는 adapter 내 deterministic 함수.
2. **P1-2 replay 판정 순수 함수**: `fillSource == "REST_BACKFILL"` || `executedAt 신선도 초과` → `isReplayFill(): Boolean`. `AutoExitOrderCoordinator.FilledEvent`에 `fillSource` 필드 add(parse에서 이미 가능한 raw read).
3. ENV 3키 default 선언(보정·cooldown·auto-pause).

### R2 — 어댑터 가드 배선 (실제 차단 동작)
1. **P1-1**: `ConsumerExecutionStrategyPositionAdapter.verifyDrift`를 `reconcileDrift`로 — 초과 방향 시 `strategyPositionPort.upsert`로 정렬 + `STRATEGY_POSITION_DRIFT_CORRECTED` 이벤트. (ENV off면 현행 WARN-only로 fallback.)
2. **P1-2**: `AutoExitOrderCoordinator.handleBuyFill`/`onOrderFilled`에 `isReplayFill` 가드 — replay면 인덱스·구독 갱신만, TP 발행 skip.
3. **P2-1**: `PositionStopLossTickWatcher`에 cooldown map + 상태기반 clear.
4. **P2-3**: watcher 발사 전 position 권위 잔량 확인 가드.
5. **P2-2**: `NotificationDispatcher` N회 거부 자동정지(기존 rate limit 인프라 확장).
6. **P2-4**: 정체 주문 주기 재조회(기존 backfill 메서드 재사용).

### R3 — 사슬 시나리오 일괄 테스트
- drift 7>5 → 보정 5 정렬 + realized 보존 검증
- backfill `fillSource=REST_BACKFILL` 재발행 → TP 미발행 검증
- 손절가 도달 매 tick → cooldown 내 1회만 intent 검증
- PAUSED + 유령 position==0 → 손절 미발사 / PAUSED + 실보유 → 발사 유지
- N회 거부 → 종목 자동정지 + 1회 알림
- **incident 재현 회귀**: NO_QUOTE → drift → 폭주 사슬이 각 가드로 끊기는지 e2e(MOCK)

---

## 4. 결과 / 트레이드오프

- **자본 안전**: position(KIS) 단일 권위가 모든 청산 의사결정의 바닥. 내부 ledger 버그가 실잔고 초과 SELL로 번지지 않음(ADR 0110 fail-safe 강화).
- **경량성**: replay 식별이 기존 payload 필드(`fillSource`·`executedAt`) read만 — 신규 Flyway·Kafka topic 0. ENV 3키만 add.
- **점진성**: 모든 가드 ENV로 fallback 가능(보정 off → 현행 WARN, cooldown 0 → 현행 매 tick). canary 위험 시 즉시 보수적 복귀.
- **잔여 risk**: 인메모리 cooldown은 다중 consumer 인스턴스 비대응(현 단일 배포 가정). 확장 시 Redis 승격(P2-3 backlog). 부족 방향 drift(strategySum<position)는 보정 안 함 → 외부 주문 흡수는 backfill 경로 의존(설계 의도 — 없는 물량 인식 회피).
- **SLO 영향**: 직접 수치 영향 0. S-1(시그널→주문) 오발주 reject·알림 폭주 감소.

## 5. 모니터링

| 지표 | 목표 | 출처 |
|------|------|------|
| `STRATEGY_POSITION_DRIFT_CORRECTED` count | 보정 동작 가시화(0이 이상적, 발생 시 보정 성공) | SystemEvent |
| replay TP skip count | backfill 후 중복 익절 0 | AutoExitOrderCoordinator 로그 |
| auto-sl `IDEMPOTENCY_SKIPPED` /30분 | incident 4,920 → 정상 시 한 자릿수 | consumer 로그 |
| 종목 자동정지 발동 | 정합 회복 시 자연 해제 추적 | NotificationDispatcher |

---

## 6. canary 전 권장 진행 순서 (분석 §6 정합)

1. **즉시(완료)**: strategy_position #8 7→5 수동 보정 — 매도 거부 멈춤(완료).
2. **개선 1순위(R1·R2)**: **P1-1 drift 자동보정** — 뿌리. 이것만으로 유령 매도 자체가 안 생김.
3. **개선 동반**: P1-2 replay-safe — #815 backfill과 직결, P1-1과 같은 사슬.
4. **개선 2순위**: P2-1 watcher cooldown + P2-2 알림 자동정지 + P2-3 PAUSED 가드.
5. **canary 전**: ACTIVE 전략 1개 + 깨끗한 포지션에서 시작.
