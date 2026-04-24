# 모듈 경계와 의존 방향

ADR 0002 기반 상세 명세. Phase B 구현 시 반드시 이 파일의 "공개 API" 섹션을 따라 모듈 간 상호작용을 구현한다.

## 의존 그래프 (단방향)

```
                    app
                     │  (조립)
   ┌──────┬──────┬──┴──┬──────┬──────┐
   ▼      ▼      ▼     ▼      ▼      ▼
 web  market-data  strategy  ai  order-exec
   │      │          │       │      │
   └──────┴──────────┴───────┴──────┘
                     ▼
                   core  (포트 인터페이스 + 도메인)
```

- `core`는 어디에도 의존하지 않는다.
- 어댑터 모듈(`web`, `market-data`, `strategy`, `ai`, `order-exec`)은 서로 **직접** 의존하지 않는다.
- 모듈 간 협업은 (1) `core`의 포트 인터페이스, (2) Spring `ApplicationEvent` 두 가지 경로만 사용.

## 모듈별 책임과 공개 API

### core
- **목적:** 도메인 타입 + 포트 인터페이스 공급. 기술 종속 금지(Spring/JPA 의존 최소).
- **공개 API(포트):**
  - `CandleRepository`: 일봉/분봉 저장·조회
  - `InstrumentCatalog`: 종목 마스터 조회
  - `NewsRepository`: 뉴스·공시 저장·조회
  - `OrderSender`: 주문 전송(구현체가 BACKTEST/MOCK/REAL 분기)
  - `StrategyEvaluator`: 조건식 DSL 평가
  - `AiClient`: LLM 호출 추상(기능별 세부 서비스는 `ai` 모듈이 제공)
  - `TradeJournalRepository`: 매매일지 저장소
  - `HarnessStateReader`: 하네스 대시보드용 상태 읽기
- **이벤트:** `OrderFilledEvent`, `AiReviewRequestedEvent`, `PhaseChangedEvent`

### market-data
- **구현:** `CandleRepository`, `InstrumentCatalog`, `NewsRepository` 구현체, KIS REST/WS 클라이언트, DART 클라이언트, 뉴스 수집 스케줄러
- **포트 의존:** (없음, 자체가 최하층 어댑터)
- **스킬:** `magicjar-kis-openapi`

### strategy
- **구현:** 지표 라이브러리, DSL 평가기(`StrategyEvaluator`), 백테스트 엔진, `OrderSender`의 `BacktestOrderSender` 구현, `TradeJournalRecorder`, `TradeJournalRepository` 구현
- **포트 의존:** `CandleRepository`, `OrderSender`(조립 시점에 모드별 선택)
- **스킬:** `magicjar-backtest-engine`, `magicjar-trading-journal`

### ai
- **구현:** `AiClient` 구현, 프롬프트 저장·렌더링, 4대 AI 기능별 서비스(`SentimentAnalyzer`, `StrategyExplainer`, `RebalancingAdvisor`, `ConditionDslTranslator`, `TradeReviewer`)
- **포트 의존:** `NewsRepository`, `TradeJournalRepository`
- **스킬:** `magicjar-spring-ai`

### order-exec
- **구현:** `MockOrderSender`, `RealOrderSender`, `OrderGuard`(안전 게이트 데코레이터)
- **포트 의존:** `InstrumentCatalog` (종목 검증)
- **의존 모듈:** market-data의 KIS 클라이언트를 **직접** 의존하지 않음 — `core`의 `BrokerGateway` 포트를 경유. 이 포트는 market-data에서 구현.

### web
- **구현:** Thymeleaf 컨트롤러(홈/종목/전략/포트폴리오/매매일지/`/harness`), WebSocket 허브, REST API, `HarnessController`·`HarnessStateReader` 구현
- **포트 의존:** 모든 포트 (조회·표시용)
- **스킬:** `magicjar-dashboard`, `magicjar-trading-journal`, `magicjar-harness-dashboard`

### app
- **구현:** `@SpringBootApplication`, 구성 클래스, 프로파일별 빈 조립, `magicjar.mode` 분기에 따른 `OrderSender` 선택
- **다른 모든 모듈 의존**

## 금지 사항

- `market-data` ↔ `strategy` 직접 import 금지 (반드시 `core` 포트 경유)
- `strategy` 내부에서 KIS 관련 클래스 import 금지
- `web` 에서 DB 직접 조회 금지 (`core` Repository 포트만 사용)
- `ai`가 `order-exec`의 구현체를 직접 import 금지 (필요 시 이벤트로 통지)
