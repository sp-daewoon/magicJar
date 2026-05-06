# PR-5 W1 모듈 부트스트랩 설계 가이드

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 대상 PR 시리즈: PR-5a (backend-core) · PR-5b (frontend) · PR-5c (CI 정상화 — 선택)
- 진입 prerequisite: W1 트랙 (T-ONBOARD · T-W1 · T-W2 · T-S1 · T-MS1 · T-IM1 · T-CA1)
- 정합 SoT: `docs/architecture/master-architecture.md` v2 + ADR 0001~0047
- 작업 원칙 (개발 룰북 정합):
  - R1 단계 — 도메인 골격 + 시그니처. 어댑터 본격 구현·UI 화면·테스트는 R2/R3.
  - 한 PR = 한 영역 (backend / frontend 분리).
  - W1·W2·W3 코드에 LLM provider import 0건. AI는 W4 한정.

---

## 1. PR-5 정확한 scope 선언

PR-5는 **부트스트랩 PR**이다. 각 모듈에 "동작하는 빈 골격"만 만들고 비즈니스 로직은 0건.
넘으면 안 되는 선:

| 영역 | PR-5 범위 | PR-5 외 (별도 PR) |
|---|---|---|
| 4 JVM 모듈 디렉토리 | ✅ 신규 생성 + Application stub | KIS 어댑터 실 구현 (PR-6+) |
| Flyway V1 baseline | ✅ 11 테이블 DDL | 비즈니스 row INSERT (PR-7+) |
| Dockerfile 4종 | ✅ multi-stage + healthcheck stub | jar 안 비즈니스 로직 |
| settings.gradle.kts | ✅ 4 모듈 include 정합 | 신규 모듈 추가 |
| docker-compose.yml | ✅ 7 컨테이너 정합 | sidecar 추가 |
| frontend Vite 스캐폴드 | ✅ React 19 + TS + Tailwind + LWC v4 | 셋업 마법사 실제 화면 (PR-6) |
| 라우터 + placeholder | ✅ 빈 라우터 + 스플래시 stub | API 호출·STOMP 구독 (PR-7+) |
| OnboardingController 등 REST | ❌ (PR-6) | 토큰 발급 로직 + AES 서비스 (PR-6) |
| BalanceReconcileJob 비즈니스 | ❌ (PR-7+) | Spring Batch 잡 등록만 stub |

**검증 기준**: 본 PR 머지 후 `./gradlew build` GREEN + `docker compose up -d` 7 컨테이너 healthy + `pnpm build` GREEN. 비즈니스 호출 0건.

---

## 2. 디렉토리 구조 (전체 — PR-5 머지 후 형상)

