# magicJar — MSA 재구성 설계 (Spec)

- **작성일**: 2026-04-27
- **작성자**: Claude (orchestrator) + 사용자 (sp-daewoon)
- **상태**: Draft → User Review
- **관련 ADR**: 0019~0026 (신규 발행 예정)
- **Phase**: D (시작점)

## 0. 개요

magicJar의 모듈 구조를 **수직 분할(market-data·strategy·ai·order-exec)** 에서 **수평 분할(domain·api·batch·consumer)** 로 재배치한다. Python 모듈 도입(스크래핑·서버 캡처), Kafka 도입(이벤트 백본), 프론트엔드 SPA 전환(Thymeleaf → React+TS+Vite + TradingView Lightweight Charts), Docker 표준화(MSA 기본 + compose 통합 기동)를 한 번에(빅뱅) 처리한다.

## 1. 모듈 레이아웃

```
magic_ch1/
├─ settings.gradle.kts
├─ build.gradle.kts                  공통 플러그인·subprojects 블록
├─ docker/                           docker-compose 통합 기동
│  ├─ docker-compose.yml
│  ├─ docker-compose.dev.yml
│  ├─ docker-compose.infra.yml
│  └─ kafka/, postgres/, minio/
│
├─ domain/                           Kotlin java-library, Spring Boot 미부착
│  └─ src/main/kotlin/com/mj/domain/
│     ├─ common/                     Money, Symbol, TimeRange, DomainEvent
│     ├─ market/                     Candle, Tick, Quote, KisClient port
│     ├─ strategy/                   DSL, Indicator, BacktestEngine
│     ├─ ai/                         PromptTemplates, SentimentClient port
│     ├─ order/                      Order, Position, BrokerClient port
│     └─ journal/                    TradeJournal, ChartSnapshot
│
├─ api/                              Spring Boot 3.x — REST + Spring @Scheduled + STOMP
│  ├─ Dockerfile
│  └─ src/main/kotlin/com/mj/api/{controller, scheduler, websocket, config}
│
├─ batch/                            Spring Boot 3.x + Spring Batch
│  ├─ Dockerfile
│  └─ src/main/kotlin/com/mj/batch/{job, step, reader, writer, config}
│
├─ consumer/                         Spring Boot 3.x + Spring Kafka
│  ├─ Dockerfile
│  └─ src/main/kotlin/com/mj/consumer/{listener, producer, kis_ws_bridge, config}
│
├─ pyworker/                         Python 3.12 + FastAPI + Playwright + APScheduler
│  ├─ Dockerfile
│  ├─ pyproject.toml
│  └─ app/{main, scrapers, screenshot, routers, messaging}
│
└─ frontend/                         React + TS + Vite + shadcn/ui + TanStack Query + Zustand
   ├─ Dockerfile                     multi-stage (node:20 → nginx:alpine)
   └─ src/{api, ws, stores, pages, components/chart, components/ui, lib}
```

폐기 디렉토리: `app/`, `core/`, `market-data/`, `strategy/`, `ai/`, `order-exec/`, `web/` (7개 — 빅뱅 일괄 `git rm`).

## 2. 런타임 구성 (하이브리드 — 3 JVM 앱 + Python + 프론트)

| 앱 | 책임 |
|----|------|
| **api-app** | REST 엔드포인트, `@Scheduled` 정기 작업, STOMP/WebSocket 실시간 push, 백테스트 트리거(batch-app 호출), pyworker HTTP 호출 |
| **batch-app** | Spring Batch 잡 — DailyCandleBackfill, EodReport, BacktestRun |
| **consumer-app** | Kafka 컨슈머/프로듀서 — KIS WS 브리지, Tick→Candle 집계, StrategyRunner, OrderDispatcher, JournalRecorder |
| **pyworker** | 정기 크롤링(APScheduler), 수동 스크래핑(HTTP), 서버사이드 브라우저 캡처(Playwright) |
| **frontend** | React SPA, nginx 정적 서빙 (독립 컨테이너) |

