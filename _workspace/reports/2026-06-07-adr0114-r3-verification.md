# ADR 0114 R3 — 백테스트 슬리피지 정합 + 시나리오 검증 일괄 (작성: strategy)

> 본 사이클의 마지막 라운드(개발 룰 4 — 코드 먼저, 테스트 마지막 일괄). Codex 비교 머지의 최종 증빙.
> 브랜치 `agent/orchestrator/order-execution-vigilance` (R1·C1·C2a·C2b 위에 add-only).

---

## 1. 백테스트 진입 정책 정합 (작업 1)

### 1-1. 변경 요지
- `JvmBacktestEngine`이 전략에 `entryPolicy`가 있으면 백테스트 진입도 **live·리플레이와 동일한**
  `EntryPricePolicy.evaluate` **순수 함수**로 캡 판정한다. `ScaleInExitCalculator` 공유 선례 1:1 — 별도 구현 0.
- 적용 경로 2곳: ① 비-scaleIn BUY 경로 ② scale-in `EnterSlice`(각 분할 회차) 경로.
- 진입 정책 미설정(`entryPolicy == null`) 전략은 **기존 즉시 체결 모델 그대로**(하위호환 LOCK, 스킵 0).

### 1-2. 호가 부재 보수 근사 — 정직한 한계 (코드 주석 + 본 보고 명기)
백테스트에는 호가(ask1/bid1) **시계열이 없다**. 따라서 `EntryPriceInput`을 다음과 같이 근사 주입한다:

| 입력 | 백테스트 근사값 | 결과 |
|------|----------------|------|
| `priceAtSignal` | 봉 평가가(evalPrice) | cap 산정 base |
| `ask1` | **슬리피지 적용 BUY 체결가**(= evalPrice × (1+slippageRate)) | 시장 진입 실제 지불가에 가장 근접 |
| `bid1` | ask1과 동일(spread 0) | 스프레드 게이트 사실상 **no-op** |
| `quoteAsOf` | evalAt(=candleTs) | stale 게이트 항상 fresh → **no-op** |

→ 백테스트는 진입 정책 중 **슬리피지 캡 게이트만** 의미 있게 재현한다. 스프레드/stale 스킵은 **항상 0**이며,
이는 live에서만 발화한다(near-fill 가정). 이 한계는 `JvmBacktestEngine.entryPolicySkip` KDoc과
`BacktestEntrySkip` KDoc에 LOCK으로 박아두었다.

### 1-3. 결과 노출 (add-only)
`BacktestResult.Success.entrySkip: BacktestEntrySkip`(default `NONE`) 신설 — 사유별 카운트
(`noQuote`·`quoteStale`·`spreadTooWide`·`priceCapExceeded`·`total`). 기존 result 구조 0 변경(default 값으로
모든 기존 호출부·역직렬화 하위호환). 컨트롤러/DTO 노출은 frontend 라운드 위임(domain result 레벨까지가 R3 범위).

### 1-4. 검증 테스트 (`JvmBacktestEngineTest`, 신규 3건)
| 테스트 | 시나리오 | 단언 |
|--------|----------|------|
| `entryPolicy 캡 0퍼센트면 슬리피지 체결가가 상한 초과 → 진입 스킵 집계` | maxEntrySlippagePct=0 → cap=priceAtSignal, ask1=price×1.0005 > cap | trades 0 · `priceCapExceeded ≥ 1` · spread/stale/noQuote 0 |
| `entryPolicy 캡 넉넉하면 진입 정상 + 스킵 0` | maxEntrySlippagePct=1.0% > 슬리피지 0.05% | BUY 발생 · `entrySkip.total == 0` |
| `entryPolicy null이면 진입 정책 미적용(스킵 0 · 하위호환)` | entryPolicy 없음 + 슬리피지 활성 | `entrySkip.total == 0` · BUY 정상 |

---

## 2. 분할매수 TP 재정렬 시나리오 — 사용자 지정 숫자 검증 ★ (작업 2)

대상: `AutoExitOrderCoordinator`(C2b). 익절 **+3%** 전략. TP LIMIT 가격 = `EntryPricePolicy.takeProfitLimitPrice(평단, 3%)`
= `floor_to_KRX_tick(평단 × 1.03)`. KRX 호가단위: 50,000~199,999원 구간 → **100원**.

### 2-1. 단계별 실제 산출 숫자 (사용자 보고용 — 그대로 읽으시면 됩니다)

