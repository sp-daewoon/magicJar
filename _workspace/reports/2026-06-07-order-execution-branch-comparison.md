# 주문 집행/포지션 감시 브랜치 비교 보고서

- 작성일: 2026-06-07
- 비교 기준 브랜치:
  - Claude: `origin/agent/orchestrator/order-execution-vigilance`
  - Codex: `origin/codex/autotrading-order-position-watch`
- 공통 기준점: `ce18b0b7262f9f12aa2d520af9997c1291557236`

## 1. 결론

이번 주제의 핵심이 "시장가 진입을 피하면서도, 보유 포지션은 항상 긴장 상태로 감시하고, 분할매수 후 익절 주문이 꼬이지 않게 하는 것"이라면 **Claude 브랜치가 main 후보로 더 강하다.**

이유는 단순히 변경량이 커서가 아니라, Claude 쪽이 주문 집행을 다음 구조로 닫았기 때문이다.

- 진입은 호가 기반 `EntryPricePolicy` 순수 함수로 판정한다.
- 조건이 나쁘면 주문을 내지 않고 스킵 사유를 남긴다.
- tick 손절은 인메모리 보유 인덱스로 평가해서 매 tick DB 조회를 피한다.
- 보유 종목 WS 구독은 `HOLDING` owner로 evict 금지한다.
- live와 백테스트가 같은 진입 정책 함수를 공유한다.
- ADR/검증 보고/테스트가 같이 붙어 있어 변경 의도를 추적하기 쉽다.

반대로 Codex 브랜치는 **작고 빠르게 붙일 수 있는 실전 안전 패치**로는 좋다. 특히 전략 리서치 화면/캔들 가용성 문제와 기본값 정리, 그리고 체결 즉시 TP 선주문/분할매수 TP 교체의 빠른 구현은 유효하다. 다만 자동매매 자본 경로 전체의 장기 골격으로는 Claude 쪽이 더 낫다.

## 2. 변경 규모

| 항목 | Claude | Codex |
|---|---:|---:|
| 커밋 수 | 8 | 3 |
| 변경 파일 | 67 | 23 |
| 추가/삭제 | +5,788 / -91 | +1,479 / -37 |
| 변경 테스트 파일 | 17 | 6 |
| 문서 | ADR 0114, 검증 보고, task 문서 다수 | 별도 비교/ADR 없음 |

해석:

- Claude는 설계 브랜치다. 변경 폭이 크고 병합 리스크도 크지만, 진입/청산 정책을 시스템 레벨로 정리했다.
- Codex는 좁은 패치 브랜치다. 리뷰와 롤백은 쉽지만, 성능과 백테스트 정합성까지 닫지는 못했다.

## 3. 항목별 판정

| 관점 | Claude | Codex | 판정 |
|---|---|---|---|
| BUY 진입 정책 | Redis 호가 -> KIS REST fallback -> `EntryPricePolicy`로 NoQuote/Stale/Spread/Cap 스킵 | 신호가 기준 cap 지정가를 산정해 LIMIT 발행 | Claude 우세 |
| 시장가 fallback 차단 | 호가 없으면 스킵. 시장가 fallback 금지 | BUY는 LIMIT로 바뀌었지만 호가 품질 판단 없음 | Claude 우세 |
| 미체결 진입 처리 | `EntryTimeoutCancelWorker`, 15초 기본, 시장가 전환 금지 | 설정 값은 있으나 별도 취소 워커 구조는 약함 | Claude 우세 |
| 손절 tick 성능 | `HoldingPositionIndex` hit-only, tick hot path DB 0 | tick마다 position DB + strategy DB 조회 | Claude 우세 |
| 보유 종목 WS 보호 | `HOLDING` owner, evict 후보에서 명시 제외 | `POSITION` owner priority+TTL 보호 | Claude 우세 |
| 체결 직후 TP | BUY fill 즉시 LIMIT TP 선주문 | BUY fill 즉시 LIMIT TP 선주문 | 동급 |
| 분할매수 TP 교체 | 기존 auto-tp 취소 후 합산 수량/새 평단 기준 재발행. 부분체결 숫자 검증 포함 | 기존 auto-tp 취소 후 합산 수량/새 평단 기준 재발행 | Claude 약우세 |
| 1m 청산 경로 | readiness/market-hours/status 게이트를 BUY/SELL로 분화 | 이 경로는 거의 손대지 않음 | Claude 우세 |
| 백테스트 정합 | live와 같은 `EntryPricePolicy` 함수 공유, `entrySkip` 집계 | 없음 | Claude 우세 |
| 전략 리서치 UI/캔들 UX | 해당 없음 | 종목 기본값 비움, 캔들 부족 표시/가져오기, 기본 1주/3%/3%, 40개 warmup 보강 | Codex 우세 |
| 병합 난이도 | 높음 | 낮음 | Codex 우세 |