```
magic_ch1/
├── domain/                                   # [backend-core] 순수 — Spring·JPA·Kafka 의존 0
│   ├── build.gradle.kts                      # bootJar 제외, java-library plugin
│   └── src/main/kotlin/kr/spdaewoon/magicjar/domain/
│       ├── common/                            # RejectReason · zoneKst · Result sealed class
│       ├── instrument/                        # Instrument · InstrumentMarket · InstrumentType (ADR 0017)
│       │   └── port/                          # InstrumentRepository · CorpActionRepository (인터페이스만)
│       ├── market/
│       │   ├── candle/                        # Candle (raw + adjusted) · CandleInterval (ADR 0019)
│       │   ├── session/                       # MarketSession sealed class · MarketSessionResolver port
│       │   └── port/                          # KisRestPort · KisWsPort · QuoteCachePort
│       ├── watchlist/                         # Watchlist · WatchlistEntry (ADR 0028·0040)
│       │   └── port/WatchlistRepository
│       ├── order/                             # OrderIntent · Order · OrderResult · RejectReason (ADR 0011·0015)
│       │   └── port/OrderSender · OrderRepository · OrderRejectAuditRepository
│       ├── position/                          # Position · PnL (ADR 0042)
│       │   └── port/PositionRepository · PositionCachePort
│       ├── risk/                              # KillSwitchState · GuardResult · OrderGuard 인터페이스 (ADR 0024·0043)
│       │   └── port/KillSwitchRepository
│       ├── user/                              # UserAccount · UserKisCredential · SystemConfig (ADR 0046)
│       │   └── port/UserAccountRepository · UserKisCredentialRepository · SystemConfigRepository · CredentialPort
│       ├── secret/                            # AES-256-GCM cipher 인터페이스 (ADR 0046 §2-4)
│       │   └── port/SecretCipher
│       ├── notification/                      # NotificationEvent · NotificationOutbox (ADR 0044)
│       │   └── port/NotificationOutboxRepository · NotificationSender
│       └── audit/                             # AuditTrailEntry (ADR 0015)
│           └── port/AuditTrailRepository
├── api/                                      # [backend-core] REST/STOMP + 외부 어댑터 + Kafka producer
│   ├── build.gradle.kts                      # spring-boot, spring-web, spring-stomp, spring-data-jpa, kafka, flyway
│   ├── Dockerfile                            # multi-stage: COPY build/libs/api-*.jar → eclipse-temurin:21-jre-alpine
│   └── src/main/
│       ├── kotlin/kr/spdaewoon/magicjar/api/
│       │   ├── ApiApplication.kt              # @SpringBootApplication main
│       │   ├── controller/                    # 빈 디렉토리 (PR-6 OnboardingController 등)
│       │   ├── adapter/
│       │   │   ├── kis/                       # 빈 (PR-6 KisAuthAdapter 등 — 패키지 골격만)
│       │   │   ├── yahoo/                    # 빈
│       │   │   ├── dart/                     # 빈
│       │   │   └── naver/                    # 빈
│       │   ├── kafka/                        # 빈 (Kafka producer 어댑터 — PR-6+)
│       │   ├── stomp/                        # 빈 (STOMP config — PR-6+)
│       │   ├── persistence/                  # 빈 (JPA 엔티티·Repository 구현 — PR-6+)
│       │   └── config/                       # 빈 (보안·CORS·Flyway 설정 — PR-6+)
│       └── resources/
│           ├── application.yml                # default profile = dev,real-kis
│           ├── application-dev.yml            # dev 오버라이드
│           ├── application-real-kis.yml       # 실 KIS 오버라이드
│           └── db/migration/
│               └── V1__baseline.sql           # 11 테이블 baseline (이 PR의 핵심)
├── batch/                                    # [backend-core] Spring Batch 잡
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/
│       ├── kotlin/kr/spdaewoon/magicjar/batch/
│       │   ├── BatchApplication.kt
│       │   └── job/                          # 빈 (PR-7+ BalanceReconcileJob · CandleBackfillJob 등)
│       └── resources/application.yml
├── consumer/                                 # [backend-core] Kafka consumer + KIS WS + 시그널 dispatch
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/
│       ├── kotlin/kr/spdaewoon/magicjar/consumer/
│       │   ├── ConsumerApplication.kt
│       │   ├── kafka/                        # 빈 (PR-7+ Kafka @KafkaListener)
│       │   ├── ws/                           # 빈 (PR-7+ KisWsSubscriber)
│       │   ├── ingestion/                    # 빈 (PR-7+ WatchlistIngestionWorker)
│       │   ├── signal/                       # 빈
│       │   ├── order/                        # 빈
│       │   └── notification/                 # 빈 (PR-7+ NotificationDispatcher)
│       └── resources/application.yml
├── frontend/                                 # [frontend] Vite SPA
│   ├── package.json                          # pnpm + React 19 + TS + Tailwind + LWC v4
│   ├── pnpm-lock.yaml                        # pnpm 일관성
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── tsconfig.node.json
│   ├── tailwind.config.js
│   ├── postcss.config.js
│   ├── index.html
│   ├── Dockerfile                            # multi-stage: node:20 build → nginx:alpine
│   ├── nginx.conf                            # SPA fallback (history mode)
│   ├── .gitignore
│   └── src/
│       ├── main.tsx                          # React 19 entry
│       ├── App.tsx                           # 라우터 + 스플래시 stub
│       ├── index.css                         # tailwind directives
│       ├── pages/
│       │   ├── SetupWizardPlaceholder.tsx    # /setup placeholder (PR-6 실 구현)
│       │   └── DashboardPlaceholder.tsx      # / placeholder
│       ├── components/                       # 빈 (PR-6+ chart·ui 등)
│       ├── api/                              # 빈 (PR-6+ TanStack Query)
│       ├── ws/                               # 빈 (PR-6+ STOMP 클라이언트)
│       ├── stores/                           # 빈 (PR-6+ Zustand)
│       └── lib/                              # 빈
├── docker/                                   # 현행 정합 검증 — 변경 최소
│   ├── docker-compose.yml                    # 7 컨테이너 정합 확인 (frontend build context 정합)
│   ├── docker-compose.dev.yml                # 핫 리로드 오버레이 — 현행 유지
│   └── docker-compose.infra.yml              # 인프라 3 — 현행 유지
├── scripts/                                  # 현행 유지 — build-and-deploy.sh 동작 확인
├── _workspace/                               # 산출물 (specs·tasks·arch·strategy·ai)
├── docs/                                     # 현행 SoT 문서
├── settings.gradle.kts                       # 4 모듈 include 정합 (현행 그대로 — 검증만)
├── build.gradle.kts                          # 현행 그대로 (검증만)
├── gradle.properties
├── gradlew · gradlew.bat
├── package.json                              # 현행 (husky 호스트 — 변경 없음)
├── .env.example                              # 현행 (변경 없음)
└── CHANGELOG.md
```