## 3. 운영 패러다임 — MSA 기본, compose는 로컬 도우미

| 축 | MSA (운영) | compose (로컬·CI 통합) |
|----|-----------|---------------------|
| 빌드 단위 | 모듈별 독립 Dockerfile | 동일 Dockerfile 재사용 |
| 배포 | `ghcr.io/sp-daewoon/magicjar/<service>:<sha>` → k8s/ECS/Fly | `docker compose up` |
| 스케일 | 서비스별 독립 | single replica |
| 라이프사이클 | 서비스별 독립 PR·릴리스 | 통합 검증용 |
| 헬스체크 | `/actuator/health` (JVM) · `/health` (pyworker) · nginx 기본 (frontend) | compose `healthcheck` |
| 로깅 | JSON stdout → 외부 수집 | 콘솔 |

## 4. 데이터 흐름 — Kafka 토폴로지

```
[KIS WebSocket]──→ consumer-app [KisWsBridge] ──publish──→ Kafka
[KIS REST]    ───→ domain.market.KisRestClient (api·batch·consumer 모두 사용)

Kafka 토픽 (모두 v1 suffix, 스키마 진화 대비):
- market.tick.v1            consumer-app → consumer-app(StrategyRunner) + api-app(STOMP fan-out)
- market.candle.1m.v1       consumer-app(TickAggregator) → 다른 컨슈머 + DB persist
- news.raw.v1               pyworker → consumer-app(Spring AI 분석)
- news.analyzed.v1          consumer-app → api-app(STOMP) + DB
- dart.disclosure.v1        pyworker → consumer-app
- screenshot.captured.v1    pyworker → consumer-app(매매일지 첨부 자동화)
- signal.v1                 consumer-app(StrategyRunner) → consumer-app(OrderDispatcher) + api(STOMP)
- order.intent.v1           api-app/consumer-app → consumer-app(OrderDispatcher)
- order.executed.v1         consumer-app → api-app(STOMP) + JournalRecorder
- journal.entry.v1          consumer-app(JournalRecorder)/batch-app → api-app + frontend
```

**프로듀서 규약**: 클래스 기준 명시 프로듀서 (`TickPublisher`, `OrderIntentPublisher` 등). 토픽 직접 문자열 노출 금지 — 각 publisher 클래스가 토픽·키·헤더 캡슐화.

**컨슈머 규약**: 클래스 기준 `@KafkaListener` (예: `TickAggregatorListener`, `StrategyRunnerListener`, `OrderDispatcherListener`). 한 클래스 = 한 책임.

**스키마**: Phase D는 JSON(Kotlin data class + Jackson, Python pydantic). Avro/Schema Registry는 Phase E+ 검토 (ADR 0020 미래 후보).

## 5. domain 패키지 상세

```
com.mj.domain
├── common/        Money, Symbol(KrxCode), TimeRange, DomainEvent base, Result
├── market/        Candle, Tick, Quote, OrderBook
│                  port: KisRestClient, KisWsClient
│                  service: CandleAggregator
├── strategy/      dsl/ ConditionDsl, RuleSet, Compiler
│                  indicator/ MA, RSI, MACD, BollingerBand, ATR
│                  backtest/ BacktestEngine, BacktestResult
│                  signal/ SignalGenerator
├── ai/            prompt/ Templates(.kt + .py 공유 디렉토리 가능성 검토)
│                  port: SentimentClient, StrategyExplainer
│                  spring-ai 어댑터: SpringAiSentimentClient
├── order/         Order, Position, ExecutionReport
│                  port: BrokerClient (KIS 모의·실전)
│                  policy: RiskGuard, PositionSizing
└── journal/       TradeJournal, JournalEntry, ChartSnapshot
                   service: JournalRecorder (event 기반 영속화)
```

