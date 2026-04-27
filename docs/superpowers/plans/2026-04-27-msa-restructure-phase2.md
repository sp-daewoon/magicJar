# Phase 2 — JVM 3 앱 스켈레톤 + Kafka 와이어링 + _legacy 영구 이전

> **Sub-skill:** `superpowers:subagent-driven-development` 또는 `executing-plans`. 본 plan은 backend-core 에이전트(sonnet, ADR 0014)에게 위임됨.

**Goal:** P1 머지(`645d36a`) 위에서 api/batch/consumer 3 Spring Boot 앱 스켈레톤 + Kafka 토폴로지 + Dockerfile + `_workspace/_legacy/`에 백업된 코드를 application layer로 영구 이전. `bootRun` 3개 모두 통과 + 임베디드 Kafka 왕복 스모크 1건이 종료 게이트.

**Source:** spec `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` (§2 §3 §4 §8 §9), master plan `2026-04-27-msa-restructure.md`, ADR 0019~0026.

**Branch:** `agent/backend-core/p2-jvm-apps`
**Author:** `bot+backend-core@magicjar.local` (ADR 0017)
**Model:** sonnet (필요 시 1회 opus 에스컬레이션, ADR 0014)

---

## 0. Pre-flight

- [ ] **0-1**: `git checkout main && git pull && ./gradlew clean build` BUILD SUCCESSFUL 확인
- [ ] **0-2**: 작업 브랜치 `git checkout -b agent/backend-core/p2-jvm-apps`
- [ ] **0-3**: `_workspace/_legacy/README.md`로 백업 카탈로그 일독, 본 plan §3 매핑과 일치 확인

---

## 1. 3 앱 build.gradle.kts (placeholder → 본격)

### 1-A. api/build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    // Web + Scheduler + STOMP
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // 데이터
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Kafka (api도 STOMP fan-out 위해 일부 토픽 consume)
    implementation("org.springframework.kafka:spring-kafka")

    // Spring AI (api에서도 동기 분석 호출 가능)
    implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")

    // MinIO
    implementation("io.minio:minio:8.5.12")

    // 보안 (rate-limit·인증은 P3+ 보강)
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:kafka:1.20.4")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")
}
```

### 1-B. batch/build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    implementation("org.springframework.kafka:spring-kafka")  // 결과 publish
    implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
}
```

### 1-C. consumer/build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")  // KIS WS 클라이언트용
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")
    implementation("io.minio:minio:8.5.12")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:kafka:1.20.4")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")
}
```

→ 셋 다 placeholder에서 본격 build.gradle.kts로 교체. `:bootJar` 가능하도록.

---

## 2. 3 앱 main 클래스 + 최소 config

### 2-A. api

`api/src/main/kotlin/com/mj/api/MagicJarApiApplication.kt`:

```kotlin
package com.mj.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
@EnableScheduling
class MagicJarApiApplication

fun main(args: Array<String>) {
    runApplication<MagicJarApiApplication>(*args)
}
```

### 2-B. batch

`batch/src/main/kotlin/com/mj/batch/MagicJarBatchApplication.kt`:

```kotlin
package com.mj.batch

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
@EnableBatchProcessing
class MagicJarBatchApplication

fun main(args: Array<String>) {
    runApplication<MagicJarBatchApplication>(*args)
}
```

### 2-C. consumer

`consumer/src/main/kotlin/com/mj/consumer/MagicJarConsumerApplication.kt`:

```kotlin
package com.mj.consumer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
@EnableKafka
class MagicJarConsumerApplication

