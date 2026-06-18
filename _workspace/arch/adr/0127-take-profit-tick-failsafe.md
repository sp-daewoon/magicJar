# ADR 0127 — TP tick fail-safe(익절 tick 안전망) — SL/TP 비대칭 해소

- 상태: **Accepted (2026-06-18)** — REAL canary D2 hotfix B(`PositionTakeProfitTickWatcher`)로 **코드는 이미 라이브 반영 완료**(2026-06-12). 본 ADR은 그 핫픽스 결정을 SoT(ADR 0030)로 사후 정식 승격·정합 문서화한다(코드 0 touch · md only).
- 작성자: architect
- 결정자: 사용자(최고 관리자) — "장중엔 tick을 계속 받으니, 보유분의 현재가가 익절가보다 큰지 검사하고 넘쳤을 때는 시장가로 던진다 — 손절 tick watcher와 대칭으로 익절 tick fail-safe를 추가하라"(설계 방향 확정 결재). 새 방향이 아니라 **빠진 대칭의 보강**으로 명문화.
- 영역: 자동매매 청산 — 익절(take-profit) 체결 "항상" 보장(시그널/resting LIMIT 의존 공백 해소) — **자본 path (사용자 결재)**
- 입력 권위: ① 라이브 결함 evidence — REAL canary 후성(093370) 오버나잇 보유분이 익절선(+2%) 초과·+11% 급등 중에도 DSL exit 불성립으로 익절 미발사(2026-06-12). ② 현행 구현 `consumer/.../order/PositionTakeProfitTickWatcher.kt`(+테스트 14건) ③ 형제 선례 `PositionStopLossTickWatcher`(ADR 0114 R2-C2b ① 손절 tick 안전망). ④ 롱 온리 정책 `docs/architecture/policies/long-only-trading.md`(SELL=체결 보장).
- 관계:
  - **ADR 0114 R2-C2b amend(대칭 완성)** — 0114는 "손절 MARKET / 익절 LIMIT"(§3-3)를 LOCK하고 손절만 tick 안전망(`PositionStopLossTickWatcher`)을 신설했다. 본 ADR은 그 비대칭(익절은 시그널·resting LIMIT 의존)을 익절 tick 안전망으로 메운다. 0114 §3-3 익절 LIMIT 선주문은 **불변 유지**(주된 익절 경로) — 본 ADR은 LIMIT 만료 후 공백을 메우는 **하한 fail-safe**다(손절과 동일 위상).
  - **ADR 0115 보강** — 유령 포지션 발사 가드(§P2-3 position 권위 잔량), replay-safe, intent 멱등 패턴을 익절 watcher가 그대로 재사용. F1 고정 해시 함정(REJECTED 후 영구 차단)을 키 KST 날짜 차원으로 회피(0115 §P2-1 cooldown 정신 계승).
  - **롱 온리 정책(헌법 §13) 정합** — 매도=보유분 청산. SELL은 cap 검증 면제 + 가격 산정 실패 시 MARKET("체결 보장 우선"). 본 ADR MARKET 채택의 정책 근거.
  - **L2(`ExpiredDayOrderSyncWorker`) 보완관계** — 본 ADR(L3 tick fail-safe)이 있어도 좀비 ACK DB 정합(L2)은 별개로 필요(§7 역할 경계 표).
- README 인덱스: §0 총건수·최근10건 prepend·§6 헤더 건수·§14 row 동시 갱신(ADR 0030 SoT 정합).

---

## 1. 배경 — 손절은 tick 안전망, 익절은 비대칭 공백

ADR 0114는 청산을 "항상" 보장하기 위해 4중 게이트(readiness·market-hours·전략 status·1m 캔들 마감)를 우회하는 **손절 tick 안전망**(`PositionStopLossTickWatcher`)을 신설했다. `market.tick.v1`을 직접 listen → tick 1건이라도 손절가 도달 시 즉시 MARKET SELL. consumer가 곧 스탑로스 장치라는 §1-2 결정의 본체다.

반면 **익절(take-profit)에는 그런 tick 안전망이 없었다.** 익절 경로는 두 갈래뿐이었다:

1. **진입 직후 resting 익절 LIMIT 선주문** (`AutoExitOrderCoordinator`) — BUY 체결 시 평단×(1+TP%)로 KIS 서버에 SELL LIMIT을 걸어둔다. 시스템 다운에도 KIS가 익절을 보장.
2. **1분봉 경계 SELL 시그널 재발행** (`SignalGeneratedKafkaListener.publishSell`) — DSL exit 조건(예: close<ma10)이 1m 캔들 마감에 참이 될 때만.