## 4. Claude가 잘한 점

### 4-1. "조건 나쁘면 안 산다"가 코드로 닫힘

Claude의 `EntryPricePolicy`는 진입을 다음 순서로 평가한다.

1. 호가 없음: `NoQuote`
2. 호가 오래됨: `QuoteStale`
3. 스프레드 과도: `SpreadTooWide`
4. 매도1호가가 cap 초과: `PriceCapExceeded`
5. 통과 시 매도1호가로 LIMIT 진입

위 구조는 사용자가 말한 "시장가 진입은 호가가 원하는 기준이 아닐 수 있다"는 문제를 더 직접적으로 해결한다. Codex는 시장가를 LIMIT로 바꾸긴 했지만, 실제 호가를 보지 않고 신호가 기준 cap만 만든다. 즉 Codex는 "비싸게 사지 않기 위한 상한"은 있지만, "지금 호가가 나쁜 장인지"를 판정하지는 않는다.

관련 파일:

- `strategy-engine/src/main/kotlin/kr/spdaewoon/magicjar/strategy/entry/EntryPricePolicy.kt`
- `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/risk/EntryOrderPriceResolver.kt`
- `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/market/port/EntryQuotePort.kt`
- `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/CompositeEntryQuoteAdapter.kt`

### 4-2. tick 손절 성능 설계가 훨씬 좋음

Codex의 `PositionStopLossTickWatcher`는 tick마다 다음 조회를 한다.

- `strategyPositionRepository.findByCodeAndQuantityGreaterThan(...)`
- `strategyRepository.findById(...)`
- open sell 확인용 `orderRepo.findOpenByUserId(...)`

보유 종목이 많거나 tick이 많이 들어오는 장중에는 DB connection pool과 latency에 부담이 간다.

Claude는 `HoldingPositionIndex`를 두고 보유 lot, 평단, 손절률을 메모리에 올린다. tick이 들어오면 `heldBy(code)`로 바로 걸러서, 보유하지 않은 종목 tick은 DB 접근 없이 끝난다. 이건 서비스 안정성 면에서 큰 차이다.

남는 trade-off도 있다. Claude 방식은 인메모리 인덱스가 stale할 수 있다. 다만 BUY/SELL 체결 이벤트에서 `onFillRefresh(code)`를 호출하고 60초 refresh를 두어 보완했다. 운영에서는 인덱스 마지막 refresh 시각과 보유 lot 수를 metric으로 노출하는 게 좋다.

관련 파일:

- Claude: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/HoldingPositionIndex.kt`
- Claude: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/PositionStopLossTickWatcher.kt`
- Codex: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/PositionStopLossTickWatcher.kt`

### 4-3. 보유 종목 WS 보호가 더 강함

Codex는 `RealtimeDemandOwner.POSITION`을 추가하고 priority 5 + TTL로 보호한다. 이건 좋은 첫 단계지만, slot 경합 상황에서 "절대 evict 금지"까지는 아니다.

Claude는 `RealtimeDemandOwner.HOLDING`을 추가하고 `findEvictionCandidateLocked`에서 HOLDING 종목을 명시적으로 제외한다. 즉 보유 포지션의 tick 재료를 끊지 않겠다는 정책이 코드에 직접 박혀 있다.

이 차이는 자동매매에서 중요하다. 보유 중인 포지션은 화면 관심 종목이나 전략 후보보다 우선이어야 한다. 청산 재료가 끊기는 순간 시스템은 "긴장 상태"가 아니다.

관련 파일:

- Claude: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ingestion/HoldingDemandRegistrar.kt`
- Claude: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ingestion/RealtimeSubscriptionRouter.kt`
- Codex: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ingestion/PositionRealtimeWatchService.kt`
- Codex: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ingestion/RealtimeSubscriptionRouter.kt`

### 4-4. 백테스트와 live의 정책 차이를 줄였음

Claude는 `JvmBacktestEngine`에서 전략에 `entryPolicy`가 있으면 live와 같은 `EntryPricePolicy.evaluate`를 호출한다. 결과에는 `BacktestResult.Success.entrySkip`도 추가했다.

다만 정직하게 한계도 적어두었다. 백테스트에는 호가 시계열이 없어서 `ask1/bid1`은 근사값이다. 그래서 백테스트에서 의미 있게 재현되는 것은 주로 price cap이고, stale/spread 스킵은 live 전용에 가깝다.

이 한계를 명시한 것이 오히려 장점이다. "백테스트와 실매매가 완전히 같다"고 과장하지 않고, 어디까지 같은지 코드와 보고서에 써두었다.

관련 파일:

- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/JvmBacktestEngine.kt`
- `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/backtest/BacktestResult.kt`
- `_workspace/reports/2026-06-07-adr0114-r3-verification.md`

### 4-5. 분할매수 TP 검증이 더 촘촘함

두 브랜치 모두 분할매수 후 기존 `auto-tp:` 주문을 취소하고 새 평단/합산 수량 기준으로 TP를 재발행한다.

Codex 테스트는 대표 케이스를 잡았다.

- 평단 97,500
- 익절 3%
- TP 100,400

Claude는 여기에 사용자 지적 포인트를 더 촘촘하게 넣었다.

- 1차 10주 @ 100,000 -> TP 10주 @ 103,000
- 2차 10주 @ 95,000 -> 평단 97,500 -> TP 20주 @ 100,400
- 2차 일부만 4주 체결 -> 총 14주, 평단 98,571.428571 -> TP 14주 @ 101,500
- 이미 정합된 TP면 취소/재발행 0

이 부분은 Claude가 Codex의 최근 수정(`647b435d`)을 흡수하고 더 넓게 검증한 것으로 보는 게 맞다.

관련 파일:

- Claude: `consumer/src/test/kotlin/kr/spdaewoon/magicjar/consumer/order/AutoExitOrderCoordinatorTest.kt`
- Codex: `consumer/src/test/kotlin/kr/spdaewoon/magicjar/consumer/order/AutoExitOrderCoordinatorTest.kt`

## 5. Codex가 잘한 점

### 5-1. 작고 빠르게 병합 가능한 패치

Codex 브랜치는 파일 수와 변경량이 훨씬 작다. 긴급하게 시장가 진입을 막고, 체결 즉시 TP를 걸고, tick 손절 안전망을 붙이는 목적이면 리뷰/배포/롤백이 쉽다.

Claude는 구조가 더 낫지만, 그만큼 마이그레이션과 운영 설정, KIS 호가 fallback, 백테스트 결과 구조 변경까지 한 번에 들어온다. main에 바로 넣기에는 검증 비용이 더 크다.

### 5-2. 전략 리서치 실사용 버그를 같이 고쳤음

Codex는 이전에 실제로 나온 전략 리서치 문제를 해결했다.

- 에코플라스틱 고정값 제거
- 종목 필드 비움 + 바로 검색 가능
- 직전 입력 저장 또는 기본값 적용
- 기본 기간 1주, 익절 3%, 손절 3%
- 최소 40개 candle warmup 보강
- 캔들이 있는 후보는 불필요한 candle 수량 표시를 숨기고, 부족한 후보만 가져오기 노출
- AI discovery candle availability refresh
- 전략 리서치 결과에 실매매 execution policy 설명 추가

Claude 브랜치에는 이 프론트/리서치 UX 보강이 없다. 따라서 Claude를 메인 후보로 삼더라도 Codex의 전략 리서치 변경은 별도로 보존해야 한다.

관련 파일:

- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/strategyresearch/StrategyResearchService.kt`
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/strategyresearch/StrategyResearchDto.kt`
- `frontend/src/pages/Strategy/StrategyResearchPage.tsx`
- `frontend/src/api/strategyResearch.ts`

### 5-3. 실제 로컬 배포/스모크 흐름까지 확인됨

Codex 브랜치는 작업 중 API/consumer를 실제 재기동하고 health 및 전략 리서치 execution policy 노출을 확인했다. Claude 브랜치는 자체 보고서에 `./gradlew check`, `bootJar`, `ktlintCheck` green이 적혀 있지만, 현재 Codex 작업 세션에서 직접 재실행한 것은 아니다.

즉 "운영 구조"는 Claude가 강하고, "이 세션에서 바로 만져본 확신"은 Codex가 조금 더 있다.

## 6. Claude 브랜치의 리스크

### 6-1. 병합 표면이 크다

67 files / +5,788 lines는 좋은 설계라도 merge conflict, 설정 누락, 운영 예외의 표면이 넓다. 특히 다음은 main 병합 전 별도 확인이 필요하다.

- `V72__strategy_entry_policy.sql` 적용 순서
- `strategy.entry_policy` JSONB 매퍼/serde 호환성
- `EntryQuotePort` bean 부재 시 모든 BUY가 스킵되는 운영 영향
- KIS REST 호가 fallback의 user id 설정
- Redis `orderbook:top:{code}` write-through가 실시간으로 안정 동작하는지
- `EntryTimeoutCancelWorker`가 실제 주문 ACK 타이밍과 race 없이 동작하는지

### 6-2. 호가 source 장애 시 더 보수적으로 멈춘다

Claude는 호가가 없으면 시장가 fallback 없이 진입을 스킵한다. 자본 보호 관점에서는 맞지만, Redis/KIS REST fallback 장애가 있으면 매수 기회가 전부 사라질 수 있다.

운영에서는 스킵 사유별 카운터가 꼭 필요하다.

- `SIGNAL_SKIPPED_NO_QUOTE`
- `SIGNAL_SKIPPED_SPREAD`
- `SIGNAL_SKIPPED_ENTRY_PRICE_CAP`

스킵이 너무 많으면 설정 문제인지, 실제 시장 조건이 나쁜지 바로 구분할 수 있어야 한다.

### 6-3. 인메모리 인덱스는 freshness 감시가 필요하다

`HoldingPositionIndex`는 성능상 훌륭하지만, 체결 이벤트가 누락되면 최대 refresh 주기만큼 오래된 평단/수량으로 평가할 수 있다. 현재 60초 주기 refresh가 있으므로 치명적 구조는 아니지만, 자동매매 자본 path라면 다음 metric/alert를 붙이는 편이 좋다.

- 마지막 refresh 시각
- 인덱스 보유 code 수
- DB 보유 code 수와 인덱스 code 수 차이
- `onFillRefresh` 실패 횟수

### 6-4. minor: migration comment 표현 불일치

`V72__strategy_entry_policy.sql`의 컬럼 comment는 `null=전역 default(MARKET 진입 현행)`이라고 되어 있다. 하지만 Claude 설계의 전역 default는 marketable limit 정책이다. 기능 동작 자체보다 문서/주석 혼동 리스크이므로 병합 전 comment를 정리하는 게 좋다.

## 7. Codex 브랜치의 리스크

### 7-1. BUY limit가 호가를 보지 않는다

Codex는 BUY를 MARKET 대신 LIMIT로 바꿨지만, 기준은 `priceAtSignal * (1 + maxSlippagePct)`다. 실제 매도1호가, 스프레드, stale 여부를 보지 않는다.

결과적으로 다음 문제가 남는다.

- 호가가 넓어도 주문이 나갈 수 있다.
- 호가가 stale인지 알 수 없다.
- 신호가와 현재 ask 괴리가 큰 경우, 단순 cap 주문만 남는다.
- 스킵 audit이 없어 "왜 안 샀는지/왜 주문만 냈는지" 추적이 약하다.

### 7-2. tick 손절 watcher가 DB에 무겁다

