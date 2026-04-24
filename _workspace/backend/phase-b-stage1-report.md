# Phase B-1 산출물 리포트 — backend-core

- 브랜치: `agent/backend-core/scaffold-phase-b`
- 작성: 2026-04-25
- 대상 ADR: 0001 (Kotlin 2.0/Boot 3.3), 0002 (7-module), 0006 (OrderSender 3-mode), 0007 (TradeJournal), 0008 (Harness)

## 1. 생성 · 수정 파일 트리 (주요)

```
magic_ch1/
├── settings.gradle.kts                       # rootProject=magicjar, include 7 modules
├── build.gradle.kts                          # 루트: 공통 플러그인·Spring/AI BOM·ktlint·JDK21 toolchain
├── gradle.properties                         # JVM 2g, caching on, parallel
├── gradlew / gradlew.bat / gradle/wrapper/*  # Gradle 8.10.2
├── .editorconfig                             # Kotlin 4-space, 140 cols
├── docker-compose.yml                        # postgres:15 + redis:7
├── .env.example                              # KIS/Anthropic/DART/DB/Redis (값 비어있음)
├── README.md                                 # 빌드·실행 섹션 확장
├── core/
│   ├── build.gradle.kts                      # 순수 Kotlin (jackson-annotations, slf4j-api)
│   └── src/main/kotlin/kr/spdaewoon/magicjar/core/
│       ├── domain/  (21 파일: Instrument, Candle, Order, TradeJournalEntry, HarnessState ...)
│       ├── event/   (3 파일: OrderFilledEvent, AiReviewRequestedEvent, PhaseChangedEvent)
│       └── port/    (9 파일: CandleRepository, InstrumentCatalog, NewsRepository,
│                              OrderSender, BrokerGateway, StrategyEvaluator, AiClient,
│                              TradeJournalRepository, HarnessStateReader)
├── market-data/build.gradle.kts              # web+websocket+JPA+Resilience4j+Bucket4j+Postgres+Flyway
├── strategy/build.gradle.kts                 # JPA+Postgres+Flyway
├── ai/build.gradle.kts                       # spring-ai-anthropic/ollama starter
├── order-exec/build.gradle.kts               # spring-boot-starter
├── web/build.gradle.kts                      # web+websocket+thymeleaf+actuator+springdoc
├── app/
│   ├── build.gradle.kts                      # 전 모듈 implementation + spring-boot plugin
│   └── src/main/
│       ├── kotlin/kr/spdaewoon/magicjar/
│       │   ├── MagicJarApplication.kt                      # @SpringBootApplication, KST TZ
│       │   └── config/
│       │       ├── AiConfig.kt + NoOpAiClient              # @ConditionalOnMissingBean AiClient
│       │       ├── OrderSenderConfig.kt + PlaceholderOrderSender  # TODO Phase B-2 대체
│       │       └── SecretMaskingConverter.kt               # logback conversionRule
│       └── resources/
│           ├── application.yml                             # 기본(mock), magicjar.mode/allow-real
│           ├── application-mock.yml / -backtest.yml / -real.yml
│           ├── logback-spring.xml                          # KST, JSON(prod) + text(dev) + %mask
│           └── db/migration/V0__init.sql                   # magicjar_schema_info placeholder
└── {market-data,strategy,ai,order-exec,web}/src/main/kotlin/.../XxxModuleMarker.kt
    # 빈 소스셋 방지 임시 마커 (각 에이전트 첫 구현 시 삭제)
```

총 핵심 파일 수 약 60개 (Kotlin 소스 ~45, Gradle/설정 ~15).

## 2. 빌드 검증 결과

환경: JDK 17 런처 + Gradle 8.10.2 + JDK 21 toolchain (foojay auto-provision).
JDK 21은 Gradle이 자동 다운로드한다.

| 명령 | 결과 |
|------|------|
| `./gradlew --version` | Gradle 8.10.2, Kotlin 1.9.24, OS Mac OS X aarch64 |
| `./gradlew :core:build` | `BUILD SUCCESSFUL in 540ms` (jar 생성, ktlint pass) |
| `./gradlew :app:classes` | `BUILD SUCCESSFUL in 1s` (core→order-exec/web/market-data/strategy/ai→app 전 모듈 컴파일) |
| `./gradlew ktlintCheck` | `BUILD SUCCESSFUL in 741ms` (35 actionable tasks, 모든 모듈 스타일 통과) |

