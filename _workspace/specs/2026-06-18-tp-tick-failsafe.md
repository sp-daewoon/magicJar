# spec — TP tick fail-safe(익절 tick 안전망) 구현 명세

- 권위 ADR: **ADR 0127** (`_workspace/arch/adr/0127-take-profit-tick-failsafe.md`)
- 상태: **Accepted** — 코드는 canary D2 hotfix B로 **이미 라이브 반영 완료**(2026-06-12, `PositionTakeProfitTickWatcher` + 테스트 14건). 본 spec은 그 구현의 명세를 ADR과 1:1 정합화하고, **잔여 디스패치(L2 보완·qa E2E 실증)**를 명세화한다.
- 입력 결재: 최고 관리자 — "장중엔 tick을 계속 받으니, 보유분의 현재가가 익절가보다 큰지 검사하고 넘쳤을 때는 시장가로 던진다 — 손절 tick watcher와 대칭으로 익절 tick fail-safe를 추가하라."
- 정합 정책: 롱 온리(`docs/architecture/policies/long-only-trading.md`) · ADR 0114 R2-C2b(손절 tick 안전망) · ADR 0115(포지션 권위·replay·멱등 가드).

---

## 0. 디스패치 매트릭스 (이 spec이 만드는 작업)

> 핵심: **주 구현(watcher 클래스 + 14 단위테스트)은 이미 존재**(D2 hotfix B). 잔여는 ① 현행 구현 ↔ ADR 1:1 검증(backend-core·strategy-engine) ② L2 보완관계 운영 검증 ③ qa E2E 실증 ④ 문서·ENV 정합이다.

| 작업 | 담당 | 라운드 | 산출물 | 상태 |
|------|------|--------|--------|------|
| W1. `PositionTakeProfitTickWatcher` 구현 ↔ ADR 0127 §2~§6 1:1 검증(발사주체·익절가 출처·MARKET·멱등·게이트 우회) | **backend-core** | 검증 | 코드 점검 보고(diff 0 또는 정정 PR) | 구현 존재 — 검증만 |
| W2. 익절가 단일출처 `EntryPricePolicy.takeProfitLimitPrice` ↔ LIMIT 선주문 동일 산정 정합 검증 | **strategy-engine** | 검증 | 산정 동치 확인(LIMIT/tick fail-safe 같은 평단→같은 target) | 함수 존재 — 검증만 |
| W3. L2(`ExpiredDayOrderSyncWorker`) 좀비 ACK 정리 → L3 발사 체인 운영 검증(§7) | **backend-core** | E2E 전제 | L2 정리 후 L3 `OwnDuplicate` 무력화 0 확인 | 별도 L2 버그픽스 진행 중 |
| W4. qa E2E 시나리오(오버나잇→개장→초과→발사→청산·삼중경로 1회·토글회귀) | **qa** | R3 | 시나리오 재현 + 라이브 evidence 인용 | 신규 |
| W5. ENV 문서·`external-api-policy`/`development-rules` 정합(토글 등재) | **backend-core** | 검증 | `.env.example` + 문서 1줄 | 신규(소) |

> 개발 룰 5(`development-rules.md`): 한 PR = 한 영역. W1·W3·W5는 backend-core이나 의제가 다르면 분리. W4는 qa 단독. AI provider import 0(룰 5 — 자본 흐름 룰 기반).

---

## 1. 현행 구현 인벤토리 (검증 대상 — 이미 라이브)