문제는 ① resting LIMIT이 **KIS 국내주식 day-order라 장 마감에 만료**된다는 점이다. **오버나잇 보유** 시 다음 날 LIMIT이 사라진 상태에서, ② DSL exit 조건이 참이 되지 않으면 가격이 익절가를 훌쩍 넘어도(+11% 급등) **익절이 영영 안 나간다.**

### 1-1. 라이브 결함 evidence (canary 2026-06-12)

후성(093370) 오버나잇 보유분이 개장 후 익절선(평단+2%)을 넘어 **+11%까지 급등**하는 동안, 전날 익절 LIMIT은 만료됐고 DSL exit(close<ma10 류)는 불성립이라 익절이 발사되지 않았다. 09:29에야 가격이 일시 꺾여 SELL 시그널이 떠준 "운"으로 청산됐다. 손절은 tick으로 감시하면서 **익절만 시그널 의존**이라 구조적으로 비대칭이었다.

> 핵심: 손절(`tick ≤ stopPrice`)과 익절(`tick ≥ takeProfitPrice`)은 부등호 방향만 반대일 뿐 "tick으로 보유분을 직접 감시한다"는 안전망 위상이 동일하다. 손절에만 그 위상이 있고 익절엔 없던 것이 결함의 본질이다.

---

## 2. 결정 — 익절 tick fail-safe 신설 (손절과 대칭)

`market.tick.v1`을 직접 listen해 **보유분 현재가가 익절가 이상이면 즉시 MARKET SELL**하는 익절 안전망을 둔다. 4중 게이트를 우회한다(이익 실현은 보유 기간·전략 status 제약 무관 — §6). 주된 익절 경로(진입 직후 LIMIT 선주문)는 그대로 두고, 본 watcher는 LIMIT이 살아 있으면 발사하지 않으며 LIMIT 만료·부재 시에만 공백을 메우는 **하한 fail-safe**다(손절 watcher가 1m 경로의 하한인 것과 동일).

### 2-1. 설계 결정 매트릭스 (9항목)

| # | 의제 | 결정 | 근거 |
|---|------|------|------|
| ① | **발사 주체** | **신규 형제 `PositionTakeProfitTickWatcher`** | §3 — SL watcher 확장(분기 욱여넣기) 기각, 통합 `PositionExitTickWatcher` 기각 |
| ② | **익절가 단일 출처** | `EntryPricePolicy.takeProfitLimitPrice`(strategy-engine) 재사용 → `KrxTickSize` 호가단위 내림 | LIMIT 선주문(`AutoExitOrderCoordinator`)과 **동일 산정** — 중복 호가계산 금지 |
| ③ | **발사 조건** | `tickPrice ≥ takeProfitPrice` 발사. stale tick·`price≤0` skip(SL watcher 동일) | 손절과 부등호만 반대(`≤`→`≥`) |
| ④ | **주문 유형** | **MARKET** (안전판 §4) | §4 — 이미 익절가 초과 상태 발사라 MARKET이어도 ≥익절가 체결 + 롱 온리 SELL=체결 보장 |
| ⑤ | **멱등·이중 SELL** | `RestingSellOrderCanceller`(`exclude=auto-tp`) 선검사 + `auto-tpt:` 멱등키(KST 날짜 차원) | §5 — LIMIT 살아 있으면 미발사·삼중 경로 1회만 |
| ⑥ | **게이트 우회** | min_hold_bars·4중 게이트 우회(이익 실현은 보유기간 제약 무관) | §6 |
| ⑦ | **L2와 관계** | 보완(L3 fail-safe ≠ L2 DB 정합) | §7 역할 경계 표 |
| ⑧ | **검증 범위** | qa E2E(오버나잇→개장→초과→발사→청산) + 단위 14건 | §8 |
| ⑨ | **롤백** | ENV `magicjar.autotrading.take-profit.tick-watch-enabled`(default true) | §9 |

---

## 3. ① 발사 주체 — 신규 형제 `PositionTakeProfitTickWatcher` (권장안)

3안을 평가했다:

| 안 | 내용 | 평가 | 사유 |
|----|------|------|------|
| A. SL watcher 확장 | `PositionStopLossTickWatcher.evaluate`에 익절 분기 추가 | ✗ | 손절 평가 방향(`≤`)·익절(`≥`)·발사 키 의미(`auto-sl` vs `auto-tpt`)·cooldown signature가 달라 한 클래스에 분기를 욱여넣으면 **SRP·OCP 위반**. 한쪽 변경이 다른쪽 회귀 risk. |
| B. 통합 `PositionExitTickWatcher` | 손절·익절을 하나로 합쳐 둘 다 평가 | ✗ | DRY를 명목으로 들지만 실제 공유 코드(인덱스 hit-only·open SELL 가드·position 권위 가드)는 이미 **컴포넌트로 추출**돼 양쪽이 재사용한다. 합치면 두 자본 경로가 한 listener·한 group에 묶여 장애 격리(한쪽 결함이 다른쪽 발사 차단)·배포 토글 분리가 무너진다. |
| **C. 신규 형제 클래스** ✅ | `PositionTakeProfitTickWatcher` 별도 `@Component`·별도 group | **채택** | SL watcher와 **대칭 구조**(같은 hot-path·같은 가드 컴포넌트 재사용)이되 SRP·독립 토글·장애 격리 보존. DRY는 **공유 컴포넌트 재사용**으로 달성(클래스 통합이 아니라). |

**DRY는 공유 컴포넌트로, SRP는 클래스 분리로** — 두 가치를 모두 만족하는 경계다. 재사용 컴포넌트:

- `HoldingPositionIndex`(인메모리 hit-only — 보유 0 종목 tick은 DB 0건 즉시 return). `HeldPosition.takeProfitPct`는 이미 refresh·onFillRefresh가 채운다(인덱스가 TP watcher를 받을 준비 완료).
- `RestingSellOrderCanceller`(open SELL 선취소 + fail-closed 수동/타전략 침범 금지).
- `positionJpaRepository.findByUserIdAndCodeAndSource`(KIS 권위 잔량 유령 포지션 가드 — source 차원).

별도 Kafka group(`magicjar-consumer-position-take-profit`)으로 손절 group과 offset·rebalance 격리.

---

## 4. ④ 주문 유형 — MARKET (권장안 + 슬리피지 안전판)

| 안 | 평가 | 사유 |
|----|------|------|
| **MARKET** ✅ | **채택** | ① 본 watcher는 `tick ≥ 익절가` 이미 **초과 상태에서만 발사** → MARKET이어도 체결가 ≥ 익절가(기대 이익 보장). ② 롱 온리 정책: SELL=체결 보장 우선, 가격 산정 실패 시 MARKET("체결 보장 우선" — 정책 명문). ③ 손절 watcher가 순수 MARKET인 점과 **대칭**(fail-safe는 체결 확실성 우선). ④ fail-safe 발사 시점에 호가 재조회·marketable LIMIT 산정을 끼우면 또 다른 stale·미체결 공백을 만든다(안전망의 목적 자기 부정). |
| marketable LIMIT | ✗ | 호가 가용·stale 게이트(`EntryPricePolicy` 진입 경로 5단계)가 SELL 청산을 다시 막을 수 있다. 청산은 가격 보호가 아니라 체결 보장이 우선(롱 온리). 진입(자본 나감)에만 marketable LIMIT을 쓰고 청산엔 안 쓴다는 0114 비대칭과 정합. |

### 4-1. 극단 슬리피지 안전판 (얇은 호가·상한가)

MARKET은 얇은 호가(NXT·소형주)·상한가 부근에서 슬리피지·미체결 risk가 있다. 단 **익절은 손절과 risk 방향이 반대**다 — 익절 발사 시점은 가격이 오른 상태(유동성·매수세 우호적)라 슬리피지 하방 risk가 손절보다 작다. 따라서:

- **R1(현행)**: MARKET 발사 유지(체결 보장 우선). 0114 §3-3 손절 MARKET의 NXT 슬리피지 risk 명기와 동일 위상으로, 안전판은 ENV 토글(§9)과 운영 관측(Slack 발사 로그 `take-profit MARKET intent`)으로 cover.
- **후속(post-MVP) 후보**: 상한가 임박·호가 잔량 임계 시 marketable LIMIT cap 전환은 `EntryPricePolicy` thin-liquidity 게이트(U-2 예약)가 활성화되면 청산 경로에도 선택 적용 검토. **단 fail-safe의 체결 보장 우선 원칙은 default 불변**(LIMIT 전환이 또 다른 미체결 공백을 만들지 않도록 게이트로만).

---