tick마다 DB 조회를 하는 구조는 초기에는 괜찮아 보이지만, 자동매매 대상과 watchlist가 늘면 장중 부하가 커진다. 특히 손절은 latency가 중요하므로 DB pool 대기와 GC pressure가 생기는 구조는 장기적으로 불리하다.

### 7-3. 보유 WS 보호가 절대 보호는 아니다

`POSITION` owner priority 5와 TTL은 좋은데, "보유 종목은 evict 금지"라는 불변식까지는 아니다. 자동매매에서 보유 포지션은 화면 조회/전략 후보보다 강한 우선순위가 맞다.

### 7-4. 백테스트/전략 리서치와 live 집행 정책 연결이 약하다

Codex는 전략 리서치 결과에 execution policy 문구를 붙였지만, live 진입 판단과 백테스트 진입 판단이 같은 순수 함수로 묶인 것은 아니다. 따라서 실매매가 "스프레드 때문에 스킵"하는 세계를 백테스트가 설명하지 못한다.

## 8. 추천 병합 전략

### 8-1. main 후보

**Claude 브랜치를 주문 집행/포지션 감시의 주 후보로 삼는 것을 추천한다.**

특히 아래는 Claude 구현을 우선 채택할 가치가 높다.

1. `EntryPricePolicy` + `EntryQuotePort` + `EntryOrderPriceResolver`
2. `EntryTimeoutCancelWorker`
3. `HoldingPositionIndex`
4. `HOLDING` owner evict 금지
5. `RealtimeSignalGenerator`의 BUY/SELL 게이트 분화
6. 백테스트 `entrySkip` 집계와 동일 함수 공유
7. ADR 0114 및 검증 보고

### 8-2. Codex에서 반드시 살릴 것

Claude를 주 후보로 삼더라도 Codex 브랜치의 아래 변경은 별도 cherry-pick 또는 수동 이식 대상이다.

1. 전략 리서치 종목 입력 기본값 제거
2. 전략 리서치 기본값: 최근 1주, 익절 3%, 손절 3%
3. candle 부족 후보만 표시/가져오기 노출
4. 최소 40개 candle warmup
5. AI discovery candle availability refresh
6. 전략 리서치 응답/프롬프트에 execution policy를 노출하는 부분

다만 6번은 Claude의 실제 진입 정책이 더 정교하므로, 문구는 Codex의 `SIGNAL_PRICE_PLUS_SLIPPAGE_CAP`가 아니라 Claude의 `ORDERBOOK_ASK1_WITH_NOQUOTE_STALE_SPREAD_CAP_SKIP` 성격에 맞게 바꾸는 것이 좋다.

### 8-3. 병합 전 체크리스트

- Claude 브랜치에서 전체 test/bootJar/ktlint를 현재 workspace에서 재실행한다.
- `V72__strategy_entry_policy.sql` comment의 MARKET 표현을 수정한다.
- `EntryQuotePort` miss/REST 실패 시 스킵율 metric을 추가한다.
- `HoldingPositionIndex` refresh metric을 추가한다.
- scale-in TP replacement가 실 KIS ACK race에서 fail-closed 되는지 재확인한다.
- Codex 전략 리서치 UI/API 변경을 Claude 위로 이식한다.
- 실제 종목 1개로 MOCK 또는 canary에서 BUY signal -> LIMIT 접수 -> 미체결 취소/체결 -> TP 선주문 -> tick 손절 cancel-and-market 흐름을 end-to-end 확인한다.

## 9. 최종 판단

| 목적 | 추천 |
|---|---|
| 당장 작은 패치로 시장가 진입만 줄이기 | Codex |
| 자동매매 주문 집행 정책을 제대로 main에 세우기 | Claude |
| 성능/안정성/서비스 기여도 종합 | Claude |
| 전략 리서치 UX와 candle 문제 해결 | Codex 변경 이식 |

최종 추천은 **Claude 기반 + Codex 전략 리서치/UX 변경 이식**이다. Claude는 주문 집행의 뼈대가 좋고, Codex는 사용자가 실제로 부딪힌 화면/캔들 리서치 문제를 잘 잡았다. 둘을 합치는 것이 가장 낫다.