---

## 3. 모듈별 의존성 그래프

### 3-1. Gradle 모듈 의존성

```
                ┌─────────────┐
                │   domain    │   (java-library, bootJar 제외)
                │   순수      │   Spring·JPA·Kafka·Spring AI 의존 0
                └──────┬──────┘
                       │ (api/batch/consumer가 implementation으로 의존)
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
   ┌─────────┐   ┌─────────┐   ┌─────────┐
   │   api   │   │  batch  │   │ consumer│
   │ (boot)  │   │ (boot)  │   │ (boot)  │
   └─────────┘   └─────────┘   └─────────┘

   frontend는 Gradle 외부 — pnpm 빌드, Docker 컨테이너만 compose 정합
```

### 3-2. 주요 라이브러리 (build.gradle.kts 골격 — backend-core가 작성 시 가이드)

#### domain
```kotlin
plugins {
    `java-library`
    kotlin("jvm")
    // Spring Boot plugin 미적용 — bootJar 자동 제외
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")  // KST 처리
    api("com.fasterxml.jackson.module:jackson-module-kotlin")  // 도메인 직렬화 시그니처용 — Jackson core만 (Spring 의존 X)
    // JPA·Spring 의존 0
}

// bootJar 비활성 명시 (이중 안전망)
tasks.named("bootJar") { enabled = false }
```

#### api
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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")  // STOMP
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")  // /actuator/health
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    // KIS HTTP 클라이언트 (PR-6+)
    implementation("org.springframework.boot:spring-boot-starter-webflux")  // WebClient
    // Swagger (사용자 자주 보는 1차 API 문서)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
}
```

#### batch
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
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.postgresql:postgresql")
    // Flyway 미적용 (api만 마이그레이션 — ADR 0006 §마이그레이션)
}
```

#### consumer
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
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-webflux")  // KIS REST·WS 클라이언트
    implementation("org.postgresql:postgresql")
    // KIS WS — Spring WebSocketClient (PR-6+ 본격 구현)
}
```

**LLM provider 의존 0건 의무** — `spring-ai-*`·`anthropic-sdk`·`openai-java` import는 W4 트랙(consumer-app `:ai` 분리 모듈)에서만. PR-5에는 어떤 모듈에도 포함 금지.

---

## 4. 패키지 구조 + 핵심 인터페이스 윤곽

### 4-1. domain (순수)

PR-5에 만들 인터페이스(빈 도메인 + port 시그니처만 — 구현은 PR-6+):

```kotlin
// domain/.../user/UserAccount.kt
package kr.spdaewoon.magicjar.domain.user

data class UserAccount(
    val userId: String,                  // KIS 실전 계좌번호 8자리 (자연 키)
    val displayName: String?,
    val maskedAccount: String,           // "7468****" (DB GENERATED)
    val createdAt: Instant,
    val lastLoginAt: Instant?,
)

enum class AccountType { REAL, MOCK }
```

```kotlin
// domain/.../user/port/UserAccountRepository.kt
package kr.spdaewoon.magicjar.domain.user.port