| 단계 | 입력 | 평단 | raw(평단×1.03) | floor(100원 단위) | 발행 TP |
|------|------|------|----------------|-------------------|---------|
| **1차 BUY** | 10주 @ 100,000 | 100,000 | 103,000.00 | **103,000** | LIMIT 10주 @ 103,000 |
| **2차 BUY(scale-in)** | +10주 @ 95,000 | (1,000,000+950,000)/20 = **97,500** | 100,425.00 | **100,400** | 기존 10@103,000 **취소** → 신규 LIMIT **20주 @ 100,400** |
| **부분체결 변형** | 2차가 10주 중 **4주만** 체결 | (10×100,000+4×95,000)/14 = 1,380,000/14 = **98,571.428571** | 101,528.57… | **101,500** | 신규 LIMIT **14주 @ 101,500** |
| **정합 변형** | 평단 변동 없음(100,000·10주 그대로) | 100,000 | 103,000.00 | 103,000 | **재발행 0**(취소 0 · intent 0) |

핵심: 2차 평단 97,500 → `97,500 × 1.03 = 100,425` → 100원 호가단위 **내림 = 100,400** (사용자 작업지시 숫자와 일치).
부분체결 평단 98,571.428571 → `× 1.03 = 101,528.57…` → 내림 **101,500**.

### 2-2. 검증 테스트 (`AutoExitOrderCoordinatorTest`, 신규 4건)
| 테스트 | 단언 |
|--------|------|
| `R3 시나리오4-A — 1차 BUY 10주@100,000 → auto-tp LIMIT 10주@103,000 발행` | qty=10 · limitPrice=103,000 |
| `R3 시나리오4-B — 2차 BUY 10주@95,000(평단 97,500) → 기존 TP 취소 + 신규 TP 20주@100,400` | cancel 1 · cancelled publish 1 · qty=20 · limitPrice=**100,400** |
| `R3 시나리오4-C — 2차 부분체결(…14주 평단 98,571.428571) → 신규 TP 14주@101,500` | cancel 1 · qty=14 · limitPrice=**101,500** |
| `R3 시나리오4-D — 평단 변동 없음(정합) → 취소·재발행 0` | cancel 0 · publishExitIntent 0 |

(기존 coordinator 테스트는 평단 50,000/60,000·tp 5% 케이스를 별도 커버 — 무수정 통과.)

---

## 3. C2b 8 시나리오 → 테스트 매핑 (작업 3)

중복 작성 금지 원칙 — **이미 커버된 항목은 기존 테스트 매핑**, gap만 신규 작성.

| # | 시나리오 | 테스트 파일 · 메서드 | 상태 |
|---|----------|----------------------|------|
| 1 | tick 손절 → MARKET SELL 1건 멱등(1m 경로 중복 0) | `PositionStopLossTickWatcherTest` · `손절가 도달 + open SELL 없음 — MARKET intent` + `이미 open auto-sl SELL 존재 — 중복 발화 0` + **신규** `R3 시나리오1 — 손절 발동은 단일 MARKET SELL 멱등` | 기존+신규 |
| 2 | DISABLED(보호상태) 전략 보유분 SELL 평가 발화·BUY 0 | `RealtimeSignalGeneratorTest` · `BUY_BLOCKED 전략도 sellCondition 충족 시 SELL 시그널은 저장하고 발행` + `BUY_BLOCKED 전략은 신규 BUY 시그널을 저장하거나 발행하지 않는다` | 기존(주1) |
| 3 | BUY 체결 → auto-tp LIMIT 즉시 발행 | `AutoExitOrderCoordinatorTest` · `BUY 체결 — … TP LIMIT 선주문(auto-tp)` + **신규** `R3 시나리오4-A` | 기존+신규 |
| 4 | 분할매수 TP 재정렬(=작업 2) | `AutoExitOrderCoordinatorTest` · `R3 시나리오4-A/B/C/D`(신규) | **신규** |
| 5 | slot 포화 시 HOLDING unsubscribe 0 | `RealtimeSubscriptionRouterTest` · `slot 포화 + 신규 진입 시 HOLDING 종목은 evict 후보에서 제외된다 (ADR 0114 P3 LOCK)` | 기존 |
| 6 | 수동 SELL 존재 시 손절 중단 + 알림 | `PositionStopLossTickWatcherTest` · `수동 SELL 존재 — 손절 중단 (취소 0 · 발화 0)` | 기존(주2) |
| 7 | sell-enabled=false NXT 손절 양 경로 발동 | 1m 경로: `RealtimeSignalGeneratorTest` · `ADR 0114 P4 — sell-enabled=false면 장전 boundary도 SELL 청산 평가는 진행` / tick 경로: `PositionStopLossTickWatcherTest` · `R3 시나리오8`(신규, 게이트 무관 발동) | 기존+신규 |
| 8 | readiness FAILED 중 tick watcher 우회 발동 | **신규** `PositionStopLossTickWatcherTest` · `R3 시나리오8 — watcher는 readiness·market-hours·전략status 게이트와 무관하게 tick만으로 발동` (1m readiness 억제는 `RealtimeSignalGeneratorTest`가 별도 커버) | 기존+신규 |

