# backend 모듈 맵 (Phase B-1 실구현 반영)

| 모듈 | Gradle path | 주요 책임 | 빌드 특성 | 외부 의존 (런타임) |
|------|------------|----------|----------|-------------------|
| `core` | `:core` | 도메인 + 이벤트 + 포트 인터페이스 (기술 종속 0) | Kotlin 라이브러리, Spring 미포함 | jackson-annotations, slf4j-api |
| `market-data` | `:market-data` | KIS REST/WS, 캔들·종목·뉴스 저장소 구현 | Spring Web/WS/JPA, kotlin-jpa 플러그인 | PostgreSQL, Flyway, Resilience4j, Bucket4j |
| `strategy` | `:strategy` | 지표, DSL, 백테스트, `BacktestOrderSender`, 매매일지 | Spring Starter/JPA, kotlin-jpa 플러그인 | PostgreSQL, Flyway |
| `ai` | `:ai` | Spring AI 기반 `AiClient` 구현 + 기능별 서비스 | Spring AI starter | Anthropic, Ollama |
| `order-exec` | `:order-exec` | `MockOrderSender`, `RealOrderSender`, `OrderGuard` | Spring Starter | (BrokerGateway 포트만 의존) |
| `web` | `:web` | Thymeleaf 컨트롤러, WebSocket, REST API, `/harness` | Spring Web/WS/Thymeleaf/Actuator | SpringDoc OpenAPI |
| `app` | `:app` | 조립, 부팅, `OrderSenderConfig`, `AiConfig`, `logback`, 프로파일 | Spring Boot plugin 적용 (bootJar) | PostgreSQL, Flyway, Redis |

## 공개 API 요약

### 도메인 (`core.domain`)
시세: `Instrument`, `Market`, `Interval`, `Candle`, `Quote`
계정/주문: `Account`, `Mode`, `Position`, `Side`, `OrderType`, `OrderStatus`, `Order`, `OrderResult`
전략/백테스트: `StrategyRef`, `BacktestConfig`, `BacktestRequest`, `Metrics`, `TradeRef`, `BacktestResult`
뉴스/AI: `NewsItem`, `AiInsight`
매매일지: `TradeJournalEntry`
하네스: `HarnessState`, `PhaseEntry`, `AgentStatus`

### 이벤트 (`core.event`, Spring `ApplicationEvent` 로 발행)
`OrderFilledEvent`, `AiReviewRequestedEvent`, `PhaseChangedEvent`

### 포트 (`core.port`) — 구현자 지정
| 포트 | 구현 모듈 | 메서드 |
|------|-----------|--------|
| `CandleRepository` | market-data | `save(List<Candle>)`, `find(code, interval, from, to)` |
| `InstrumentCatalog` | market-data | `findAll()`, `findByCode(code)`, `upsert(List)` |
| `NewsRepository` | market-data | `save(List)`, `search(code?, from, to)` |
| `BrokerGateway` | market-data | `fetchQuote`, `fetchCandles`, `placeOrder` |
| `OrderSender` | strategy(BACKTEST) / order-exec(MOCK,REAL) | `send(order)` |
| `StrategyEvaluator` | strategy | `evaluate(expr, EvaluationContext)` |
| `TradeJournalRepository` | strategy | `save`, `find(id)`, `search(TradeJournalFilter)` |
| `AiClient` | ai (미설정 시 app `NoOpAiClient`) | `chat`, `chatJson` (null=비활성) |
| `HarnessStateReader` | web | `read()` |

## 의존 그래프 (실제 Gradle 선언 기준)

```
core ← market-data
core ← strategy
core ← ai
core ← order-exec
core ← web
core, market-data, strategy, ai, order-exec, web ← app
```

어댑터 모듈 간 직접 의존 없음 (검증됨).