## 5. ⑤ 멱등 · 이중 SELL 방지 (삼중 경로 1회만)

익절은 ① resting LIMIT 선주문 ② 1m DSL SELL 시그널 ③ 본 tick watcher 삼중 경로가 동시 발화할 수 있다. 1회만 나가도록 2중 가드:

### 5-1. open `auto-tp:` LIMIT 공존 가드 (LIMIT 우선)

`RestingSellOrderCanceller.cancelRestingSells(exclude=auto-tp)` 선검사. open `auto-tp:` LIMIT이 하나라도 살아 있으면 `Result.OwnDuplicate`(canProceed=false)로 **미발사**(취소도 0 — LIMIT 보존). LIMIT이 먼저 익절을 보장 중이면 tick MARKET은 발사하지 않는다. LIMIT이 만료·부재일 때만 watcher가 공백을 메운다. 같은 종목의 수동·타전략 open SELL은 `RestingSellOrderCanceller`의 fail-closed 가드(`AbortedForeignSell`)가 처리(우리가 모르는 SELL 의도 침범 금지).

### 5-2. `auto-tpt:` 멱등키 — F1 고정 해시 함정 회피 (KST 날짜 차원)

키 = `auto-tpt:<digest(userId·strategyId·code·quantity·**kstDate(yyyyMMdd)**)>`(`AutoOrderIdempotencyKeys.takeProfitTick`). OrderRouter `idem:order:{key}` Redis SETNX가 동일 키 재발행을 흡수한다.

**왜 KST 날짜 차원인가** — 손절 키(`auto-sl:`)는 (qty·updatedAtEpoch) signature라 상태가 안 바뀌면 종일 같은 digest다. 익절을 같은 패턴으로 두면, KIS가 한 번 REJECTED한 키(예: 장 시작 직후 일시 잔량 부족)가 `idem:order:{key}`에 남아 **그날 내내 익절 재발사를 영구 차단**한다(canary D2 F1 evidence). 익절은 가격이 익절가 이상으로 머무는 한 계속 발사 가능해야 하므로, 키에 **KST 당일 날짜**를 섞어 하루 1회 발사로 한정하되 **날이 바뀌면(또는 수량 변동) 자연 재발사**되게 한다(영구 차단 0). 손절 키는 본 ADR에서 건드리지 않는다(별도 의제).

> SL watcher의 cooldown(상태 signature + 30s)과 TP watcher의 키 KST 날짜 차원은 둘 다 "같은 상태 무한 재발사 차단 + 정당한 다음 발사는 허용"이라는 동일 목표를 경로 특성에 맞게 푼 것이다. TP는 가격 잔류 동안 재발사 필요성이 SL보다 커서 cooldown 대신 키 날짜 차원을 택했다.

---

## 6. ⑥ 게이트 우회 범위 — 이익 실현은 보유기간 제약 무관 (우회)

손절 watcher와 동일하게 **4중 게이트(readiness·market-hours·전략 status·1m 캔들 마감)를 우회**한다. 추가로:

- **min_hold_bars(최소 보유 봉수) 우회** — 이익 실현은 보유 기간 제약과 무관해야 한다. min_hold_bars는 "성급한 손절·휩쏘 방지"가 목적이지 익절을 막을 이유가 없다(익절은 목표 도달 = 보유 성공). 따라서 TP watcher는 min_hold_bars를 보지 않는다.
- **전략 status 무관** — PAUSED 전략이라도 실보유(position>0)면 익절 발사(보유 보호 의도 보존 — ADR 0115 §P2-3 손절 정합). DISABLED도 보유분 익절은 발사(자본 회수는 막지 않음).
- **fail-closed는 신규 자본 투입(BUY)에만** — 0114 P1 정신 계승. 청산(SELL)은 readiness 미충족이어도 진행.

단 **유령 포지션 가드는 우회하지 않는다** — `position`(KIS 권위) 잔량 0이면 미발사(strategy_position>0 인데 실보유 0인 보정 직전 경합 구간 유령 익절 폭주 차단 — 손절 watcher와 동일).

---

## 7. ⑦ L2(`ExpiredDayOrderSyncWorker`)와의 역할 경계

본 ADR(L3 tick fail-safe)과 L2(좀비 ACK DB 정합)는 **다른 결함을 닫는 보완 레이어**다. L3이 있어도 L2는 별개로 필요하다.