> **주1 — 시나리오 2 의미 명확화**: 도메인 `StrategyStatus.isEvaluable()`은 `ACTIVE`·`BUY_BLOCKED`만 true이고
> **순수 `DISABLED`는 평가 path 자체에 진입하지 않는다**(SELL도 안 됨). 리스크 자동정지의 "보유분 청산은 살리고 BUY만
> 막는" 보호 상태는 `BUY_BLOCKED`다. C2b 보고의 "DISABLED 전략 보유분 SELL"은 이 보호상태(BUY_BLOCKED) 의미로
> 해석하며, 기존 `RealtimeSignalGeneratorTest`가 정확히 그 분기를 커버한다. (추가로 C2b의 P2 position-driven
> 청산은 DISABLED-with-position을 `findSellEvalByCodeAndInterval`이 포함 — backend-core 라운드 범위.)
>
> **주2 — "알림"**: 수동/타전략 SELL 침범 시 `log.error`(P0)로 알린다. 테스트는 손절 중단(취소 0·발화 0)을 단언하며,
> 로그 자체는 side effect라 단언 대상에서 제외(중단 보장이 자본 안전의 본질).

### 시나리오 리플레이(ScenarioReplayService · ADR 0107) 재사용 판단
C2b 8 시나리오는 **consumer Kafka 리스너의 발화/멱등/취소 동작**이 본질이라 리플레이 ledger(체결 시퀀스 재현)보다
**단위/슬라이스 테스트(MockK)** 가 직접적이고 결정론적이다. 리플레이는 다중 lot 체결 시퀀스의 평단·진행도 재현에 강점이
있어 백테스트 진입 정합(작업 1)과 결이 맞지만, 본 라운드는 진입 게이트가 `EntryPricePolicy` 단일 함수로 수렴하므로
순수 함수 단위 테스트로 충분하다(리플레이는 별도 회귀 자산으로 유지).

---

## 4. 품질 게이트

| 게이트 | 결과 |
|--------|------|
| `./gradlew check` (전체 모듈) | **GREEN** (exit 0) |
| `:api:bootJar` · `:batch:bootJar` · `:consumer:bootJar` | **GREEN** (3종 exit 0) |
| `./gradlew ktlintCheck` | **GREEN** (exit 0) |
| 기존 테스트 무수정 통과 | 확인(신규 추가만, 단언 반전·삭제 0) |
| AI 호출 / KIS 호출 / mock 합성 | 0 / 0 / 0 |

신규 테스트 합계: 백테스트 진입 정책 3 + coordinator 시나리오4 4 + tick watcher 2 = **9건**.

---

## 5. 미해결 / 후속 (정직 기록)

- **P5 — 1m 폴링 청산 백업 (post-MVP)**: ADR 0114 §4-5. tick·1m close 양 경로가 모두 끊긴 극단 상황의
  최후 폴링 백업은 본 사이클 범위 밖(스코프 LOCK).
- **PartialExit 선주문 위임**: 다단계 분할 익절(`partialExits`)은 stage state(`executedExitStages`)를
  `ScaleInExitCalculator`가 소유하므로 `AutoExitOrderCoordinator`는 **단일 전량 익절(`takeProfitPct`)만 선주문**하고
  분할 익절 plan 전략은 1m 경로가 stage 발화한다(이중 발화 회피 — 의도적 분기). resting 다단계 선주문화는 별도 의제.
- **백테스트 호가 게이트 한계**: §1-2 그대로 — 스프레드/stale 게이트는 호가 시계열 부재로 백테스트에서 재현 불가.
  분봉 호가 스냅샷 적재가 생기면 근사 → 실측으로 승격 가능(데이터 정책 의제, market-data 협의 필요).
- **entrySkip API/UI 노출**: domain result까지 add-only 노출 완료. 컨트롤러 DTO·백테스트 리포트 화면 표시는
  frontend 라운드 위임(`backtest-report-schema.json`에 `entrySkip` 필드 추가 권장).
