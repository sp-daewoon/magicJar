# ADR 0049: 종목 진입 단일 흐름 통합 + recent-only bootstrap (default5 하드코드 폐기)

- 상태: Accepted
- 작성일: 2026-05-04
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.2 (시세 흐름) · §3-5 (구독 정책)
- 트랙: T2 (Market Data Pipeline) — 정책 정정
- 선행: ADR 0032 (Market Session FSM), ADR 0033 (3-tier Fallback), ADR 0048 (1m-only candle)
- Supersedes: PR #137·#138 의 default5 하드코드 정책 (부분 정정 — 핵심 인프라는 유지)

---

## 1. 맥락

### 1-1. 사용자 진단 (2026-05-04)

> "디폴트 5개라고 어느순간 명시되었는데 — 최근 조회한 5개 종목이여야지. 고정적인 종목을 거기다 두는게 아니야 이거 ADR"

> "종목 검색해서 엔터/클릭했을때(종목 mount?) 캔들 가져오는건 당연 — 이미 있는 데이터가 있다면 다행이지만 거의 새 데이터. 멱등성 있게 끌고 와야하는거고 — 지금 계속 꼬이는게 이유가 언제는 어디서 가져오고 어디서 배치돌고 이런 이상한 로직이 섞여서 문제로 보여"

### 1-2. 코드 꼬임 진단 (수정 안 되는 이유)