부트런(`:app:bootRun`)은 PostgreSQL/Redis가 기동되지 않은 상태에서 실패 가능하므로 지시대로 미실행.
`docker compose up -d` 후 `SPRING_PROFILES_ACTIVE=mock ./gradlew :app:bootRun`으로 Phase C QA가 검증.

## 3. PR / 브랜치

- 로컬 브랜치: `agent/backend-core/scaffold-phase-b` 생성
- 원격 푸시 + PR 생성은 커밋 후 이어서 실행 (본 리포트는 커밋 전 작성)
- 머지는 수행하지 않음 — Phase C QA APPROVED 후에만 main 머지 (ADR 0011)

## 4. Phase B-2 4개 에이전트에게 전달할 주의사항

### 공통 패키지 루트
- `kr.spdaewoon.magicjar.core.{domain,event,port}` — 기존 파일 그대로 import
- 각 어댑터 모듈 패키지: `kr.spdaewoon.magicjar.{marketdata,strategy,ai,orderexec,web}`
- 첫 구현 추가 시 해당 모듈의 `XxxModuleMarker.kt`를 삭제 가능

### core 포트 시그니처 (변경 시 architect + backend-core 협의 필수)
- `OrderSender.send(order: Order): OrderResult` — 동기. 비동기 필요하면 ADR
- `AiClient.chat / chatJson` — `null` 반환 = AI 비활성 상태 (호출 측이 빈 응답으로 처리)
- `CandleRepository.save(List<Candle>)` — upsert 의미론
- `TradeJournalRepository.search(filter: TradeJournalFilter)` — 필터 data class 제공됨
- `BrokerGateway` — KIS 어댑터는 반드시 이 포트 뒤에. order-exec은 market-data를 직접 import 금지

### 프로퍼티 키
- `magicjar.mode` ∈ `BACKTEST | MOCK | REAL` (Mode enum과 동일 표기)
- `magicjar.allow-real` — REAL 모드 이중 스위치 (기본 false)
- `magicjar.harness.enabled` — 하네스 대시보드 노출
- DB: `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (docker-compose 기본값: magicjar/magicjar/magicjar)
- Redis: `SPRING_REDIS_HOST/PORT`

### OrderSender 빈 등록 규칙
- app 모듈의 `PlaceholderOrderSender`는 `@ConditionalOnMissingBean(OrderSender::class)`로 등록됨
- strategy의 `BacktestOrderSender`, order-exec의 `MockOrderSender`/`RealOrderSender`는
  `@Component` 또는 `@Bean`으로 등록하면 자동 대체됨
- 3 모드 중 하나만 단일 빈으로 등록해야 함 (프로파일/mode 분기 구성은 각 모듈에서 수행)

### Flyway 마이그레이션 번호 예약
- V0 (app): 초기 placeholder (변경 금지)
- V1~V9: market-data 전용 (candle, instrument, news 등)
- V10~V19: strategy 전용 (backtest_run, trade_journal 등)
- V20~V29: ai/web 기타
- 각 모듈 모두 classpath `db/migration`에 놓으면 Flyway가 모아 실행함

### AI 키 없이 부팅
- ANTHROPIC_API_KEY 미설정 → `NoOpAiClient` 자동 주입
- ai 모듈의 실제 `SpringAiChatClient` 구현은 `@ConditionalOnProperty(...)` 또는 `@ConditionalOnBean(ChatClient.class)` 등으로 가드할 것
- UI는 `aiEnabled` 플래그로 배너 노출 판단

### 테스트 라이브러리
- JUnit 5 + kotest-assertions 5.9.1 + MockK 1.13.13 (전 모듈 자동 주입됨)
- Spring 통합 테스트는 app 모듈에서 `spring-boot-starter-test`(mockito는 제외됨, MockK 사용)

### ktlint 주의사항
- `function-expression-body` 규칙 활성 상태 — 단순 함수는 `= ...` 표현식 바디 선호
- data class 파라미터 1개여도 multi-line 필수 (`val x: T,\n)`)
- import는 lexicographic 순서 (`jakarta.*` → `kr.spdaewoon.*` → `org.*` → `java.*`)
