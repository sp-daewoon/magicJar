# 공용 도메인 모델 초안 (`core` 모듈, Kotlin)

Phase B에서 backend-core가 이 초안을 기준으로 `core/src/main/kotlin/kr/spdaewoon/magicjar/core/domain/**`에 Kotlin data class/enum으로 구현한다. 변경 제안은 `SendMessage`로 architect에 요청.

## 공통 규칙

- 값 객체는 `data class`, 식별 엔티티(JPA 영속)는 일반 `class`(또는 `open class`) + `kotlin-jpa` 플러그인의 no-arg 지원.
- 타입 표기: nullable은 `Type?`, 기본 숫자는 `Int`/`Long`/`java.math.BigDecimal`, 시간은 `java.time.Instant`/`LocalDate`.
- **`Double` 금지(가격·수량·손익).** 항상 `BigDecimal` 또는 `Int`.
- Enum은 DB 저장 시 `EnumType.STRING`.
- 자바 `record`·Lombok 사용 금지.

## 종목·시세

```kotlin
data class Instrument(val code: String, val name: String, val market: Market)

enum class Market { KOSPI, KOSDAQ }

enum class Interval { D1, M1, M5, M15, M30, H1 }

data class Candle(
    val code: String,
    val interval: Interval,
    val openTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val tradeCount: Long,
)

data class Quote(
    val code: String,
    val at: Instant,
    val price: BigDecimal,
    val volume: Long,
)
```

## 계좌·포지션·주문

```kotlin
data class Account(val cano: String, val prdt: String, val mode: Mode)

enum class Mode { BACKTEST, MOCK, REAL }

data class Position(
    val code: String,
    val qty: Int,
    val avgPrice: BigDecimal,
    val openedAt: Instant,
)

enum class Side { BUY, SELL }
enum class OrderType { MARKET, LIMIT }
enum class OrderStatus { PENDING, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED }

data class Order(
    val id: UUID,
    val code: String,
    val side: Side,
    val qty: Int,
    val price: BigDecimal?,        // LIMIT에서만 의미, MARKET은 null
    val type: OrderType,
    val status: OrderStatus,
    val createdAt: Instant,
)

data class OrderResult(
    val order: Order,
    val resultStatus: OrderStatus,
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
    val filledAt: Instant?,
    val rejectReason: String? = null,
)
```

## 전략·백테스트

```kotlin
data class StrategyRef(val strategyId: String, val version: Int)

data class BacktestConfig(
    val initialCapitalKrw: BigDecimal,
    val feeRate: BigDecimal,           // 예: 0.00015
    val taxRateKospi: BigDecimal,
    val taxRateKosdaq: BigDecimal,
    val slippageBp: BigDecimal,
)

data class BacktestRequest(
    val strategy: StrategyRef,
    val conditionExpr: String,         // DSL
    val interval: Interval,
    val from: LocalDate,
    val to: LocalDate,
    val universe: List<String>,        // 종목 코드
    val config: BacktestConfig,
)

data class Metrics(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
    val sharpe: BigDecimal,
    val winRate: BigDecimal,
    val tradeCount: Int,
)

data class TradeRef(val journalEntryId: UUID)

data class BacktestResult(
    val strategy: StrategyRef,
    val from: LocalDate,
    val to: LocalDate,
    val metrics: Metrics,
    val trades: List<TradeRef>,
    val aiExplanation: String? = null,
)
```

## 뉴스·공시·AI

```kotlin
data class NewsItem(
    val id: UUID,
    val source: String,                // "DART" | "NAVER" | ...
    val publishedAt: Instant,
    val title: String,
    val body: String,
    val instruments: List<String>,     // 관련 종목 코드
)

data class AiInsight(
    val topic: String,                 // "sentiment" | "strategy-explain" | ...
    val payloadJson: String,
    val reasoning: String,
    val createdAt: Instant,
)
```

## 매매일지 (상세는 ADR 0007 / magicjar-trading-journal 스킬)

```kotlin
data class TradeJournalEntry(
    val id: UUID,
    val tradeId: UUID,
    val mode: Mode,
    val code: String,
    val name: String,
    val side: Side,
    val entryAt: Instant,
    val entryPrice: BigDecimal,
    val entryQty: Int,
    val exitAt: Instant? = null,
    val exitPrice: BigDecimal? = null,
    val exitQty: Int? = null,
    val pnl: BigDecimal? = null,
    val pnlRate: BigDecimal? = null,
    val strategyId: String,
    val entrySnapshot: Map<String, BigDecimal>,
    val exitSnapshot: Map<String, BigDecimal> = emptyMap(),
    val entryReason: String,
    val exitReason: String? = null,
    val aiExplanation: String? = null,
    val aiReview: String? = null,
    val tags: List<String> = emptyList(),
    val userNote: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)
```

## 하네스 상태

```kotlin
data class HarnessState(
    val currentPhase: String,          // "0" | "A" | "B" | "C" | "DONE"
    val phaseHistory: List<PhaseEntry>,
    val agents: List<AgentStatus>,
    val issuesOpen: Int,
    val issuesFixed: Int,
    val generatedAt: Instant,
)

data class PhaseEntry(
    val phase: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val mode: String,                  // "initial" | "partial" | "incremental" | "team" | ...
    val agents: List<String>,
)

data class AgentStatus(
    val name: String,
    val roleSummary: String,
    val skills: List<String>,
    val lastActivity: Instant? = null,
    val lastArtifacts: List<String> = emptyList(),
    val status: String,                // "IDLE" | "RUNNING" | "BLOCKED"
)
```

## 이벤트(Spring ApplicationEvent)

```kotlin
data class OrderFilledEvent(val result: OrderResult, val mode: Mode)
data class AiReviewRequestedEvent(val journalEntryId: UUID)
data class PhaseChangedEvent(val from: String, val to: String, val at: Instant)
```

## JPA 영속 엔티티 주의

JPA 엔티티는 data class로 두지 말고 일반 `class`로 선언하고 `kotlin-jpa` 플러그인(no-arg)을 적용한다. 예:

```kotlin
@Entity
@Table(name = "trade_journal")
class TradeJournalJpa(
    @Id val id: UUID,
    @Column(nullable = false) var updatedAt: Instant,
    // ...
)
```

도메인 모델(data class) ↔ JPA 엔티티 매핑은 `core`의 포트 인터페이스 아래 구현체에서 확장 함수(`fun TradeJournalJpa.toDomain(): TradeJournalEntry`)로 간결하게 처리한다. MapStruct 미사용.