| 레이어 | 닫는 결함 | 메커니즘 | L3가 cover하나? |
|--------|----------|----------|-----------------|
| **L2** `ExpiredDayOrderSyncWorker` | 만료된 day-order LIMIT의 DB `status`가 `ACK`로 **영구 잔존** → 다음 날 SELL 시그널이 그 좀비 ACK를 "살아있는 익절 선주문"으로 오판해 매도 skip + 매도가능수량 가드 오류 + 체인 무결성 깨짐 | KIS 일별체결 대조 → FILLED/CANCELLED 동기화(DB 정합) | **아니오** — L3는 주문 발사 경로, L2는 DB row 상태 정합 |
| **L3** `PositionTakeProfitTickWatcher`(본 ADR) | resting LIMIT 만료 후 익절가 초과 보유분이 시그널 불성립으로 **미청산** | tick ≥ 익절가 → MARKET SELL | **부분만** — L3는 익절을 발사하지만 좀비 ACK row 자체는 정리 못 함 |

**상호작용**: L2가 좀비 ACK를 CANCELLED로 정리하지 않으면, `RestingSellOrderCanceller`가 그 좀비를 open `auto-tp:`로 분류 → L3가 `OwnDuplicate`로 **미발사**할 수 있다(L2 미가동 시 L3 무력화 risk). 즉 L2(DB 정합)가 L3(발사)의 전제 조건이기도 하다. 두 레이어를 함께 둬야 익절 보장이 완성된다.

> L2의 조회일자 버그픽스(별도 진행)는 본 ADR 범위 밖이나, L3 효과의 전제이므로 함께 운영·검증한다(§8 E2E에 좀비 ACK 정리 → L3 발사 시나리오 포함).

---

## 8. ⑧ 검증 범위

### 8-1. 단위 테스트 매트릭스 (`PositionTakeProfitTickWatcherTest` — 14건, 현행 GREEN)

| 시나리오 | 기대 |
|----------|------|
| tick ≥ 익절가 + open SELL 0 | MARKET intent 1회(`auto-tpt:` 키 + KST 날짜) |
| 익절가 초과(+11% 급등) 시그널 불성립 | 발사(D2 결함 재현 — tick만으로 청산) |
| 익절가 미도달(tick < target) | 주문 0 |
| open `auto-tp:` LIMIT 공존 | 미발사 + 취소 0(LIMIT 보존) |
| 보유 0(인덱스 miss) | 평가 0 · DB 조회 0 |
| 토글 off | no-op(시그널 의존 익절 회귀) |
| 같은 KST 일자 연속 tick | 동일 키(하루 1회 한정 — 폭주 차단) |
| KST 날짜 변경 | 키 변경(F1 영구 차단 회피) |
| 유령 포지션(position 권위 0) | 미발사 |
| 수동 SELL 존재 | 중단(취소 0 · 발화 0) |
| 익절 미설정(takeProfitPct null) | 발화 0 |
| stale tick | 평가 0 |
| `takeProfitPriceOf` 평단 50,000×2% | 51,000(호가단위 정렬) |
| `takeProfitPriceOf` 비정상 입력 | null |

### 8-2. qa E2E/시나리오 (운영 재현)

1. **오버나잇 보유 → 개장 후 익절 초과 → tick 발사 → 청산** (핵심 — D2 결함 직접 재현): 전날 익절 LIMIT 만료 상태에서 개장 후 tick이 익절가 초과 → `auto-tpt:` MARKET intent → OrderRouter → KIS SELL → 체결 → 포지션 청산·HOLDING demand 해제 확인.
2. **resting LIMIT 살아 있는 동안 미발사** → LIMIT 만료(L2 CANCELLED 정리) 후 같은 종목 tick 초과 → L3 발사(L2→L3 체인).
3. **삼중 경로 동시 발화 1회만** — LIMIT + 1m SELL 시그널 + tick watcher 동시 → `idem:order` 멱등으로 SELL 1건만 KIS 도달.
4. **토글 off 회귀** — ENV false 시 현행(시그널 의존 익절)으로 무손실 복귀.

> 운영 검증 의무(메모리 `feedback_verification_strict`): PR 머지·gradle GREEN만으로 종결 금지. canary 라이브에서 `take-profit MARKET intent (tick watcher)` 로그 + KIS 체결 통보 + 포지션 반영 + Slack 발사 알림 실증 evidence 인용.

---

## 9. ⑨ 운영 리스크 · 롤백