| 영역 | 현재 | 문제 |
|---|---|---|
| 시세 구독 | `useSymbolQuote` → `ensureSubscribe` (B PR #137) | OK |
| 시세 fallback | `TickFallbackEmitter @Scheduled 10s` (C PR #138) | OK |
| 캔들 backfill (진입) | `useSymbolQuote` → `ensureBackfill` (D PR #140) | 시세 hook이 캔들 trigger — 결합 |
| 캔들 fetch (차트) | `useCandles` → REST `/candles` (DB만) — backfill 없음 | **트리거 빠짐** — 차트만 보는 사용자는 backfill 안 됨 |
| 캔들 batch | `DailyCandleBackfillScheduler` 16:30 default5 | 하드코드 종목 갇힘 |
| bootstrap | `KisQuoteSubscriber.onReady` `holdings ∪ default5` | 하드코드 |
| eviction 면제 | `SubscriptionManager.defaultCodes` | 하드코드 — 사용자 의도와 무관 |
| mock tick | `MockTickGenerator` default5 sin | 하드코드 |
| yml config | `magicjar.market.default-codes` | 하드코드 |

= **default5 하드코드가 7군데 분산** + **종목 진입 = 4~5 hook 호출** + **backfill trigger가 시세 hook에만 매달림**.

### 1-3. 정책 컨텍스트

- **ADR 0046 AI 매매 결정권 0** + **ADR 0031 오프라인 1순위** — 사용자 흐름이 분산되면 룰/시그널 진입 타이밍이 흔들린다.
- 메모리 `feedback_no_mock_data.md` — mock 합성 폐기. default5 하드코드는 mock 잔재의 일종.

---

## 2. 결정

### 2-A. 모든 자동 bootstrap 폐기 — 완전 빈 시작

| 영역 | 폐기 방식 |
|---|---|
| `magicjar.market.default-codes` yml | **삭제** (consumer dev/prd) |
| `KisQuoteSubscriber.onReady` 자동 구독 | **0건 add**. `router.start()`만 호출. 합집합·holdings·default5 모두 폐기 |
| `HoldingsCodeFetcher` 의존 | 본 cycle에서 **bootstrap에서 제거**. 보유 종목은 별도 watchlist UI 화면에서 명시 add (후속) |
| `SubscriptionManager.defaultCodes` eviction 면제 | **폐기**. 18 LRU만 유지 — 모든 종목 동일 priority |
| `DailyCandleBackfillScheduler` default5 | `SubscriptionManager.activeCodes()` 기반 — 사용자가 최근 본 종목 자동 갱신 |
| `MockTickGenerator` default5 | yml `mock-tick.codes` 유지 (mock 환경 부팅 부담). 후속에서 activeCodes 기반으로 전환 |

**부팅 source 근거 (사용자 정정 2026-05-04)**:
> "보유 종목이 몇십 개가 될지도 모르는데 그 이유로 넣을 필요 없음. mount 개념으로 일단 종목 클릭해서 상세보기됐을 때만 가져오고."

→ **mount = 시세 진입점**. 사용자가 SymbolDetail로 진입할 때만 SubscriptionManager.addOnDemand. 보유·watchlist는 후속 별도 UI(고정)에서 명시 add.

**SymbolDetail 페이지 정체성 (사용자 명시)**: "차트 보고 전략 분석하는 화면". → mount = 시세·캔들·상세 메타 모두 보장 (§2-B).

### 2-B. 종목 진입 = 단일 보장 흐름 (멱등)

종목 mount 시 frontend가 호출하는 모든 REST endpoint가 **동일 보장 명령을 발행**한다. 사용자가 어떤 hook으로 진입하든(시세·차트·상세) 같은 결과.

```
종목 mount (사용자가 검색·클릭으로 /symbol/{code} 진입)
  ├─ useSymbolQuote → GET /api/instruments/{code}/quote
  │     └─ QuoteController: ensureSubscribe + ensureBackfill 발행
  ├─ useCandles → GET /api/instruments/{code}/candles
  │     └─ InstrumentController: ensureBackfill 발행 (신규)
  └─ useInstrumentDetail → GET /api/instruments/{code}/detail
        └─ InstrumentDetailController: 기존 6h cache (변경 없음)

backend 처리:
  - market.subscription.command.v1 → SubscriptionManager.addOnDemand (멱등)
  - market.candle.backfill.command.v1 → BackfillCommandListener (DB max ≥ 어제 skip + 60s dedup)
```

**핵심**: frontend hook 어디서 호출하든 backend는 동일 명령을 받는다. 사용자가 차트만 봐도 backfill OK. 사용자가 quote만 봐도 backfill OK.

### 2-C. Scheduled backfill 정책

| 옵션 | 결정 |
|---|---|
| 매일 16:30 KST default5 일괄 | **폐기** |
| 매일 16:30 KST `SubscriptionManager.activeCodes()` 일괄 | **채택** — 활성 구독 종목 자동 갱신. 빈 set이면 no-op |

근거: 사용자가 최근 본 종목만 갱신해도 충분. 4,455 종목 일괄은 KIS rate limit 부담 + 사용 안 하는 종목 fetch 낭비.

### 2-D. 18 LRU 한도 정책 정정

- 한도 18종목 유지 (KIS account 41건 한도 정합)
- **eviction 면제 폐기** — `defaultCodes` 자체가 없으므로 면제할 대상도 없음
- 19번째 진입 시 LRU 가장 오래된 종목 unsubscribe (모든 종목 동일 priority)
- `SubscriptionEvictedEvent` publish는 그대로 (후속 STOMP fanout으로 toast)

### 2-E. MockTickGenerator 정책

mock profile에서 `MockTickGenerator`도 `SubscriptionManager.activeCodes()` 기반.
- 사용자가 진입한 종목만 sin tick 발행
- 빈 set이면 no-op
- mock 환경에서 dev/prd 환경과 일관된 흐름 (사용자 진입 → 시세 흐름)

---

## 3. 수용 기준

- yml `magicjar.market.default-codes` 검색 결과 0건
- `defaultCodes` 변수·필드 검색 결과 0건 (영향 영역 모두 정리)
- `useCandles` REST 호출 시 `MarketCandleBackfillCommandPublisher.ensureBackfill` 호출됨 (테스트 검증)
- `DailyCandleBackfillScheduler.runDaily`가 `SubscriptionManager.activeCodes()` 사용 (단위 테스트)
- `MockTickGenerator`가 `SubscriptionManager.activeCodes()` 사용 (mock 환경 + 빈 set 시 no-op)
- 라이브 검증: 깨끗한 컨테이너 부팅 → 한국석유 진입 → 시세 LIVE + 차트 ~243봉 + 상세 메타 모두 자동 표시 (사용자 진입 1회로 끝)

---

## 4. 영향 범위

### 4-1. 코드 변경 영역 (8건)

| 파일 | 변경 |
|---|---|
| `api/src/main/resources/application.yml` | `default-codes` 삭제 |
| `consumer/src/main/resources/application.yml` | `default-codes` 삭제 |
| `consumer/.../KisQuoteSubscriber.kt` | `defaultCodesRaw` · 합집합 로직 삭제. holdings만 |
| `consumer/.../SubscriptionManager.kt` | `defaultCodes` 면제 폐기. eviction에서 단순 첫 entry |
| `consumer/.../MockTickGenerator.kt` | `subscriptionManager.activeCodes()` 기반으로 전환 |
| `api/.../DailyCandleBackfillScheduler.kt` | publisher 인자 삭제(consumer로 이전) 또는 activeCodes 기반 신규 listener 통합 |
| `api/.../InstrumentController.kt` (candles) | `ensureBackfill` 호출 추가 |
| `consumer/.../MockTickProperties.kt` | `codes` 하드코드 폐기 (activeCodes 사용) |

### 4-2. 테스트 영역 (영향 ~30 PASS)

- `KisQuoteSubscriberTest` — defaultCodesRaw 인자 삭제, bootstrap에 holdings만 capture 검증
- `SubscriptionManagerTest` — eviction 면제 case 폐기, 단순 LRU 검증
- `MockTickGeneratorTest` — activeCodes 모킹 + 빈 set / N건 case
- `DailyCandleBackfillSchedulerTest` — activeCodes mock + publisher 호출 검증
- `InstrumentControllerTest` (candles) — ensureBackfill 호출 검증

### 4-3. 다른 ADR 영향

- ADR 0033 (3-tier fallback) — 영향 0
- ADR 0048 (1m-only candle) — 영향 0
- ADR 0042 (Risk Guard) — 영향 0

---

## 5. 비범위 (후속)

| 항목 | 사유 |
|---|---|
| 종목 진입 단일 endpoint (`GET /bundle`) | 본 ADR은 "각 hook이 같은 명령 발행"으로 통일. 단일 endpoint는 frontend 측 1 hook 합치기 차원 — 별도 ADR 또는 P2 |
| frontend recent-history backend sync | 진입 시 자동 ensureSubscribe로 자연 sync — 명시 endpoint 불요 |
| `SubscriptionEvictedEvent` STOMP fanout | LRU eviction 사용자 toast — P2 후속 |
| 1년 페이징 후속(KIS 일봉 fetch limit 부분) | PR #141로 이미 해소 |

---

## 6. 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-04 | 발행 | 사용자 진단 — default5 하드코드 폐기 + 종목 진입 단일 흐름 통합 |