도메인은 Spring Boot 미부착 (`org.jetbrains.kotlin.jvm` + `java-library` 만). Spring AI 어댑터는 `org.springframework.ai:spring-ai-core` 까지만 — starter는 각 앱이.

## 6. 매핑 (현재 7 모듈 → 신규 5 + pyworker)

| 현재 | 신규 위치 | 비고 |
|------|----------|------|
| `app/` | 폐기 | 각 앱이 자체 main; bootRun hotfix는 api-app config로 회수 |
| `core/common`, `core/security` | `domain/common` + `api/config` | 보안 필터는 api-app, 도메인 enum은 domain |
| `core/persistence` | `domain/<영역>/persistence` 또는 각 앱의 `infrastructure/` | JPA 엔티티/리포지토리 영역별 분산 |
| `market-data/kis-rest` | `domain/market` (KisClient port + 구현) | api·batch가 직접 사용 |
| `market-data/kis-ws` | `consumer/kis_ws_bridge` | WS 수신 → Kafka publish |
| `market-data/news` (Kotlin) | **폐기 → pyworker/scrapers** | 사용자 지시 재구현 |
| `strategy/` | `domain/strategy` | 라이브러리화. 백테스트=batch, 실시간 신호=consumer |
| `ai/` | `domain/ai` (포트·프롬프트) + 호출은 api·consumer | `spring-ai-core`만 domain에. starter는 각 앱 |
| `order-exec/` | `domain/order` (port) + `consumer/order_dispatcher` | 주문 실행은 컨슈머 |
| `web/` | 폐기 → `frontend/` (React) | Phase B 화면(시드 종목·하네스 대시보드)은 React 재작성 (의도 보존) |
| `_workspace/` | 유지 | ADR·하네스 산출물 |
| `.claude/` | 유지 + 갱신 | 에이전트·스킬 변경 반영 |

## 7. Python 모듈 (`pyworker`) 상세

### 7-1. 구조

```
pyworker/
├─ pyproject.toml             uv 또는 poetry
├─ Dockerfile                 python:3.12-slim + Playwright chromium
├─ docker-entrypoint.sh
└─ app/
   ├─ main.py                 FastAPI 진입
   ├─ config.py               KAFKA_BOOTSTRAP, S3_ENDPOINT, ...
   ├─ scrapers/
   │  ├─ naver_news.py        BeautifulSoup + httpx
   │  ├─ dart.py              opendart-py SDK
   │  └─ scheduler.py         APScheduler 정기 수집
   ├─ screenshot/
   │  ├─ capture.py           Playwright 헤드리스 (URL → PNG)
   │  └─ storage.py           MinIO/S3 업로드
   ├─ routers/
   │  ├─ scrape.py            POST /scrape/{source}
   │  ├─ screenshot.py        POST /screenshot
   │  └─ health.py            GET  /health
   └─ messaging/
      └─ kafka_producer.py    aiokafka — news.raw.v1, dart.disclosure.v1
```

### 7-2. JVM ↔ Python 통신

| 트리거 | 통신 | 출력 |
|--------|------|------|
| 정기 크롤링 (APScheduler 내부) | Kafka publish | `news.raw.v1`, `dart.disclosure.v1` |
| 수동 스크래핑 | api-app → pyworker HTTP | HTTP 응답 + Kafka publish |
| 서버사이드 캡처 | api-app → pyworker HTTP `/screenshot` | MinIO 저장 + URL 응답 |
| 클라이언트 차트 캡처 | 프론트 → api-app `/journal/{id}/snapshots` | api-app DB + MinIO |

pyworker는 PostgreSQL 직접 접근 없음 — Kafka·HTTP·MinIO만. 결합 차단.

## 8. Docker 토폴로지

### 8-1. Dockerfile 원칙