| 파일 | 역할 | ADR 0127 매핑 |
|------|------|---------------|
| `consumer/.../order/PositionTakeProfitTickWatcher.kt` | tick≥익절가 MARKET SELL 발사 watcher | §2~§6 본체 |
| `consumer/.../order/PositionTakeProfitTickWatcherTest.kt`(14건) | 단위 검증 | §8-1 |
| `consumer/.../order/AutoOrderIdempotencyKeys.kt` `takeProfitTick()` + `PREFIX_TAKE_PROFIT_TICK="auto-tpt"` | KST 날짜 차원 멱등키 | §5-2 |
| `consumer/.../order/HoldingPositionIndex.kt` `HeldPosition.takeProfitPct` | 인메모리 hit-only(보유 0 즉시 return) — TP% 이미 채움 | §3 재사용 |
| `consumer/.../order/RestingSellOrderCanceller.kt` | open SELL 선검사(exclude=auto-tp) | §5-1 재사용 |
| `strategy-engine/.../entry/EntryPricePolicy.kt` `takeProfitLimitPrice()` | 익절가 단일 출처(KrxTickSize 내림) | §3 ② 재사용 |

**검증 의무(W1·W2)**: 위 구현이 ADR 0127 결정과 1:1인지 확인. diff가 0이면 코드 변경 없이 "정합 확인" 보고. 어긋나면 정정 PR(예: ENV 키명·멱등키 parts·orderTrigger 분류).

---

## 2. 발사 주체 (§3 — 신규 형제 클래스) · 검증 항목

`PositionTakeProfitTickWatcher`가 `PositionStopLossTickWatcher`와 **대칭 구조**임을 확인:

- [ ] 별도 `@Component` + 별도 Kafka group(`magicjar-consumer-position-take-profit`) — 손절 group과 offset/rebalance 격리.
- [ ] `@KafkaListener(topics=[KafkaTopics.Market.TICK])` 직접 listen — 4중 게이트 우회.
- [ ] `HoldingPositionIndex.heldBy(code)` hit-only — 보유 0 종목 tick은 DB 0건 즉시 return(손절 watcher와 동일 hot-path 비용).
- [ ] SL watcher와 **공유 컴포넌트** 재사용(클래스 통합 아님): `HoldingPositionIndex`·`RestingSellOrderCanceller`·`positionJpaRepository.findByUserIdAndCodeAndSource`(KIS 권위 잔량).
- [ ] 통합 `PositionExitTickWatcher` 미채택(장애 격리·토글 분리 보존) — 클래스 2개 유지 확인.

---

## 3. 익절가 단일 출처 (§3 ② · W2) · 검증 항목

- [ ] `PositionTakeProfitTickWatcher.takeProfitPriceOf(avgPrice, takeProfitPct)`가 `EntryPricePolicy.takeProfitLimitPrice` 위임 — 신규 호가계산 0.
- [ ] `AutoExitOrderCoordinator`(LIMIT 선주문)와 **같은 평단·같은 TP%면 같은 target** 산정(LIMIT/tick fail-safe 산정 동치). 평단 50,000 × (1+2%) = 51,000(KrxTickSize 내림) 양쪽 일치.
- [ ] `KrxTickSize.floorToTick` 내림 방향(SELL LIMIT 보수적 체결) — SL watcher `stopPriceOf`와 호가단위 출처 동일.

---

## 4. 발사 조건 (§2 ③) · 검증 항목

- [ ] `tickPrice ≥ takeProfitPrice` 발사(손절 `≤`와 부등호 반대).
- [ ] `tick.stale == true` 또는 `tick.price ≤ 0` skip(SL watcher 동일 정책).
- [ ] fallback price(KIS polling/no-tick) 취급: SL watcher의 `onFallbackPriceTick`처럼 명시 호출 경로가 있다면 동일 정책 적용 여부 확인. **현행 TP watcher는 `onTick`(stale skip)만** — fallback 직접 경로 미보유. ADR 0127 §6 게이트 우회 정책상 fallback 경로 추가는 후속 검토(SL과 완전 대칭 원하면 W1에서 `onFallbackPriceTick` 대칭 추가 — **단 default 현행 유지**, 자본 risk 낮음).