fun main(args: Array<String>) {
    runApplication<MagicJarConsumerApplication>(*args)
}
```

---

## 3. _legacy 백업분 → application layer 영구 이전 (핵심)

### 3-1. 매핑표 (필수)

| _legacy/ | 신규 위치 | 패키지 prefix | 비고 |
|---------|----------|------------|------|
| `app/MagicJarApplication.kt` | **삭제** (각 앱 자체 main 사용) | — | 위 §2의 3개 main이 대체 |
| `app-config/SecretMaskingConverter.kt` | `api/src/main/kotlin/com/mj/api/config/` | `com.mj.api.config` | 로깅 마스킹 — api에서 |
| `app-config/AiConfig.kt` | `consumer/src/main/kotlin/com/mj/consumer/config/` | `com.mj.consumer.config` | AI 호출 위치 — consumer에서 |
| `app-config/JpaScanConfig.kt` | **삭제** (3앱 main의 `@EntityScan` + `@EnableJpaRepositories`로 대체) | — | |
| `app-config/OrderSenderConfig.kt` | `consumer/src/main/kotlin/com/mj/consumer/config/` | `com.mj.consumer.config` | 주문 송신은 consumer/order_dispatcher |
| `app-resources/application.yml` (공통) | 3앱 각자 `src/main/resources/application.yml`로 분할 | — | datasource·redis·kafka·logging 공통 |
| `app-resources/application-{backtest,mock,real}.yml` | api·batch·consumer 모두 동일 yml 복제 (모드 스위치 ADR 0006 유지) | — | |
| `app-resources/logback-spring.xml` | 3앱 각자 `src/main/resources/`에 복제 | — | |
| `app-resources/db/migration/V0__init.sql` | api에 두고 Flyway 실행 | — | api가 DB owner. batch는 `flyway.enabled=false` |
| `market-kis/*` | `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/` | `com.mj.consumer.infrastructure.kis` | KIS REST + WS 모두 consumer에서 (시세 publish 책임) |
| `market-persistence/*` | `api/src/main/kotlin/com/mj/api/infrastructure/persistence/` | `com.mj.api.infrastructure.persistence` | api가 DB owner. consumer/batch도 같은 엔티티 의존하면 domain port + api 구현 호출 (HTTP 또는 직접 JPA) |
| `market-data-resources/application-market-data.yml` | api/application.yml로 흡수 | — | KIS 키·rate-limit |
| `market-data-resources/kis-samples/` | `consumer/src/test/resources/kis-samples/` | — | 테스트 픽스처 |
| `market-data-resources/db/migration/V1, V3` | api/src/main/resources/db/migration/ | — | api Flyway가 적용 |
| `strategy-journal/*` | `consumer/src/main/kotlin/com/mj/consumer/application/journal/` | `com.mj.consumer.application.journal` | TradeJournalRecorder는 consumer에서 이벤트 수신 후 영속 |
| `strategy-journal/TradeJournalJpa(Repository)` | `api/src/main/kotlin/com/mj/api/infrastructure/persistence/journal/` | `com.mj.api.infrastructure.persistence.journal` | api가 DB owner |
| `strategy-backtest-app/BacktestEngine.kt + BacktestOrderSender.kt` | `batch/src/main/kotlin/com/mj/batch/job/backtest/` | `com.mj.batch.job.backtest` | 백테스트는 batch 잡에서 |
| `strategy-dsl-app/DslStrategyEvaluator.kt` | `consumer/src/main/kotlin/com/mj/consumer/application/strategy/` | `com.mj.consumer.application.strategy` | 실시간 신호 평가 |
| `strategy-test-app/` | 각자 위치 따라 (BacktestEngineSmokeTest → batch test, EvaluatorTest → consumer test, TradeJournal* → consumer test + api integration test) | — | |
| `strategy-resources/application-strategy.yml` | consumer + batch yml로 흡수 | — | |
| `strategy-resources/db/migration/V2__strategy_journal.sql` | api/src/main/resources/db/migration/ | — | api Flyway |
| `ai-app/client/* + util/JsonMapper은 이미 domain` | `consumer/src/main/kotlin/com/mj/consumer/application/ai/client/` | `com.mj.consumer.application.ai.client` | Spring AI 호출 |
| `ai-app/config/*` | `consumer/src/main/kotlin/com/mj/consumer/config/ai/` | `com.mj.consumer.config.ai` | |
| `ai-app/service/*` | `consumer/src/main/kotlin/com/mj/consumer/application/ai/service/` | `com.mj.consumer.application.ai.service` | SentimentAnalyzer, TradeReviewer 등 — consumer가 토픽 받아 호출 |
| `ai-app/PromptLoader.kt` | `consumer/src/main/kotlin/com/mj/consumer/application/ai/prompt/` | `com.mj.consumer.application.ai.prompt` | classpath /prompts/*.st 로드 (이미 domain/resources/prompts/) |
| `ai-test-app/service/*` | `consumer/src/test/kotlin/com/mj/consumer/application/ai/service/` | — | |
| `ai-resources/application-ai.yml` | consumer/application.yml에 흡수 (anthropic·openai 키) | — | |
| `web/kotlin/` | **삭제** (P3 React 재작성, 영구 위치 없음) | — | 단 controller/api/service의 비즈니스 로직 의도는 P3 frontend 라우터·페이지 설계 시 참고 |
| `web-templates/`, `web-resources/static/` | **삭제** (P3 React) | — | |
| `web-resources/application-web.yml` | api/application.yml 흡수 (server.port·security 등) | — | |

→ 패키지 prefix 일괄 변경: `kr.spdaewoon.magicjar.{config,marketdata,strategy,ai,web}.*` → 위 표에 맞는 `com.mj.{api,batch,consumer}.*`.

### 3-2. 이전 절차 (영역별 sub-task)

각 sub-task는 (a) `mv` (b) `sed` 패키지·import 치환 (c) `:<app>:compileKotlin` 통과 (d) commit. backend-core 에이전트가 자체 판단으로 sub-task 단위 또는 영역 묶음 단위로 commit (커밋 author=`bot+backend-core@magicjar.local`).

---

## 4. application.yml 4종 작성

### 4-1. 공통 (모든 앱이 베이스로 사용)

`<app>/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: magicjar-<app>
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/magicjar}
    username: ${DB_USER:magicjar}
    password: ${DB_PASSWORD:magicjar}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.mj"
      group-id: ${KAFKA_GROUP_ID:magicjar-<app>}
      auto-offset-reset: earliest

management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,metrics

logging:
  level:
    root: INFO
    com.mj: DEBUG
```

### 4-2. api 추가 항목

```yaml
server:
  port: 8080
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
magicjar:
  pyworker:
    base-url: ${PYWORKER_URL:http://localhost:8000}
```

### 4-3. batch 추가 항목

```yaml
spring:
  batch:
    jdbc:
      initialize-schema: always
    job:
      enabled: false   # 명시 트리거만 실행
  flyway:
    enabled: false   # api가 owner
server:
  port: 8081
```

### 4-4. consumer 추가 항목

```yaml
spring:
  flyway:
    enabled: false
server:
  port: 8082
spring.ai:
  openai:
    api-key: ${OPENAI_API_KEY:}
    chat:
      options:
        model: ${OPENAI_MODEL:gpt-4o-mini}
magicjar:
  kafka:
    topics:
      tick: market.tick.v1
      candle1m: market.candle.1m.v1
      newsRaw: news.raw.v1
      newsAnalyzed: news.analyzed.v1
      dartDisclosure: dart.disclosure.v1
      screenshotCaptured: screenshot.captured.v1
      signal: signal.v1
      orderIntent: order.intent.v1
      orderExecuted: order.executed.v1
      journalEntry: journal.entry.v1
```

---

## 5. Kafka 토폴로지 클래스 — 클래스당 한 책임

각 클래스는 한 토픽·한 책임. 토픽 문자열은 클래스 내부에 캡슐화 (`@KafkaListener(topics = ["\${magicjar.kafka.topics.tick}"]`).

### 5-1. consumer/.../messaging/

```
com.mj.consumer.messaging/
├─ producer/
│  ├─ TickPublisher.kt              market.tick.v1
│  ├─ CandlePublisher.kt            market.candle.1m.v1
│  ├─ SignalPublisher.kt            signal.v1
│  ├─ OrderIntentPublisher.kt       order.intent.v1
│  ├─ OrderExecutedPublisher.kt     order.executed.v1
│  ├─ JournalEntryPublisher.kt      journal.entry.v1
│  └─ NewsAnalyzedPublisher.kt      news.analyzed.v1
└─ listener/
   ├─ TickAggregatorListener.kt     market.tick.v1 → 집계 → CandlePublisher
   ├─ StrategyRunnerListener.kt     market.candle.1m.v1 → 신호 → SignalPublisher
   ├─ OrderDispatcherListener.kt    order.intent.v1 → KIS 주문 → OrderExecutedPublisher
   ├─ JournalRecorderListener.kt    order.executed.v1 → 영속 + JournalEntryPublisher
   ├─ NewsAnalyzerListener.kt       news.raw.v1 → SentimentAnalyzer → NewsAnalyzedPublisher
   └─ DartDisclosureListener.kt     dart.disclosure.v1 → 처리 → DB
```

### 5-2. api/.../messaging/

```
com.mj.api.messaging/
├─ producer/
│  └─ ScrapeRequestPublisher.kt     pyworker HTTP 트리거 결과를 토픽으로 (선택)
└─ listener/
   ├─ TickStompFanoutListener.kt    market.tick.v1 → STOMP /topic/market.tick.{symbol}
   ├─ NewsAnalyzedStompFanoutListener.kt  → STOMP /topic/news
   ├─ JournalEntryStompFanoutListener.kt  → STOMP /topic/journal
   └─ OrderExecutedStompFanoutListener.kt → STOMP /topic/orders
```

### 5-3. batch/.../messaging/

```
com.mj.batch.messaging/
└─ producer/
   ├─ JournalEntryPublisher.kt      백테스트 결과 매매일지 publish
   └─ BacktestResultPublisher.kt    backtest.result.v1 (신규 토픽 가능)
```

### 5-4. 신규 도메인 이벤트 객체 (domain 추가 가능)

`com.mj.domain.market.event.TickEvent`, `CandleEvent`, `OrderIntentEvent`, `OrderExecutedEvent`, `SignalEvent`, `JournalEntryEvent`, `NewsRawEvent`, `NewsAnalyzedEvent`, `DartDisclosureEvent` — 모두 Kotlin data class + Jackson serializable. domain에 위치 (메시지 스키마는 도메인 어휘이므로).

---

## 6. Dockerfile (3 앱)

각 앱 동일 패턴:

`<app>/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1.6
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S magicjar && adduser -S -G magicjar magicjar
WORKDIR /app
COPY build/libs/<app>-*.jar /app/app.jar
USER 1000
EXPOSE 808X
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
  CMD wget -qO- http://localhost:808X/actuator/health | grep -q UP || exit 1
```

→ `EXPOSE` 와 `HEALTHCHECK` URL의 포트는 api=8080, batch=8081, consumer=8082.
→ 멀티 스테이지로 build 단계 추가 가능 (gradle wrapper 사용 시) — backend-core가 결정. 단순화 위해 위 단일 스테이지 + `./gradlew :api:bootJar` 후 build/libs/*.jar 사용 권장.

---

## 7. 임베디드 Kafka 통합 테스트 1건 (consumer)

`consumer/src/test/kotlin/com/mj/consumer/messaging/TickRoundtripIT.kt`:

`@SpringBootTest` + `@EmbeddedKafka` 또는 Testcontainers Kafka. TickPublisher → 토픽 publish → TickAggregatorListener 호출 검증.

스모크 1건만. 나머지는 P4 풀 통합.

---

## 8. 종료 게이트

- [ ] `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar` 모두 SUCCESSFUL
- [ ] `./gradlew :api:bootRun` → `/actuator/health` UP (별 터미널에서)
- [ ] `./gradlew :batch:bootRun` → 동일
- [ ] `./gradlew :consumer:bootRun` → 동일
- [ ] `./gradlew :consumer:test --tests "*TickRoundtripIT"` PASS
- [ ] 각 앱 `docker build -t magicjar/api:p2 ./api` 성공 (3개 모두)
- [ ] `_legacy/` 영역 중 위 §3-1 매핑 표에 해당하는 항목 모두 신규 위치로 이전 (남은 것은 web/* + web-resources/static/* + web-templates/* — P3 frontend로 이전)
- [ ] `git grep "kr.spdaewoon.magicjar"` 0건 (모든 Kotlin 소스)
- [ ] PR `agent/backend-core/p2-jvm-apps` → `main` rebase 머지

---

## 9. 발견 시 사용자에게 통보 (autonomy 경계)

- **아키텍처 변경**: §3-1 매핑표를 벗어난 신규 위치 결정 필요 시 (예: market-persistence를 batch가 아닌 api에 두기로 했지만 batch에서도 직접 JPA로 읽는 게 자연스러운 경우 등)
- **외부 자원**: 신규 PostgreSQL 스키마 생성, 신규 환경변수 추가 (`.env.example` 변경)
- **블로킹 상충**: 컴파일 에러가 §3 매핑으로 해결 안 되는 경우 (예: 의존 순환)

위 3가지 외에는 backend-core 자율 판단 + commit 메시지에 결정 근거 명시.

---

## 10. 후속 단계

- **P3 (병렬)**: ai-engineer (opus) — `pyworker/`. frontend (sonnet) — `frontend/` React + LWC.
- **P4**: architect (opus) — `docker/` compose + 통합 스모크 + main 풀 QA.