- 모든 모듈 멀티 스테이지
- 비루트 유저 (`USER 1000`)
- distroless 또는 alpine 베이스 (이미지 경량)
- JVM: `eclipse-temurin:21-jre-alpine`
- Python: `python:3.12-slim` + Playwright chromium
- Frontend: `node:20-alpine` (build) → `nginx:alpine` (serve)

### 8-2. docker-compose 서비스

| 서비스 | 이미지 | 포트 | 역할 |
|--------|--------|------|------|
| `kafka` | `bitnami/kafka:3.7` (KRaft) | 9092 | 메시지 브로커 |
| `postgres` | `postgres:16-alpine` | 5432 | 도메인 DB |
| `redis` | `redis:7-alpine` | 6379 | 세션·캐시·STOMP fan-out 옵션 |
| `minio` | `minio/minio:latest` | 9000, 9001 | 객체 저장소 |
| `api-app` | local | 8080 | REST + STOMP |
| `batch-app` | local | 8081 | Spring Batch |
| `consumer-app` | local | 8082 | Kafka |
| `pyworker` | local | 8000 | FastAPI |
| `frontend` | local | 3000 | nginx |

### 8-3. 모드

```bash
# 1) 인프라만 (개발자 IDE에서 앱 실행)
docker compose -f docker/docker-compose.infra.yml up

# 2) 전체 스택 (통합 검증·시연)
docker compose -f docker/docker-compose.yml up

# 3) 핫 리로드·볼륨 마운트
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up
```

## 9. 데이터 저장소 정책

- **PostgreSQL 1 인스턴스, 스키마 분리** (`schema=trading` 공유, `schema=ops` 분리). DB-per-service는 Phase F+ 검토 (ADR 0025).
- **MinIO/S3**: 스크린샷·백테스트 산출물·로그 첨부 (ADR 0026).
- **Redis**: 세션·rate-limit·STOMP fan-out (선택적).
- **pyworker**: DB 직접 접근 없음.

## 10. 컨테이너 레지스트리·태깅

- **Registry**: `ghcr.io/sp-daewoon/magicjar/<service>` (api·batch·consumer·pyworker·frontend).
- **태그**: `:<short-sha>` 항상 + `:latest` (main 브랜치만).
- **빌드**: GitHub Actions matrix (5 서비스 병렬 빌드).

## 11. 프론트엔드 상세

### 11-1. 차트 라이브러리 — TradingView Lightweight Charts (Apache 2.0)

| 라이브러리 | 라이선스 | 채택 |
|----------|---------|------|
| **Lightweight Charts v4** | Apache 2.0 (진짜 OSS) | ✅ MVP 채택 |
| **Advanced Charts** (proprietary, 무료 발급) | Closed source | ❌ 미채택 — 그리기 도구·커스텀 study 필수 시 ADR 0027(미래) 트리거 |
| **Trading Platform** (유상) | Closed | ❌ 범위 외 |

### 11-2. LWC 한계 → 우리 시스템이 메우는 방법 (LWC 공식 API 한정)

| LWC 한계 | 대응 |
|---------|------|
| 보조지표 계산 미지원 | `domain.strategy.indicator/` (Kotlin)에서 계산 → API 응답에 시리즈 동봉 → **LWC 공식 시리즈 API**(LineSeries/HistogramSeries)로 plot |
| 그리기 도구 없음 | **MVP 미지원**. 캔버스 오버레이·DOM hack 금지. 사업적으로 필수해지면 Advanced Charts 전환 (ADR 0027) |
| 차트 시간축 동기화 | LWC 공식 `subscribeVisibleLogicalRangeChange` + `setVisibleLogicalRange`만 사용 |
| 심볼 검색 | 차트 외부 — api-app `/api/symbols/search` + shadcn/ui Combobox |
| Replay 모드 | LWC 공식 `setData` 슬라이싱 + 외부 재생 버튼 (LWC 내부 조작 없음) |
| 사용자 정의 study 작성기 | 미지원 — 필요 시 Advanced Charts |
| 틱 차트 전용 시리즈 | LineSeries로 `update({time, value})` (LWC 공식, 비균등 시간 자연 처리) |