> ⚠ 비대칭 잔여 1건: SL watcher는 degraded polling/no-tick fallback 직접 경로(`onFallbackPriceTick`)를 보유하나 TP watcher는 미보유. ADR 0127 §3 ③은 "stale tick·fallback price를 SL watcher와 동일 정책"을 요구하므로, **완전 대칭을 위해 TP watcher에도 `onFallbackPriceTick` 추가를 W1 후속 후보로 명시**한다(WS tick 조용한 구간에서 익절도 fallback 가격으로 평가). default off가 아니라 SL과 동일 source 화이트리스트(`KIS-POLLING`·`KIS-NOTICK-FALLBACK`)로 켜는 방향. 자본 risk(익절 미발사 = 이익 미실현)는 손절(손실 확대)보다 작아 우선순위는 SL 다음.

---

## 5. 멱등 · 이중 SELL 방지 (§5) · 검증 항목

- [ ] 발사 전 `restingSellOrderCanceller.cancelRestingSells(exclude=auto-tp)` 선검사.
- [ ] open `auto-tp:` LIMIT 살아 있으면 `Result.OwnDuplicate`(canProceed=false) → 미발사 + 취소 0(LIMIT 보존).
- [ ] 수동/타전략 open SELL → `AbortedForeignSell` → 중단(침범 금지).
- [ ] 멱등키 = `auto-tpt:<digest(userId·strategyId·code·quantity·kstDate yyyyMMdd)>` — KST 날짜 차원(F1 영구차단 회피).
- [ ] 같은 KST 일자 연속 tick → 동일 키(OrderRouter `idem:order` 흡수 — 폭주 차단). 날 바뀌면 키 변경(재발사).
- [ ] `orderTrigger = AUTO_EXIT`(전량 익절이므로 PARTIAL_TAKE_PROFIT 아님 — LIMIT 선주문과 동일 분류).

---

## 6. 게이트 우회 범위 (§6) · 검증 항목

- [ ] 4중 게이트(readiness·market-hours·전략 status·1m 캔들 마감) 우회.
- [ ] min_hold_bars 우회(이익 실현은 보유기간 제약 무관). — **현행 watcher는 min_hold_bars를 보지 않음(인덱스 hit만 확인)** → 정합 확인.
- [ ] 전략 status 무관(PAUSED·DISABLED 보유분도 발사 — 자본 회수).
- [ ] 유령 포지션 가드는 **유지**: `position`(KIS 권위) 잔량 0이면 미발사(strategy_position>0 경합 유령 차단). source 차원 조회로 MOCK/REAL 공존 단일 결과.

---

## 7. L2(`ExpiredDayOrderSyncWorker`) 보완관계 (W3) · 운영 검증

| 레이어 | 닫는 결함 | L3가 cover? |
|--------|----------|-------------|
| **L2** | 만료 day-order LIMIT의 DB status가 ACK 영구 잔존 → 매도 skip·매도가능수량 가드 오류·체인 무결성 | 아니오(DB row 정합) |
| **L3**(본 spec) | resting LIMIT 만료 후 익절가 초과 보유분 미청산 | 부분(발사하지만 좀비 row 정리 못 함) |

**상호작용 risk(검증 필수)**: L2가 좀비 ACK를 CANCELLED로 정리하지 않으면, `RestingSellOrderCanceller`가 그 좀비를 open `auto-tp:`로 분류 → L3가 `OwnDuplicate`로 **미발사**한다(L2 미가동 시 L3 무력화). 따라서:

- [ ] L2(조회일자 버그픽스 포함)가 좀비 ACK를 정리한 뒤 L3가 정상 발사하는지 체인 검증.
- [ ] L2 미가동/지연 시 L3가 좀비를 LIMIT로 오인해 익절을 영구 skip하지 않는지 — L2가 L3의 **전제 조건**임을 운영 가드로 명시(둘 다 켜야 익절 보장 완성).

> L2 버그픽스는 본 spec 범위 밖(별도 진행)이나, L3 효과의 전제이므로 함께 운영·검증한다.