interface UserAccountRepository {
    fun findById(userId: String): UserAccount?
    fun findActiveTradingUsers(): List<UserAccount>
    fun upsert(account: UserAccount): UserAccount
}
```

```kotlin
// domain/.../secret/port/SecretCipher.kt  (ADR 0046 §2-4)
package kr.spdaewoon.magicjar.domain.secret.port

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}
```

(나머지 21개 도메인 패키지 — 위 §2 디렉토리 트리 참조. 각 패키지 1~3 핵심 도메인 클래스 + port 인터페이스 1~2개 stub.)

**의무 컨벤션**:
- 모든 도메인 클래스 `data class` 또는 `sealed class` (불변)
- port는 `interface`만 — 구현은 어댑터 레이어에서
- `Spring`·`JPA`·`Kafka`·`Redis` 어떤 import도 0건 (정합 검증: backend-core가 `grep -r "import org.springframework" domain/src/main` → 0건)

### 4-2. api 패키지 트리 (시그니처만)

```kotlin
// api/.../ApiApplication.kt
package kr.spdaewoon.magicjar.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["kr.spdaewoon.magicjar"])
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
```

빈 디렉토리 (PR-6+ 채움):
- `controller/` — REST endpoint (PR-6 OnboardingController · PR-7 WatchlistController · ...)
- `adapter/kis/` — KIS REST 어댑터 (PR-6 KisAuthAdapter · PR-7 KisQuotePort 구현)
- `adapter/yahoo/` · `adapter/dart/` · `adapter/naver/` — 외부 어댑터 (PR-7+)
- `kafka/` — `@Component` Kafka producer 래퍼 (PR-7+)
- `stomp/` — `WebSocketConfig` + `/topic/*` 라우팅 (PR-7+)
- `persistence/` — JPA 엔티티 + `@Repository` 구현 (PR-6+)
- `config/` — Flyway·CORS·보안 (PR-6+)

### 4-3. batch · consumer 패키지 트리

`BatchApplication.kt`·`ConsumerApplication.kt` `@SpringBootApplication(scanBasePackages = ["kr.spdaewoon.magicjar"])` + main only. 잡·리스너는 PR-7+에서.

### 4-4. frontend 패키지 트리

```typescript
// src/main.tsx — React 19 entry
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import { App } from './App'

const queryClient = new QueryClient()
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
```

```typescript
// src/App.tsx — 라우터 + placeholder
import { Routes, Route, Navigate } from 'react-router-dom'
import { SetupWizardPlaceholder } from './pages/SetupWizardPlaceholder'
import { DashboardPlaceholder } from './pages/DashboardPlaceholder'

export function App() {
  return (
    <Routes>
      <Route path="/" element={<DashboardPlaceholder />} />
      <Route path="/setup" element={<SetupWizardPlaceholder />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
```

`pages/SetupWizardPlaceholder.tsx` · `pages/DashboardPlaceholder.tsx`는 Tailwind 클래스 + "magicJar" 로고 stub + "PR-6에서 구현 예정" 안내 문구. **PR-5에선 실제 구현 0건**.

---

## 5. Flyway V1 baseline 테이블 정의 (DDL 윤곽)

위치: `api/src/main/resources/db/migration/V1__baseline.sql` (단일 파일).
Flyway 실행: api 모듈만 (ADR 0006 §마이그레이션). `batch`·`consumer`는 `spring.flyway.enabled=false`.

ADR 0006은 `trading` · `ops` 스키마 분리를 명시했으나, **PR-5는 단순화 — `public` 스키마 단일 사용**.
스키마 분리는 PR-7+에서 ALTER TABLE로 이동 (또는 V2에서 본격화). 사유: PR-5는 부트스트랩이라 비즈니스 row 0건 → 스키마 분리의 가치가 0.

> 사용자 결정 필요 항목: 스키마 분리를 PR-5에 포함할지 vs PR-7로 미룰지. **architect 권장 = PR-7로 미룸** (PR-5 분량 축소).

### 5-1. 11 테이블 정의

| # | 테이블 | ADR | 핵심 컬럼 | PK·FK·제약 |
|---|---|---|---|---|
| 1 | `system_config` | 0046 | `id` SMALLINT(=1)·`dart_api_key` BYTEA·`notification_slack_webhook` BYTEA·`created_at`·`updated_at` | PK(id) + CHECK(id=1) singleton |
| 2 | `user_account` | 0046·0010 | `user_id` VARCHAR(20)·`display_name` VARCHAR(100)·`masked_account` GENERATED·`created_at`·`last_login_at` | PK(user_id) |
| 3 | `user_kis_credential` | 0046·0038 | `user_id`·`account_type` CHECK IN ('REAL','MOCK')·`account_number`·`account_prdt_cd` DEFAULT '01'·`app_key_enc` BYTEA·`secret_key_enc` BYTEA·`last_token_iss`·`last_validated_at` | PK(user_id, account_type) + FK user_id → user_account ON DELETE CASCADE |
| 4 | `instrument` | 0017 | `code` VARCHAR(8)·`isin` VARCHAR(12)·`name` VARCHAR(80)·`name_en` VARCHAR(120)·`market` VARCHAR(10) CHECK IN ('KOSPI','KOSDAQ','KONEX')·`type` VARCHAR(20) CHECK IN ('STOCK','ETF','ETN','PREFERRED','WARRANT','REIT')·`sector` VARCHAR(40)·`listed_at` DATE·`delisted_at` DATE·`active` BOOL·`created_at`·`updated_at` | PK(code) + INDEX(market, active) |
| 5 | `watchlist` | 0028·0040 | `id` BIGSERIAL·`user_id`·`code`·`priority` INT·`created_at` | PK(id) + UNIQUE(user_id, code) + FK user_id·code |
| 6 | `candle` | 0019 | `id` BIGSERIAL·`code`·`interval` VARCHAR(8) CHECK IN ('1m','2m','5m','15m','30m','90m','1h','1d','1wk','1mo','1y')·`ts` TIMESTAMPTZ·`open_raw`·`high_raw`·`low_raw`·`close_raw`·`open_adj`·`high_adj`·`low_adj`·`close_adj`·`volume`·`source` VARCHAR(20) | PK(id) + UNIQUE(code, interval, ts) + INDEX(code, interval, ts DESC) |
| 7 | `position` | 0042·0024 | `id` BIGSERIAL·`user_id`·`code`·`quantity` INT·`avg_price` NUMERIC(19,4)·`updated_at` | PK(id) + UNIQUE(user_id, code) + FK user_id·code |
| 8 | `orders` | 0011·0015 | `id` BIGSERIAL·`intent_id` UUID·`user_id`·`code`·`side` VARCHAR(8) CHECK IN ('BUY','SELL')·`order_type` VARCHAR(16)·`quantity` INT·`price` NUMERIC(19,4)·`status` VARCHAR(20) CHECK IN ('PENDING','PARTIAL_FILLED','FILLED','REJECTED','CANCELLED')·`reject_reason` VARCHAR(64)·`reject_detail` TEXT·`reject_source` VARCHAR(32)·`kis_ord_no` VARCHAR(20)·`created_at`·`executed_at` | PK(id) + UNIQUE(intent_id) + INDEX(user_id, created_at DESC) |
| 9 | `kill_switch_state` | 0024·0043 | `user_id`·`enabled` BOOL DEFAULT false·`reason` VARCHAR(64)·`updated_at`·`updated_by` VARCHAR(40) | PK(user_id) + FK user_id |
| 10 | `notification_outbox` | 0044 | `id` BIGSERIAL·`event_topic` VARCHAR(60)·`payload_json` JSONB·`status` VARCHAR(20) CHECK IN ('PENDING','SENT','FAILED')·`retry_count` INT DEFAULT 0·`last_error` TEXT·`created_at`·`sent_at` | PK(id) + INDEX(status, created_at) |
| 11 | `audit_trail` | 0015 | `id` BIGSERIAL·`actor` VARCHAR(40)·`action` VARCHAR(60)·`entity_type` VARCHAR(40)·`entity_id` VARCHAR(40)·`detail_json` JSONB·`occurred_at` TIMESTAMPTZ | PK(id) + INDEX(entity_type, entity_id, occurred_at DESC) |

### 5-2. V1__baseline.sql 작성 의무 (backend-core 가이드)

- 모든 `created_at`·`updated_at`·`occurred_at`은 `TIMESTAMPTZ NOT NULL DEFAULT now()` (UTC 영속, 표현은 KST — master §3-5)
- ENUM은 사용 금지 (Flyway 변경 비용↑) — `VARCHAR + CHECK` 패턴 (위 표 정합)
- 모든 FK 명시 + `ON DELETE CASCADE`는 user_id 계열만 (multi-tenant 정합)
- BIGSERIAL은 PK 자동 — 외부 인용 불요. 자연 키(`code`·`user_id`)는 그대로.
- `system_config (id)` 초기 row INSERT 의무 — `INSERT INTO system_config (id) VALUES (1) ON CONFLICT DO NOTHING;` (V1 마지막 줄)
- 인덱스는 hot path 최소만 — 추가 인덱스는 PR-7+ 트래픽 분석 후 V2/V3로

### 5-3. V1 외 마이그레이션은 PR-5에 포함 X

ADR 0046 §2-7은 V25를 명시하지만 — PR-5 V1 baseline에 통합하여 단순화. V2~V24 결번 (Flyway는 결번 허용). 후속 마이그레이션은 **트랙 진입 시 V2·V3·... 순차 추가**.

---

## 6. application.yml 윤곽 (profile별)

### 6-1. api/src/main/resources/application.yml (default)

```yaml
spring:
  profiles:
    active: dev,real-kis  # default — mock 부팅 path 폐기 (CLAUDE.md 운영 함정 §3)
  application:
    name: magicjar-api
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/magicjar}
    username: ${DB_USER:magicjar}
    password: ${DB_PASSWORD:magicjar}
  jpa:
    hibernate:
      ddl-auto: validate  # Flyway가 스키마 권위
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}

server:
  port: 8080
  shutdown: graceful  # ADR 0045 — SIGTERM HTTP draining

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

magicjar:
  mode: ${MAGICJAR_MODE:MOCK}
  allow-real: ${MAGICJAR_ALLOW_REAL:false}
  ai:
    enabled: ${MAGICJAR_AI_ENABLED:false}
```

### 6-2. application-dev.yml · application-real-kis.yml

PR-5에선 빈 파일 + 주석 ("PR-6+ 활성화") 만 두기. KIS endpoint·base URL은 PR-6에서 채움.

### 6-3. batch · consumer application.yml

거의 동일한 골격 + `server.port: 8081`(batch) / `8082`(consumer).
`spring.flyway.enabled: false` 명시 (api만 Flyway 실행).
`spring.batch.job.enabled: false` (batch — 자동 실행 차단, PR-7+에서 트리거 명시).

---

## 7. Dockerfile 표준 (4 모듈 공통 패턴)

ADR 0008 §Dockerfile 원칙 정합. CLAUDE.md 운영 함정 §1 "stale jar" 가드 정합.

### 7-1. JVM 3 모듈 공통 (api/Dockerfile · batch/Dockerfile · consumer/Dockerfile)

```dockerfile
# syntax=docker/dockerfile:1.7
# 멀티 스테이지 X — gradle 호스트 빌드 후 jar COPY (CLAUDE.md 운영 함정 §1).
# scripts/build-and-deploy.sh가 정석 경로 (gradle bootJar → docker build).

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.source=https://github.com/sp-daewoon/magicJar
LABEL org.opencontainers.image.title=magicjar-<module>
LABEL org.opencontainers.image.licenses=MIT

# 비루트 (ADR 0008)
RUN addgroup -S magicjar && adduser -S -G magicjar magicjar
USER magicjar
WORKDIR /app

# 호스트 산출물 COPY — bootJar이 build/libs/<module>-*.jar 생성. 없으면 fail-fast.
COPY --chown=magicjar:magicjar build/libs/*-SNAPSHOT.jar /app/app.jar

# JVM 옵션 — KST 타임존 의무 (KIS·DB·log 정합)
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul -XX:+UseG1GC -XX:MaxRAMPercentage=75"

EXPOSE 8080  # api는 8080, batch는 8081, consumer는 8082 (모듈별 변경)

# 헬스체크 (ADR 0008) — /actuator/health
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=20 \
    CMD wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q UP || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**모듈별 차이**: `EXPOSE`·`HEALTHCHECK` 포트만 다름 (api=8080, batch=8081, consumer=8082).
**JAR glob**: `build/libs/*-SNAPSHOT.jar` — 멀티 모듈에서 모듈명 prefix가 들어가므로 안전. `plain.jar` 미제외 가드는 backend-core가 build.gradle.kts에 `tasks.withType<Jar> { archiveClassifier.set("") }` + `tasks.named("bootJar") { archiveClassifier.set("") }` 또는 `*-SNAPSHOT.jar` glob로 처리.

> backend-core 결정 항목: `build/libs/` 산출물 이름 정책 — `<module>-0.0.1-SNAPSHOT.jar` 단일 vs `*-plain.jar` 별도. **architect 권장 = `<module>-0.0.1-SNAPSHOT.jar` 단일 + `bootJar { archiveClassifier.set("") }` 명시**.

### 7-2. frontend Dockerfile (frontend/Dockerfile)

```dockerfile
# syntax=docker/dockerfile:1.7
# 멀티 스테이지 — node:20 build → nginx:alpine serve (ADR 0009 정합)

FROM node:20-alpine AS build
WORKDIR /build
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD wget -qO- http://localhost/ 2>/dev/null | grep -q "<html" || exit 1
```

`nginx.conf` 핵심:
```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;
    location / {
        try_files $uri $uri/ /index.html;  # SPA history mode fallback
    }
}
```

---

## 8. settings.gradle.kts · docker-compose 정합 (architect 검증 항목)

### 8-1. settings.gradle.kts 검증

현행 `settings.gradle.kts`는 이미 4 모듈 include 정합:
```kotlin
include("domain", "api", "batch", "consumer")
```

frontend는 Gradle 외부 — 변경 없음. **architect 검증: 추가 변경 0건**.

### 8-2. docker/docker-compose.yml 정합

**현행 docker-compose.yml은 이미 7 컨테이너(인프라 3 + dozzle + kafka-ui + 앱 4)를 정의**. PR-5에서 추가 변경 권장 0건.

backend-core 검증 의무:
- `api-app` build context `../api` + Dockerfile 존재 → `scripts/build-and-deploy.sh` 통과
- `batch-app` build context `../batch` + Dockerfile 존재
- `consumer-app` build context `../consumer` + Dockerfile 존재

frontend 검증 의무:
- `frontend` build context `../frontend` + Dockerfile 존재
- pnpm-lock.yaml 존재 (frozen-lockfile)

### 8-3. scripts/build-and-deploy.sh 동작 확인

- `--gradle-only` → `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar` 통과
- `--no-gradle` → docker build 4 서비스 통과
- 전체 → 위 두 단계 + `up -d` → 헬스체크 healthy

---

## 9. 검증 게이트 (PR-5a + PR-5b 머지 전 통합)

### 9-1. backend-core (PR-5a) 머지 게이트

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | Gradle 빌드 | `./gradlew build` | BUILD SUCCESSFUL — 4 모듈 모두 |
| 2 | bootJar 3 모듈 (domain 제외) | `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar` | 3 jar 생성 (`<module>-0.0.1-SNAPSHOT.jar`) |
| 3 | domain bootJar 비활성 검증 | `./gradlew :domain:bootJar 2>&1 | grep -q "Task .* not enabled"` | 명시적 비활성 메시지 |
| 4 | ktlint | `./gradlew ktlintCheck` | violation 0 |
| 5 | scripts/build-and-deploy.sh --gradle-only | shell | 종료 코드 0 |
| 6 | 인프라 컨테이너 | `docker compose -f docker/docker-compose.infra.yml up -d` | postgres·kafka·redis 모두 healthy (60초 내) |
| 7 | Flyway V1 적용 | `docker compose -f docker/docker-compose.yml up -d api-app` 후 `psql -c "\dt"` | 11 테이블 모두 존재 |
| 8 | system_config singleton | `psql -c "SELECT id FROM system_config"` | 1 row, id=1 |
| 9 | domain Spring 의존 0 | `grep -r "import org.springframework" domain/src/main` | 0건 |
| 10 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {domain,api,batch,consumer}/src/main` | 0건 (W4 한정) |

### 9-2. frontend (PR-5b) 머지 게이트

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | pnpm 설치 | `cd frontend && pnpm install --frozen-lockfile` | 종료 코드 0 |
| 2 | TS 타입체크 | `cd frontend && pnpm tsc --noEmit` | 0 error |
| 3 | Vite 빌드 | `cd frontend && pnpm build` | dist/ 생성 + 0 error |
| 4 | Lint (선택) | `cd frontend && pnpm lint` | 0 error (eslint config 추가 시) |
| 5 | Docker 빌드 | `docker compose -f docker/docker-compose.yml build frontend` | 종료 코드 0 |
| 6 | frontend 컨테이너 healthy | `docker compose up -d frontend` 후 `curl http://localhost:3000` | 200 + `<html>` 응답 |
| 7 | LWC v4 import 검증 | `grep "lightweight-charts" frontend/package.json` | `"lightweight-charts": "^4..."` |

### 9-3. 통합 (PR-5a + PR-5b 모두 머지 후) 게이트

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | 전체 통합 기동 | `scripts/build-and-deploy.sh` | 7 컨테이너 모두 healthy |
| 2 | api healthy | `curl http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| 3 | batch up | `curl http://localhost:8081/actuator/health` | `{"status":"UP"}` |
| 4 | consumer up | `curl http://localhost:8082/actuator/health` | `{"status":"UP"}` |
| 5 | frontend reachable | `curl http://localhost:3000` | 200 + SPA index.html |
| 6 | 11 테이블 존재 | `psql magicjar -c "\dt" \| wc -l` | ≥ 11 (헤더 제외) |
| 7 | Kafka topic auto-create OK | `docker exec magicjar-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list` | 명령 성공 (topic 0건이어도 OK) |

---

## 10. 트레이드오프·결정 요약

| # | 결정 항목 | 채택 | 폐기/후속 | 사유 |
|---|---|---|---|---|
| 1 | 스키마 분리 (trading/ops) — V1 vs V2 | **V2 (PR-7+)** | V1 포함 | PR-5는 부트스트랩 — 비즈니스 row 0건 → 스키마 분리 가치 0. ALTER TABLE 비용 < 무관 단순화 가치 |
| 2 | Flyway location — 단일 vs 분리 (`db/migration/{trading,ops}`) | **단일 `db/migration/`** | 분리 | ADR 0006 §마이그레이션은 분리 명시했으나 — V1 단일 파일이라 가치 0. PR-7+ 본격화 시 전환 |
| 3 | Dockerfile 멀티 vs 단일 스테이지 | **단일 (host gradle 빌드 정합)** | 멀티 (gradle 컨테이너 내부 빌드) | CLAUDE.md 운영 함정 §1 정합 — `scripts/build-and-deploy.sh`가 정석. 컨테이너 내부 gradle은 빌드 시간·메모리·로컬 캐시 미공유 |
| 4 | Application stub `@SpringBootApplication` scanBasePackages | **`kr.spdaewoon.magicjar` 루트** | 모듈별 분리 | `:api`가 `:domain` 패키지 스캔 의무 (port 인터페이스 + adapter 구현 매핑) |
| 5 | bootJar 산출물 이름 | **`<module>-0.0.1-SNAPSHOT.jar`** | `<module>-plain.jar` 분리 | Dockerfile glob 단순화. plain.jar 미생성 명시 (`bootJar { archiveClassifier.set("") }`) |
| 6 | frontend 빌드 도구 | **pnpm** | npm/yarn | CLAUDE.md 명시 (`pnpm test/build`) |
| 7 | React 19 | **채택** | React 18 | ADR 0009 정합 + 최신 (사용자 결정 반영). 단 LWC v4·Tailwind 호환성 backend-core 검증 의무 |
| 8 | tailwind v3 vs v4 | **v3 (안정)** | v4 | v4는 alpha 안정성 미확보 — frontend 결정 권한 위임 (PR-5b 시점 최신 stable) |
| 9 | Swagger (springdoc) | **api 모듈 의존 포함** | PR-6+로 미룸 | 사용자 메모리 — Swagger는 1차 API 문서. PR-5에 의존성만 추가 (실 endpoint는 PR-6+) |

---

## 11. PR-5 외 명시 비범위 (다음 PR로)

| 항목 | 다음 PR | 트랙 |
|---|---|---|
| OnboardingController 5 endpoint 실 구현 | PR-6 | T-ONBOARD |
| AesGcmCipher 서비스 + master key 검증 | PR-6 | T-ONBOARD |
| KisAuthAdapter 토큰 발급 호출 | PR-6 | T-ONBOARD |
| frontend 셋업 마법사 7-step 화면 | PR-6 | T-ONBOARD |
| 셋업 미완 인증 차단 미들웨어 | PR-6 | T-ONBOARD |
| Watchlist CRUD REST + 화면 | PR-7 | T-W1 |
| KIS WS 구독·tick 수집 | PR-8 | T-W2 |
| BalanceReconcileJob (5분 cron) | PR-9 | T-W3 |
| Position Bootstrap ApplicationRunner | PR-9 | T-W3 |
| 시그널·주문·Risk Guard | PR-10+ | T-S1 등 |
| AI provider 통합 | W4 | T-AI |

---

## 12. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-5 W1 모듈 부트스트랩 설계 가이드 v1 발행 (architect, opus 4.7 1M). master-architecture v2 + ADR 0001~0047 정합. backend-core 위임용 + frontend 위임용 분배 명세는 `pr-5-dispatch-spec.md` 참조. |