### 11-3. 차트 컴포넌트 추상화 (얇은 래퍼만)

```tsx
// frontend/src/components/chart/Chart.tsx
type ChartProps = {
  data: { candles: Candle[]; ticks?: Tick[]; indicators?: IndicatorSeries[] };
  height: number;
  realtime?: { topic: string };
  onCapture?: (png: Blob) => void;
};
// 구현: LWC 공식 API 호출만. 자체 캔버스 그리기·DOM 조작·내부 prototype hack 금지.
// pages/* 는 <Chart/>만 import (props 표면 단순화 목적).
// 미래 Advanced Charts 전환 시 어댑터 교체 가능 — 하지만 그건 ADR 0027 발동 시점.
```

### 11-4. 차트 캡처 — LWC 공식 + 서버 사이드 2단

1. **1차** — `chart.takeScreenshot()` (LWC 공식 API)
2. **2차 (서버)** — pyworker `/screenshot`로 차트 페이지 URL → Playwright PNG

`html-to-image` 등 외부 캡처 라이브러리 도입 금지 — LWC 공식 캡처가 안 되는 케이스는 서버 사이드로 위임.

`ChartSnapshot.source` 필드(`CLIENT_LWC` / `SERVER_BROWSER`)로 경로 추적.

### 11-5. 커스텀 지양 원칙 (사용자 명시 제약)

| 항목 | 정책 |
|------|------|
| LWC 공식 API 호출 | ✅ 허용 (시리즈 추가, setData/update, 스케일·옵션 설정, 공식 이벤트 구독) |
| props로 데이터 받아 LWC에 전달하는 React 래퍼 | ✅ 허용 (얇게) |
| LWC 내부 prototype·private 필드 조작 | ❌ 금지 |
| 차트 위에 자체 `<canvas>` 오버레이 | ❌ 금지 |
| LWC가 제공하지 않는 기능을 외부 라이브러리로 흉내 (drawing, indicator 위젯, 캡처 라이브러리 등) | ❌ 금지 |
| 그래도 사업적으로 필수가 된 기능 | ADR 0027 발동(Advanced Charts 전환), 커스텀 코드로 메우지 않음 |

→ **MVP 동안 차트 컴포넌트 코드는 "LWC 공식 가이드 예시 ± 데이터 어댑팅" 수준을 넘지 않는다.** PR 리뷰 시 이 원칙을 명시 게이트로.

### 11-6. 실시간 (STOMP over WebSocket)

- api-app `spring-boot-starter-websocket` + STOMP broker
- consumer-app이 처리한 결과를 api-app이 직접 Kafka consume → STOMP fan-out (MVP는 이 방식)
- Phase E+ 에서 Redis pub/sub 또는 Kafka Streams 도입 검토

### 11-7. 페이지 구성

| 페이지 | 핵심 컴포넌트 |
|--------|------------|
| Dashboard | TickChart + 포지션 요약 + AI 인사이트 패널 |
| Backtest | 전략 선택·실행·결과 (Replay 차트) |
| Journal | 매매일지 + 차트 스크린샷 첨부 + 사용자 코멘트 |
| Strategy | DSL 편집·미리보기 |
| HarnessOps | 에이전트·Phase·QA 이슈 카탈로그·CLAUDE.md 변경 이력 |

## 12. ADR 변동

### 12-1. 신규 ADR (8건)