---

## 8. 검증 (§8) · qa E2E (W4)

### 8-1. 단위 테스트 (현행 14건 GREEN — backend-core 재실행 확인)

`./gradlew :consumer:test --tests "*PositionTakeProfitTickWatcherTest*"` GREEN.

매트릭스: tick≥익절가 발사 / +11% 급등(시그널 불성립) 발사 / 미도달 0 / open auto-tp 공존 미발사 / 보유 0 평가 0 / 토글 off / 같은 KST 동일키 / 날짜 변경 키변경 / 유령 미발사 / 수동 SELL 중단 / TP null 0 / stale 0 / takeProfitPriceOf 산정 / 비정상 null.

### 8-2. qa E2E 시나리오 (운영 재현 — 라이브 evidence 인용)

1. **오버나잇 보유 → 개장 후 익절 초과 → tick 발사 → 청산**(핵심 — D2 결함 직접 재현): 전날 익절 LIMIT 만료 상태 → 개장 후 tick 익절가 초과 → `auto-tpt:` MARKET intent → OrderRouter → KIS SELL → 체결 → 포지션 청산·HOLDING demand 해제.
2. **resting LIMIT 살아있는 동안 미발사** → LIMIT 만료(L2 CANCELLED 정리) 후 tick 초과 → L3 발사(L2→L3 체인).
3. **삼중 경로 동시 발화 1회만** — LIMIT + 1m SELL 시그널 + tick watcher 동시 → SELL 1건만 KIS 도달(`idem:order` 멱등).
4. **토글 off 회귀** — ENV false → 현행(시그널 의존 익절) 무손실 복귀.

**검증 의무**(`feedback_verification_strict`): gradle GREEN만으로 종결 금지. canary 라이브에서 `take-profit MARKET intent (tick watcher)` 로그 + KIS 체결 통보 + 포지션 반영 + Slack 알림 실증 evidence 인용.

---

## 9. 운영 · 롤백 (§9) · W5

- [ ] ENV `magicjar.autotrading.take-profit.tick-watch-enabled`(`MAGICJAR_TAKE_PROFIT_TICK_WATCHER_ENABLED`, default true) — `.env.example` 등재.
- [ ] 롤백: ① ENV false(재배포 불요·즉시 무력화) ② PR revert(클래스 add-only·손절/기존 익절 회귀 0).
- [ ] 관측: `take-profit MARKET intent (tick watcher)` WARN 로그(userId·code·tick·targetPrice·idemKey) + Slack 발사 알림.

---

## 10. 자원 audit (ADR 0102)

| 자원 | 신규 |
|------|------|
| Flyway | 0 |
| Kafka | topic 0 · group 1(`magicjar-consumer-position-take-profit`) |
| STOMP / REST | 0 |
| Redis | 키 prefix `auto-tpt:`(OrderRouter 기존 멱등 공간) |
| ENV | 1(`magicjar.autotrading.take-profit.tick-watch-enabled` default true) |

KIS 실주문 0(설계 문서·핫픽스 기 반영) · AI 호출 0(룰 기반 — ADR 0023 §2-4) · mock 합성 0.

---

## 11. CHANGELOG 슬롯 (헌법 §4 — sub-agent별 prepend)

| 슬롯 | 담당 | anchor |
|------|------|--------|
| `docs(adr): ADR 0127 TP tick fail-safe 발행 + spec` | architect | (본 PR) |
| `verify(consumer): PositionTakeProfitTickWatcher ↔ ADR 0127 정합 검증 + ENV 문서화` | backend-core | `tp-tick-failsafe-verify` |
| `verify(strategy-engine): 익절가 단일출처 LIMIT/tick 산정 동치 확인` | strategy-engine | `tp-tick-failsafe-price-source` |
| `test(qa): TP tick fail-safe E2E(오버나잇→개장→초과→발사→청산)` | qa | `tp-tick-failsafe-e2e` |