| 항목 | 내용 |
|------|------|
| ENV 토글 | `magicjar.autotrading.take-profit.tick-watch-enabled`(`MAGICJAR_TAKE_PROFIT_TICK_WATCHER_ENABLED`, default **true**). false면 watcher no-op → 현행(시그널 의존 + resting LIMIT) 익절로 즉시 회귀. |
| 롤백 경로 | ① 1차 — ENV false(재배포 불요, 즉시 무력화). ② 2차 — PR revert(클래스 add-only라 손절·기존 익절 경로 회귀 0). |
| SLO 영향 | tick hot-path는 인덱스 hit-only(보유 0 종목 DB 0건 즉시 return)라 tick 처리 latency 영향 미미. 발사 시에만 open SELL 조회·취소. 손절 watcher와 동일 부하 프로파일. |
| 자본 risk | MARKET 슬리피지(§4-1) — 익절은 가격 상승 상태 발사라 하방 risk 작음. 유령 포지션·이중 SELL은 §5·§6 가드로 차단. |
| 관측 | `take-profit MARKET intent (tick watcher)` WARN 로그(userId·code·tick·targetPrice·idemKey) + Slack 발사 알림 + (기존) OrderRouter 멱등 skip 카운터. |

---

## 10. 자원 audit (ADR 0102 — 5단계)

| 자원 | 점유 | 신규 |
|------|------|------|
| Flyway | — | **0** (영속 변경 없음 — 인메모리 인덱스·기존 order_request 재사용) |
| Kafka | `market.tick.v1` consume(신규 group `magicjar-consumer-position-take-profit`) · `order.intent` produce(`publishExitIntent` 재사용) | topic 0 · group 1(소비 격리) |
| STOMP | — | 0 |
| REST | — | 0(KIS 신규 endpoint 0 · tick은 기존 WS 인입 재사용) |
| Redis | `idem:order:{auto-tpt:*}`(OrderRouter 기존 멱등 키 공간) | 키 prefix 1(`auto-tpt:`) |
| ENV | `magicjar.autotrading.take-profit.tick-watch-enabled` | 1(default true) |

KIS 실주문 0(설계 문서) · AI 호출 0(룰 기반 자본 흐름 — ADR 0023 §2-4) · mock 합성 0.

---

## 11. 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-06-18 | **ADR 0127 신설 — TP tick fail-safe(익절 tick 안전망) Accepted (최고 관리자 결재 — SL/TP 비대칭 해소)**. canary D2 hotfix B(`PositionTakeProfitTickWatcher`, 2026-06-12 라이브 반영)를 SoT로 사후 정식 승격·정합 문서화(md only·코드 0 touch). **9 결정 LOCK**: ①발사주체=신규 형제 `PositionTakeProfitTickWatcher`(SL 확장·통합 watcher 기각·DRY는 공유 컴포넌트 SRP는 클래스 분리) ②익절가 단일출처=`EntryPricePolicy.takeProfitLimitPrice`(LIMIT 선주문과 동일 산정·`KrxTickSize` 내림) ③발사조건=`tick≥익절가`(손절과 부등호만 반대·stale skip) ④주문유형=**MARKET**(이미 익절가 초과 발사·롱 온리 SELL 체결보장·손절 MARKET 대칭·슬리피지 안전판 ENV/관측·후속 thin-liquidity 게이트 검토) ⑤멱등=`RestingSellOrderCanceller`(exclude=auto-tp open LIMIT 우선) + `auto-tpt:` 키 KST 날짜 차원(F1 영구차단 회피) ⑥게이트 우회(이익실현 보유기간 제약 무관·min_hold_bars 우회·status 무관·유령 가드는 유지) ⑦L2(`ExpiredDayOrderSyncWorker`) 보완관계(L3≠L2 DB정합·L2가 L3 전제) ⑧검증=단위14+qa E2E(오버나잇→개장→초과→발사→청산) ⑨롤백=ENV false 즉시/revert add-only. ADR 0114 R2-C2b amend(손절 tick 안전망의 익절 대칭 완성·익절 LIMIT 선주문 불변) + ADR 0115 가드 패턴 재사용 + 롱 온리(헌법 §13) 정합. 자원 audit(ADR 0102): Flyway/STOMP/REST/KIS/AI/mock 0 · Kafka group 1(소비 격리) · ENV 1(default true) · Redis 키 prefix `auto-tpt:`. spec `_workspace/specs/2026-06-18-tp-tick-failsafe.md` SoT. 자본 결정권 0(설계 문서). (architect, opus 4.8) |