| ADR | 제목 |
|-----|------|
| 0019 | MSA 모듈 재구성 (5 모듈 + pyworker + frontend) |
| 0020 | Kafka 도입 (Spring Kafka + aiokafka) |
| 0021 | 하이브리드 런타임 (api+scheduler / batch / consumer 3 앱) |
| 0022 | 프론트엔드 SPA 전환 (React+TS+Vite, TradingView Lightweight Charts) |
| 0023 | Python 모듈 도입 (`pyworker`) |
| 0024 | Docker 표준 (모듈별 Dockerfile + compose 통합 기동) |
| 0025 | 데이터 저장소 정책 (PostgreSQL 단일 인스턴스, 스키마 분리) |
| 0026 | 객체 저장소 도입 (MinIO/S3) |

### 12-2. Supersede / 수정

| 기존 | 처리 |
|------|------|
| 0001 (Kotlin 2.0) | 유지 |
| 0002~0008 | 유지 + 모듈 위치 갱신 주석 |
| 0009 (브랜치 전략) | 유지 |
| 0010 (Husky) | 유지 (0017 강제화 포함) |
| 0011 (QA 스크린샷) | **개정** — 클라이언트(LWC) + 서버(Playwright) 두 경로 명시 |
| 0012 (2단 머지) | **Superseded by 0015** (이미) |
| 0013, 0015~0018 | 유지 |
| 0014 (모델 차등) | 유지 + ai-engineer 책임 확장 (pyworker 연동) |

### 12-3. 미래 후보

- ADR 0027 (Phase E+ 후보): Advanced Charts 도입 — **트리거 조건**: LWC 공식 API로 안 되는 기능(그리기 도구·custom study·심볼 비교·고급 replay 등)이 사업적으로 분명히 필요해지고, 커스텀 코드로 메우는 게 아니라 라이브러리 교체로 가야 한다고 architect가 판단한 시점.

## 13. 에이전트 책임 갱신 (`.claude/agents/*.md`)

| 에이전트 | 변경점 |
|---------|-------|
| architect | ADR 0019~0026 작성, 모듈 경계·이벤트 토폴로지 가드 |
| backend-core | 5 Gradle 모듈 + Dockerfile 책임. application.yml 앱별 분리 |
| market-data | KIS REST → `domain/market`, KIS WS → `consumer/kis_ws_bridge`. 뉴스·DART는 pyworker 검수 |
| strategy-engine | `domain/strategy` 라이브러리화. 백테스트=batch, 실시간 신호=consumer |
| ai-engineer | Spring AI(JVM) + **pyworker 설계·프롬프트 일관성** 책임 추가 |
| frontend | Thymeleaf 종료. React+TS+Vite, LWC, 클라이언트 차트 캡처 |
| qa | Playwright via pyworker 자동화 후보 검토. On-demand 그대로 |

## 14. 마이그레이션 — 빅뱅 단계별 (안전 장치 포함)

1. 신규 5 모듈 + pyworker + frontend 디렉토리 생성 → 빈 build 통과 확인
2. domain 패키지로 코드 일괄 이전 → `gradle :domain:build` 통과
3. 각 앱 main + 최소 컨피그 → `bootRun` 통과
4. 기존 7 디렉토리(`app/core/market-data/strategy/ai/order-exec/web`) 일괄 `git rm`
5. settings.gradle.kts 갱신 → 전체 빌드 + Docker 빌드 통과
6. pyworker 별도 검증 (`docker compose up pyworker`)
7. 통합 스모크 (api → 백테스트 → batch → consumer → 매매일지 → 프론트)

## 15. 테스트·검증

### 15-1. 빌드·테스트 매트릭스

| 단계 | 명령 | 게이트 |
|------|------|-------|
| domain | `./gradlew :domain:test` | kotest + MockK |
| 각 앱 단위 | `./gradlew :<app>:test` | Spring slice |
| 각 앱 통합 | `./gradlew :<app>:integrationTest` | Testcontainers (Postgres·Kafka) |
| pyworker 단위 | `cd pyworker && pytest` | pytest + pytest-asyncio |
| pyworker 통합 | `pytest -m integration` | testcontainers-python |
| frontend 단위 | `pnpm -C frontend test` | Vitest + RTL |
| frontend e2e | `pnpm -C frontend test:e2e` | Playwright (MVP 후) |
| 도커 빌드 | `docker compose build` | 5 이미지 모두 성공 |
| 통합 스모크 | `docker compose up -d && scripts/smoke.sh` | 왕복 통과 |

### 15-2. 통합 시나리오 (스모크)

| # | 시나리오 |
|---|---------|
| 1 | 백테스트 트리거 → 결과 → 매매일지 표시 |
| 2 | 실시간 시세 → 신호 → STOMP → 프론트 차트 |
| 3 | 뉴스 크롤링(pyworker) → AI 분석(consumer) → 인사이트 표시 |
| 4 | 클라이언트 차트 캡처 → 매매일지 첨부 |
| 5 | 서버 페이지 캡처(pyworker) → URL 응답 |

### 15-3. QA 정책 (ADR 0016 그대로)

이 재구성은 7 트리거 중 5개에 해당 → **빅뱅 PR이 main 진입 직전 1회 풀 QA**. 단계별 PR은 셀프 리뷰 + 자동 테스트.

## 16. 하네스 갱신 항목

| 파일 | 변경 |
|------|------|
| `_workspace/_harness-state.json` | Phase D 시작 + 본 재구성 task |
| `_workspace/arch/adr/0019~0026.md` | 신규 |
| `_workspace/arch/adr/README.md` | 인덱스 갱신 |
| `_workspace/arch/branching-and-qa-gates.md` | MSA 빌드·QA 매트릭스 |
| `_workspace/backend/setup-notes.md` | 5 모듈 + Docker |
| `_workspace/frontend/setup-notes.md` | React 프로젝트 부트 |
| `_workspace/python/setup-notes.md` (신규) | uv·Playwright·Kafka |
| `.claude/skills/magicjar-spring-boot-scaffold/SKILL.md` | 5 모듈 스캐폴드 |
| `.claude/skills/magicjar-dashboard/SKILL.md` | React + LWC |
| `.claude/skills/magicjar-spring-ai/SKILL.md` | pyworker 연동 |
| `.claude/skills/magicjar-orchestrator/SKILL.md` | 빅뱅 단계별 라우팅 시퀀스 |
| `docker/docker-compose*.yml` | 신규 |
| `Makefile` 또는 `scripts/dev.sh` (신규) | `make up`/`make smoke` |
| `CLAUDE.md` 변경 이력 | 본 재구성 항목 추가 |
| `README.md` | 5 모듈 + 빠른 시작 |

## 17. 위험·완화

| 위험 | 완화 |
|------|------|
| 빅뱅이라 한 번에 빌드 깨질 가능성 | 14절 단계별 안전 장치 (각 단계 build/bootRun 통과 후 다음) |
| Phase B 코드 손실 | 7 디렉토리 `git rm`은 마지막 단계, 그 전에 신규 위치 빌드 통과 확인 |
| Kafka 학습 곡선 | docker compose로 로컬 클러스터 → 1 토픽씩 추가하며 검증 |
| pyworker Playwright 이미지 크기 (~1GB+) | playwright slim base + `--with-deps chromium`만 (1 브라우저) |
| LWC 캡처 버전 호환성 | 11-4 절 2단 fallback (LWC 공식 + 서버 사이드) |
| MSA 운영 복잡도 (단계 D 단계에선 과한 분할) | compose로 로컬 통합 단순화. k8s는 실서비스 진입 시 |

## 18. 다음 단계

1. **본 spec 사용자 검토** ← 현재
2. ADR 0019~0026 발행 (`magicjar-adr` 스킬)
3. writing-plans 스킬로 단계별 구현 계획서 작성
4. orchestrator 스킬로 에이전트 라우팅 시작
5. Phase D 빌드·검증
6. main PR + QA 풀 게이트 1회 통과 → 머지
