# magicJar MSA 재구성 — Implementation Plan (Phase 1 상세 / Phase 2~4 개요)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 7 모듈(app/core/market-data/strategy/ai/order-exec/web)을 5 모듈(domain/api/batch/consumer/frontend) + pyworker(Python) + Docker 구조로 빅뱅 재배치. 매 Phase 단위로 빌드·테스트 통과를 게이트로 둔다.

**Architecture:** 도메인 라이브러리(`domain`, java-library) + 3 Spring Boot 앱(`api`, `batch`, `consumer`) + Python 서비스(`pyworker`) + React SPA(`frontend`). Kafka 이벤트 백본 + PostgreSQL 단일 인스턴스(스키마 분리) + MinIO 객체 저장소. 운영은 MSA(서비스별 컨테이너), 로컬은 docker-compose 통합.

**Tech Stack:** Kotlin 2.0.21 (K2), Spring Boot 3.3.5, Spring AI 1.0.0-M3, Spring Kafka, Spring Batch, JDK 21, Gradle 8.x. Python 3.12 + FastAPI + aiokafka + Playwright. React 19 + TypeScript + Vite + TanStack Query + Zustand + shadcn/ui + TradingView Lightweight Charts v4 (Apache 2.0, 공식 API만). Docker (multi-stage, eclipse-temurin:21-jre-alpine / python:3.12-slim / nginx:alpine). PostgreSQL 16, Kafka 3.7 (KRaft), Redis 7, MinIO.

**Source of truth:** `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` (커밋 fdbc155). ADR 0019~0026 발행됨 (`bc7d24c`).

**Phase 분할 (4 Phase, 각자 working software 단위):**

| Phase | 목표 | 브랜치 | 담당 에이전트 | 머지 게이트 |
|-------|------|-------|------------|-----------|
| **P1** | Foundation + domain 라이브러리 (이 파일에서 상세) | `agent/architect/p1-foundation` | architect (opus) | `:domain:build` + `:domain:test` 통과, 기존 7 모듈 git rm 완료 |
| **P2** | 3 JVM 앱 스켈레톤 + Kafka 와이어링 | `agent/backend-core/p2-jvm-apps` | backend-core (sonnet) | `bootRun` 3개 모두 통과, 임베디드 Kafka 스모크 |
| **P3** | pyworker + frontend 스켈레톤 | `agent/ai-engineer/p3-pyworker` + `agent/frontend/p3-frontend` (병렬 가능) | ai-engineer + frontend | pytest + vitest 통과, FastAPI/Vite dev 서버 기동 |
| **P4** | Docker compose + 통합 스모크 + main 머지 | `agent/architect/p4-docker-smoke` | architect | `docker compose up` + `scripts/smoke.sh` 5 시나리오 통과, QA on-demand 1회 |

→ **이 plan 문서는 Phase 1만 상세**. Phase 2~4는 P1 종료 후 각각 별도 plan 파일로 작성한다 (`2026-04-XX-msa-restructure-phase2.md` 등).

---

## Phase 1 — Foundation + Domain 라이브러리

### File Structure (Phase 1 종료 시점)

```
magic_ch1/
├─ settings.gradle.kts              ← 5 신규 모듈 등록, 7 구 모듈 제거됨
├─ build.gradle.kts                 ← subprojects 블록 보강(java-library 분기)
├─ docker/                          ← 빈 디렉토리 + .gitkeep (P4에서 채움)
├─ docs/superpowers/{specs,plans}/  ← 이미 존재
│
├─ domain/                          ← 신규 (Kotlin java-library, Spring 미부착)
│  ├─ build.gradle.kts
│  └─ src/
│     ├─ main/kotlin/com/magicjar/domain/
│     │  ├─ common/                 ← 기존 core/domain + core/event 이전
│     │  ├─ market/                 ← market-data/* 비-WS 부분 이전
│     │  ├─ strategy/               ← strategy/* 이전
│     │  ├─ ai/                     ← ai/* 이전
│     │  ├─ order/                  ← order-exec/* 이전
│     │  └─ journal/                ← 기존 TradeJournal* 이전
│     └─ test/kotlin/...            ← 기존 테스트 이전 + 신규 패키지 가드 테스트
│
├─ api/                             ← 신규, 빈 build.gradle.kts (P2에서 채움)
│  └─ build.gradle.kts              ← 최소 placeholder (kotlin("jvm") 만)
├─ batch/                           ← 동일
├─ consumer/                        ← 동일
├─ frontend/                        ← 신규, .gitkeep만 (P3에서 채움)
└─ pyworker/                        ← 신규, .gitkeep만 (P3에서 채움)

폐기됨 (git rm):
├─ app/         ← 삭제
├─ core/        ← 삭제 (코드는 domain으로 이전됨)
├─ market-data/ ← 삭제
├─ strategy/    ← 삭제
├─ ai/          ← 삭제
├─ order-exec/  ← 삭제
└─ web/         ← 삭제 (frontend가 대체, 공허하게 둠)
```

### 패키지 매핑 규약 (P1 일괄 적용)

| 기존 패키지 | 신규 패키지 |
|------------|-----------|
| `kr.spdaewoon.magicjar.core.domain.*` | `com.magicjar.domain.common.*` |
| `kr.spdaewoon.magicjar.core.port.*` | `com.magicjar.domain.<영역>.port.*` (CandleRepository→market, BrokerGateway→order, AiClient→ai, TradeJournalRepository→journal, 등) |
| `kr.spdaewoon.magicjar.core.event.*` | `com.magicjar.domain.common.event.*` |
| `kr.spdaewoon.magicjar.marketdata.*` | `com.magicjar.domain.market.*` |
| `kr.spdaewoon.magicjar.strategy.*` | `com.magicjar.domain.strategy.*` |
| `kr.spdaewoon.magicjar.ai.*` | `com.magicjar.domain.ai.*` |
| `kr.spdaewoon.magicjar.orderexec.*` | `com.magicjar.domain.order.*` |

→ 패키지 prefix 일괄 변경(`kr.spdaewoon.magicjar` → `com.magicjar.domain`). 그룹 ID는 `build.gradle.kts`의 `group` 변수로 유지 (`kr.spdaewoon.magicjar`).

### Pre-flight 체크

- [ ] **Step 0-1: 현재 main 빌드 통과 확인**

```bash
cd /Users/ak-song/magic_ch1
./gradlew clean build -x test
```

Expected: `BUILD SUCCESSFUL`. 실패 시 P1 시작 금지 — 이미 깨진 채라면 별도 hotfix 먼저.

- [ ] **Step 0-2: 작업 브랜치 생성**

```bash
git checkout -b agent/architect/p1-foundation
```

Expected: `Switched to a new branch 'agent/architect/p1-foundation'`.

- [ ] **Step 0-3: 작업 시작 기록 (선택)**

`_workspace/_harness-state.json`은 P4 완료 시점에 일괄 갱신 (개별 Phase마다는 안 함). 스킵.

---

### Task 1.1: 신규 모듈 디렉토리 + placeholder build.gradle.kts 생성

**Files:**
- Create: `domain/build.gradle.kts`
- Create: `api/build.gradle.kts`
- Create: `batch/build.gradle.kts`
- Create: `consumer/build.gradle.kts`
- Create: `frontend/.gitkeep`
- Create: `pyworker/.gitkeep`
- Create: `docker/.gitkeep`

- [ ] **Step 1.1-1: `domain/build.gradle.kts` 작성**

```kotlin
plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("org.slf4j:slf4j-api")

    // strategy/ai 영역은 Spring AI core 를 사용 (starter 아님)
    api("org.springframework.ai:spring-ai-core")
    api("org.springframework.ai:spring-ai-openai")

    // JPA 어노테이션은 영역별 persistence 패키지에서 사용. starter는 각 앱.
    api("jakarta.persistence:jakarta.persistence-api:3.1.0")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
}
```

- [ ] **Step 1.1-2: `api`/`batch`/`consumer/build.gradle.kts` 최소 placeholder**

각 파일 내용 (동일):

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
}
```

→ Spring Boot starter는 P2에서 추가. P1은 빌드만 성공시키는 placeholder.

- [ ] **Step 1.1-3: 빈 디렉토리 git 추적용 `.gitkeep` 생성**

```bash
mkdir -p frontend pyworker docker
touch frontend/.gitkeep pyworker/.gitkeep docker/.gitkeep
```

- [ ] **Step 1.1-4: 신규 src 디렉토리 골격 생성**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/{common,market,strategy,ai,order,journal}
mkdir -p domain/src/test/kotlin/com/magicjar/domain
mkdir -p api/src/main/kotlin/com/magicjar/api
mkdir -p batch/src/main/kotlin/com/magicjar/batch
mkdir -p consumer/src/main/kotlin/com/magicjar/consumer
# 빈 디렉토리 보존용
find domain api batch consumer -type d -empty -exec touch {}/.gitkeep \;
```

- [ ] **Step 1.1-5: 커밋**

```bash
git add domain api batch consumer frontend pyworker docker
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "feat(p1): 신규 5 모듈 + frontend/pyworker/docker 디렉토리 스켈레톤"
```

Expected: pre-commit hook(`.husky/pre-commit`) 통과 (author=`bot+architect@magicjar.local`은 ADR 0017 화이트리스트).

---

### Task 1.2: settings.gradle.kts에 신규 모듈 등록 (구 모듈도 일단 유지)

**Files:**
- Modify: `settings.gradle.kts`

- [ ] **Step 1.2-1: `settings.gradle.kts` 갱신**

기존:
```kotlin
include(
    "app",
    "core",
    "market-data",
    "strategy",
    "ai",
    "order-exec",
    "web",
)
```

→

```kotlin
include(
    // === 신규 (P1~) ===
    "domain",
    "api",
    "batch",
    "consumer",

    // === 구 (P1 Task 1.10에서 일괄 제거 예정) ===
    "app",
    "core",
    "market-data",
    "strategy",
    "ai",
    "order-exec",
    "web",
)
```

- [ ] **Step 1.2-2: 루트 `build.gradle.kts` subprojects 분기 추가 (Spring Boot/JPA 플러그인을 domain·신규 모듈에 적용 안 함)**

기존 `subprojects { apply(plugin = "...plugin.spring") apply(plugin = "...plugin.jpa") }`는 신규 placeholder 모듈(api/batch/consumer)에는 적용해도 무해하지만 **domain은 java-library**라 spring-boot dependency-management도 일단은 전체 apply 유지 가능. 단 `domain`은 BOM 임포트만 받고 실제 starter 의존은 안 함 → 그대로 OK.

→ 본 Task는 `build.gradle.kts` **수정 없음** 확인. (단순 검토 step)

- [ ] **Step 1.2-3: 빌드 통과 확인**

```bash
./gradlew clean build -x test
```

Expected: `BUILD SUCCESSFUL`. 11개 모듈(7 구 + 4 신규) 모두 빌드.

실패 시 점검:
- `domain/build.gradle.kts`의 `spring-ai-core` 좌표가 `spring-ai-bom`에서 해석되는지
- `jakarta.persistence-api`가 BOM에서 충돌 안 나는지

- [ ] **Step 1.2-4: 커밋**

```bash
git add settings.gradle.kts
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "feat(p1): settings.gradle.kts에 신규 4 모듈 등록 (구 7 모듈 일시 병존)"
```

---

### Task 1.3: 도메인 검증용 가드 테스트 1개 추가 (TDD red-green)

**Files:**
- Create: `domain/src/test/kotlin/com/magicjar/domain/PackageGuardTest.kt`

**의도:** 패키지 prefix 규칙(`com.magicjar.domain`)과 6개 서브 패키지가 존재하는지 가드. Task 1.4~1.9의 코드 이전이 진행되며 이 테스트가 점진 통과한다.

- [ ] **Step 1.3-1: 실패 테스트 작성**

```kotlin
package com.magicjar.domain

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class PackageGuardTest {

    @Test
    fun `domain 모듈은 6 서브 패키지 디렉토리를 가진다`() {
        val baseDir = File("src/main/kotlin/com/magicjar/domain")
        baseDir.exists() shouldBe true
        val subDirs = baseDir.listFiles { f -> f.isDirectory }
            .orEmpty()
            .map { it.name }
        subDirs shouldContainAll listOf("common", "market", "strategy", "ai", "order", "journal")
    }

    @Test
    fun `domain 모듈에 최소 1개의 Kotlin 소스가 common 패키지에 존재한다`() {
        val commonDir = File("src/main/kotlin/com/magicjar/domain/common")
        val ktFiles = commonDir.walk().filter { it.extension == "kt" }.toList()
        (ktFiles.size > 0) shouldBe true
    }
}
```

- [ ] **Step 1.3-2: 테스트 실행 — 두 번째 테스트만 실패해야 함**

```bash
./gradlew :domain:test --tests "com.magicjar.domain.PackageGuardTest"
```

Expected: 첫 번째(서브 디렉토리 존재) PASS, 두 번째(common에 .kt 파일 존재) FAIL.

→ 다음 Task에서 common 패키지를 채우면서 두 번째 테스트가 자동 PASS 됨.

- [ ] **Step 1.3-3: 커밋**

```bash
git add domain/src/test/kotlin/com/magicjar/domain/PackageGuardTest.kt
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "test(p1): domain 패키지 가드 테스트 (common 비어있음을 일부러 fail)"
```

---

### Task 1.4: `core/` → `domain/common` 이전

**Files:**
- Move: `core/src/main/kotlin/kr/spdaewoon/magicjar/core/domain/*.kt` → `domain/src/main/kotlin/com/magicjar/domain/common/`
- Move: `core/src/main/kotlin/kr/spdaewoon/magicjar/core/event/*.kt` → `domain/src/main/kotlin/com/magicjar/domain/common/event/`
- Move: `core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/*.kt` → 영역별 분배 (Task 1.5~1.9)
- Move: `core/src/test/**` → `domain/src/test/kotlin/com/magicjar/domain/common/`

- [ ] **Step 1.4-1: 도메인 데이터 클래스 이전 (port 제외)**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/common/event
git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/domain/*.kt \
  domain/src/main/kotlin/com/magicjar/domain/common/
git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/event/*.kt \
  domain/src/main/kotlin/com/magicjar/domain/common/event/
```

- [ ] **Step 1.4-2: 패키지 선언 일괄 변경 (Mac BSD sed)**

```bash
find domain/src/main/kotlin/com/magicjar/domain/common -name "*.kt" -exec \
  sed -i '' \
    -e 's|^package kr.spdaewoon.magicjar.core.domain.*|package com.magicjar.domain.common|' \
    -e 's|^package kr.spdaewoon.magicjar.core.event.*|package com.magicjar.domain.common.event|' \
    {} \;
```

- [ ] **Step 1.4-3: import 보정 — 같은 모듈 내 참조**

도메인 데이터 클래스끼리 서로 import 했던 부분(`kr.spdaewoon.magicjar.core.domain.X`)을 `com.magicjar.domain.common.X`로 일괄 치환.

```bash
find domain/src/main/kotlin/com/magicjar/domain -name "*.kt" -exec \
  sed -i '' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.domain|com.magicjar.domain.common|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.event|com.magicjar.domain.common.event|g' \
    {} \;
```

- [ ] **Step 1.4-4: 테스트 이전**

```bash
mkdir -p domain/src/test/kotlin/com/magicjar/domain/common
# core 테스트 디렉토리가 비어있으면 skip; 존재하면 이전
if [ -d core/src/test/kotlin ]; then
  find core/src/test/kotlin -name "*.kt" -exec git mv {} domain/src/test/kotlin/com/magicjar/domain/common/ \;
fi
find domain/src/test/kotlin/com/magicjar/domain/common -name "*.kt" -exec \
  sed -i '' \
    -e 's|^package kr\.spdaewoon\.magicjar\.core.*|package com.magicjar.domain.common|' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.domain|com.magicjar.domain.common|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.event|com.magicjar.domain.common.event|g' \
    {} \;
```

- [ ] **Step 1.4-5: `:domain:compileKotlin` 통과 확인 (port 미이전 상태라 일부 에러 OK — 본 Task는 common만 검증)**

```bash
./gradlew :domain:compileKotlin
```

Expected: common 패키지의 데이터 클래스만 컴파일됨. port 참조하는 클래스가 있으면 실패하는데, common은 보통 port를 참조 안 함 → 통과.

만약 실패하면 어떤 클래스가 port 의존하는지 확인 후 1.5~1.9에서 port 이전 후 재시도.

- [ ] **Step 1.4-6: PackageGuardTest 두 번째 테스트 통과 확인**

```bash
./gradlew :domain:test --tests "com.magicjar.domain.PackageGuardTest"
```

Expected: 두 테스트 모두 PASS.

- [ ] **Step 1.4-7: 커밋**

```bash
git add -A domain/ core/
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "refactor(p1): core/{domain,event} → domain/common 이전 + 패키지 prefix 변경"
```

---

### Task 1.5: `core/port/` 영역별 재배치 + `market-data/` REST·persistence 부분 → `domain/market`

**Files (port 분배):**
- `CandleRepository.kt`, `InstrumentCatalog.kt`, `Instrument.kt`(이미 common이면 둠) → `domain/market/port/`
- `BrokerGateway.kt`, `OrderSender.kt` → `domain/order/port/`
- `AiClient.kt` → `domain/ai/port/`
- `TradeJournalRepository.kt` → `domain/journal/port/`
- `NewsRepository.kt` → `domain/journal/port/` (또는 `domain/market/port/` — spec §6에서 뉴스는 pyworker로 이관이지만 `NewsItem` 도메인 모델 자체는 common 유지, repository port는 일단 `journal/port`로 임시 — P3에서 재검토)
- `StrategyEvaluator.kt` → `domain/strategy/port/`
- `HarnessStateReader.kt` → `domain/common/port/` (운영성 포트)

**Files (market-data 본체):**
- `market-data/src/main/kotlin/.../marketdata/kis/**` → `domain/market/kis/` (KIS REST 클라이언트)
- `market-data/src/main/kotlin/.../marketdata/persistence/**` → `domain/market/persistence/`
- `market-data/src/main/kotlin/.../marketdata/MarketDataConfig.kt` → **삭제** (P2에서 api-app config로 재구성)
- `market-data/src/main/kotlin/.../marketdata/news/**` → **삭제** (pyworker로 이관, P3)
- `market-data/src/test/kotlin/.../kis/**` → `domain/src/test/kotlin/com/magicjar/domain/market/kis/`
- `market-data/src/test/kotlin/.../persistence/**` → `domain/src/test/kotlin/com/magicjar/domain/market/persistence/`
- `market-data/src/test/kotlin/.../news/**` → **삭제**

- [ ] **Step 1.5-1: port 영역별 이전**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/{market,order,ai,strategy,journal,common}/port

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/CandleRepository.kt \
       domain/src/main/kotlin/com/magicjar/domain/market/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/InstrumentCatalog.kt \
       domain/src/main/kotlin/com/magicjar/domain/market/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/BrokerGateway.kt \
       domain/src/main/kotlin/com/magicjar/domain/order/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/OrderSender.kt \
       domain/src/main/kotlin/com/magicjar/domain/order/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/AiClient.kt \
       domain/src/main/kotlin/com/magicjar/domain/ai/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/TradeJournalRepository.kt \
       domain/src/main/kotlin/com/magicjar/domain/journal/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/NewsRepository.kt \
       domain/src/main/kotlin/com/magicjar/domain/journal/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/StrategyEvaluator.kt \
       domain/src/main/kotlin/com/magicjar/domain/strategy/port/

git mv core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/HarnessStateReader.kt \
       domain/src/main/kotlin/com/magicjar/domain/common/port/
```

- [ ] **Step 1.5-2: 패키지 선언 + import 일괄 치환**

```bash
# 각 영역의 port 파일에 대해 신규 패키지 적용
for area in market order ai journal strategy common; do
  find domain/src/main/kotlin/com/magicjar/domain/$area/port -name "*.kt" -exec \
    sed -i '' \
      -e "s|^package kr\.spdaewoon\.magicjar\.core\.port.*|package com.magicjar.domain.$area.port|" \
      {} \;
done

# 모든 도메인 파일의 import 보정
find domain/src -name "*.kt" -exec \
  sed -i '' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.CandleRepository|com.magicjar.domain.market.port.CandleRepository|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.InstrumentCatalog|com.magicjar.domain.market.port.InstrumentCatalog|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.BrokerGateway|com.magicjar.domain.order.port.BrokerGateway|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.OrderSender|com.magicjar.domain.order.port.OrderSender|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.AiClient|com.magicjar.domain.ai.port.AiClient|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.TradeJournalRepository|com.magicjar.domain.journal.port.TradeJournalRepository|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.NewsRepository|com.magicjar.domain.journal.port.NewsRepository|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.StrategyEvaluator|com.magicjar.domain.strategy.port.StrategyEvaluator|g' \
    -e 's|kr\.spdaewoon\.magicjar\.core\.port\.HarnessStateReader|com.magicjar.domain.common.port.HarnessStateReader|g' \
    {} \;
```

- [ ] **Step 1.5-3: market-data 본체 이전 (KIS REST + persistence)**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/market/{kis,persistence}
mkdir -p domain/src/test/kotlin/com/magicjar/domain/market/{kis,persistence}

# KIS REST
if [ -d market-data/src/main/kotlin/kr/spdaewoon/magicjar/marketdata/kis ]; then
  git mv market-data/src/main/kotlin/kr/spdaewoon/magicjar/marketdata/kis/* \
         domain/src/main/kotlin/com/magicjar/domain/market/kis/
fi

# persistence
if [ -d market-data/src/main/kotlin/kr/spdaewoon/magicjar/marketdata/persistence ]; then
  git mv market-data/src/main/kotlin/kr/spdaewoon/magicjar/marketdata/persistence/* \
         domain/src/main/kotlin/com/magicjar/domain/market/persistence/
fi

# news → 삭제 (pyworker로 P3에서 재구현)
git rm -rf market-data/src/main/kotlin/kr/spdaewoon/magicjar/marketdata/news 2>/dev/null || true
git rm -rf market-data/src/test/kotlin/kr/spdaewoon/magicjar/marketdata/news 2>/dev/null || true

# MarketDataConfig.kt → 삭제 (P2 api-app에서 재구성)
git rm -f market-data/src/main/kotlin/kr/spdaewoon/magicjar/marketdata/MarketDataConfig.kt 2>/dev/null || true

# 테스트 이전 (kis + persistence만)
if [ -d market-data/src/test/kotlin/kr/spdaewoon/magicjar/marketdata/kis ]; then
  git mv market-data/src/test/kotlin/kr/spdaewoon/magicjar/marketdata/kis/* \
         domain/src/test/kotlin/com/magicjar/domain/market/kis/
fi

if [ -d market-data/src/test/kotlin/kr/spdaewoon/magicjar/marketdata/persistence ]; then
  git mv market-data/src/test/kotlin/kr/spdaewoon/magicjar/marketdata/persistence/* \
         domain/src/test/kotlin/com/magicjar/domain/market/persistence/
fi
```

- [ ] **Step 1.5-4: 패키지 선언 + import 치환 (market 영역)**

```bash
find domain/src -name "*.kt" -path "*market*" -exec \
  sed -i '' \
    -e 's|^package kr\.spdaewoon\.magicjar\.marketdata\.kis.*|package com.magicjar.domain.market.kis|' \
    -e 's|^package kr\.spdaewoon\.magicjar\.marketdata\.persistence.*|package com.magicjar.domain.market.persistence|' \
    {} \;

find domain/src -name "*.kt" -exec \
  sed -i '' \
    -e 's|kr\.spdaewoon\.magicjar\.marketdata\.kis|com.magicjar.domain.market.kis|g' \
    -e 's|kr\.spdaewoon\.magicjar\.marketdata\.persistence|com.magicjar.domain.market.persistence|g' \
    {} \;
```

- [ ] **Step 1.5-5: `:domain:compileKotlin` 통과 확인**

```bash
./gradlew :domain:compileKotlin
```

Expected: BUILD SUCCESSFUL. 이 시점에서 common + market(port + kis + persistence) 모두 컴파일.

⚠️ JPA 어노테이션 사용하는 persistence 클래스가 있다면 `jakarta.persistence-api`만으로 부족할 수 있음 (Spring Data JPA의 `@Repository` 등). 그런 클래스는 P2에서 api-app/batch-app으로 이관 — 본 Task에서는 컴파일 에러 시 해당 클래스 임시 주석 처리(`// TODO P2: move to app infrastructure`)하고 다음 진행.

- [ ] **Step 1.5-6: 커밋**

```bash
git add -A
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "refactor(p1): port 영역별 재배치 + market-data(kis,persistence) → domain/market 이전, news/MarketDataConfig 삭제"
```

---

### Task 1.6: `strategy/` → `domain/strategy` 이전

**Files:**
- Move: `strategy/src/main/kotlin/.../strategy/**` → `domain/src/main/kotlin/com/magicjar/domain/strategy/`
- Move: `strategy/src/test/kotlin/.../strategy/**` → `domain/src/test/kotlin/com/magicjar/domain/strategy/`

- [ ] **Step 1.6-1: 코드·테스트 일괄 이전**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/strategy
mkdir -p domain/src/test/kotlin/com/magicjar/domain/strategy

if [ -d strategy/src/main/kotlin ]; then
  rsync -a --remove-source-files strategy/src/main/kotlin/kr/spdaewoon/magicjar/strategy/ \
        domain/src/main/kotlin/com/magicjar/domain/strategy/
fi
if [ -d strategy/src/test/kotlin ]; then
  rsync -a --remove-source-files strategy/src/test/kotlin/kr/spdaewoon/magicjar/strategy/ \
        domain/src/test/kotlin/com/magicjar/domain/strategy/
fi
git add -A
```

(`git mv` 대신 `rsync --remove-source-files` 사용 이유: 깊은 디렉토리 트리 일괄 이동 시 git mv가 한 파일씩 호출 비용 큼. rsync 후 git add -A로 동등한 rename 인식.)

- [ ] **Step 1.6-2: 패키지 선언 + import 치환**

```bash
find domain/src -name "*.kt" -path "*strategy*" -exec \
  sed -i '' \
    -e 's|^package kr\.spdaewoon\.magicjar\.strategy|package com.magicjar.domain.strategy|' \
    {} \;

find domain/src -name "*.kt" -exec \
  sed -i '' \
    -e 's|kr\.spdaewoon\.magicjar\.strategy|com.magicjar.domain.strategy|g' \
    {} \;
```

- [ ] **Step 1.6-3: `:domain:compileKotlin` 통과 확인**

```bash
./gradlew :domain:compileKotlin
```

Expected: BUILD SUCCESSFUL. 컴파일 에러 시 다음 우선순위로 처리:
1. JPA/Spring 어노테이션 사용 클래스 → 임시 주석 + `// TODO P2`
2. core 모듈 의존 → 이미 common으로 옮겨졌으므로 import 보정만 필요. 1.6-2 sed가 다 잡음.
3. 아직 미이전 영역(ai, order, journal) 의존 → 다음 Task에서 자동 해소.

- [ ] **Step 1.6-4: 커밋**

```bash
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "refactor(p1): strategy/ → domain/strategy 이전"
```

---

### Task 1.7: `ai/` → `domain/ai` 이전

**Files:** `ai/src/{main,test}/kotlin/...` → `domain/src/{main,test}/kotlin/com/magicjar/domain/ai/`

- [ ] **Step 1.7-1: 이전**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/ai
mkdir -p domain/src/test/kotlin/com/magicjar/domain/ai

if [ -d ai/src/main/kotlin ]; then
  rsync -a --remove-source-files ai/src/main/kotlin/kr/spdaewoon/magicjar/ai/ \
        domain/src/main/kotlin/com/magicjar/domain/ai/
fi
if [ -d ai/src/test/kotlin ]; then
  rsync -a --remove-source-files ai/src/test/kotlin/kr/spdaewoon/magicjar/ai/ \
        domain/src/test/kotlin/com/magicjar/domain/ai/
fi
git add -A
```

- [ ] **Step 1.7-2: 패키지 + import 치환**

```bash
find domain/src -name "*.kt" -path "*com/magicjar/domain/ai*" -exec \
  sed -i '' \
    -e 's|^package kr\.spdaewoon\.magicjar\.ai|package com.magicjar.domain.ai|' \
    {} \;

find domain/src -name "*.kt" -exec \
  sed -i '' \
    -e 's|kr\.spdaewoon\.magicjar\.ai|com.magicjar.domain.ai|g' \
    {} \;
```

- [ ] **Step 1.7-3: `:domain:compileKotlin` 통과**

```bash
./gradlew :domain:compileKotlin
```

- [ ] **Step 1.7-4: 커밋**

```bash
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "refactor(p1): ai/ → domain/ai 이전"
```

---

### Task 1.8: `order-exec/` → `domain/order` 이전

**Files:** `order-exec/src/{main,test}/kotlin/...` → `domain/src/{main,test}/kotlin/com/magicjar/domain/order/`

- [ ] **Step 1.8-1~3: Task 1.7과 동일 패턴 (orderexec ↔ order)**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/order
mkdir -p domain/src/test/kotlin/com/magicjar/domain/order

if [ -d order-exec/src/main/kotlin ]; then
  rsync -a --remove-source-files order-exec/src/main/kotlin/kr/spdaewoon/magicjar/orderexec/ \
        domain/src/main/kotlin/com/magicjar/domain/order/
fi
if [ -d order-exec/src/test/kotlin ]; then
  rsync -a --remove-source-files order-exec/src/test/kotlin/kr/spdaewoon/magicjar/orderexec/ \
        domain/src/test/kotlin/com/magicjar/domain/order/
fi
git add -A

find domain/src -name "*.kt" -path "*com/magicjar/domain/order*" -exec \
  sed -i '' \
    -e 's|^package kr\.spdaewoon\.magicjar\.orderexec|package com.magicjar.domain.order|' \
    {} \;

find domain/src -name "*.kt" -exec \
  sed -i '' \
    -e 's|kr\.spdaewoon\.magicjar\.orderexec|com.magicjar.domain.order|g' \
    {} \;

./gradlew :domain:compileKotlin

git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "refactor(p1): order-exec/ → domain/order 이전"
```

---

### Task 1.9: `domain/journal` 채우기 (TradeJournal* 도메인 객체 정리)

대부분의 journal 객체는 이미 `core/domain/`에 있어서 1.4에서 common으로 이전됨. journal 도메인은 `TradeJournalEntry`, `TradeRef` 등 → common에서 journal로 이관.

- [ ] **Step 1.9-1: journal 관련 클래스를 common → journal로 이동**

```bash
mkdir -p domain/src/main/kotlin/com/magicjar/domain/journal

git mv domain/src/main/kotlin/com/magicjar/domain/common/TradeJournalEntry.kt \
       domain/src/main/kotlin/com/magicjar/domain/journal/ 2>/dev/null || true
git mv domain/src/main/kotlin/com/magicjar/domain/common/TradeRef.kt \
       domain/src/main/kotlin/com/magicjar/domain/journal/ 2>/dev/null || true
```

- [ ] **Step 1.9-2: 신규 ChartSnapshot 도메인 객체 추가 (spec §5에서 정의)**

`domain/src/main/kotlin/com/magicjar/domain/journal/ChartSnapshot.kt`:

```kotlin
package com.magicjar.domain.journal

import java.time.Instant
import java.util.UUID

enum class SnapshotSource { CLIENT_LWC, SERVER_BROWSER }

data class ChartSnapshot(
    val id: UUID,
    val tradeId: UUID,
    val symbol: String,
    val timeframe: String,
    val capturedAt: Instant,
    val source: SnapshotSource,
    val storageKey: String,
    val publicUrl: String,
    val meta: Map<String, String> = emptyMap(),
)
```

- [ ] **Step 1.9-3: ChartSnapshot 단위 테스트**

`domain/src/test/kotlin/com/magicjar/domain/journal/ChartSnapshotTest.kt`:

```kotlin
package com.magicjar.domain.journal

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ChartSnapshotTest {

    @Test
    fun `ChartSnapshot은 모든 필수 필드를 가진다`() {
        val s = ChartSnapshot(
            id = UUID.randomUUID(),
            tradeId = UUID.randomUUID(),
            symbol = "005930",
            timeframe = "1m",
            capturedAt = Instant.parse("2026-04-27T00:00:00Z"),
            source = SnapshotSource.CLIENT_LWC,
            storageKey = "snapshots/2026-04-27/abc.png",
            publicUrl = "https://minio.local/snapshots/2026-04-27/abc.png",
        )
        s.symbol shouldBe "005930"
        s.source shouldBe SnapshotSource.CLIENT_LWC
        s.meta.size shouldBe 0
    }
}
```

- [ ] **Step 1.9-4: package + import 치환 (journal로 옮긴 클래스들)**

```bash
find domain/src -name "*.kt" -path "*com/magicjar/domain/journal*" -exec \
  sed -i '' \
    -e 's|^package com\.magicjar\.domain\.common$|package com.magicjar.domain.journal|' \
    {} \;

find domain/src -name "*.kt" -exec \
  sed -i '' \
    -e 's|com\.magicjar\.domain\.common\.TradeJournalEntry|com.magicjar.domain.journal.TradeJournalEntry|g' \
    -e 's|com\.magicjar\.domain\.common\.TradeRef|com.magicjar.domain.journal.TradeRef|g' \
    {} \;
```

- [ ] **Step 1.9-5: `:domain:test` 통과 확인**

```bash
./gradlew :domain:test
```

Expected: BUILD SUCCESSFUL. ChartSnapshotTest + PackageGuardTest + 이전된 기존 테스트 모두 PASS.

실패 시:
- 미해결 import → grep으로 잔존 `kr.spdaewoon.magicjar.core` 찾기
- 임시 주석된 JPA 클래스가 테스트 깨면 해당 테스트도 임시 `@Disabled("P2: move to app")` 처리

- [ ] **Step 1.9-6: 커밋**

```bash
git add -A
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "refactor(p1): journal 영역 정리 + ChartSnapshot 도메인 객체 신규 추가"
```

---

### Task 1.10: 구 7 모듈 디렉토리 일괄 삭제 + settings.gradle 정리

**Files:**
- Delete: `app/`, `core/`, `market-data/`, `strategy/`, `ai/`, `order-exec/`, `web/`
- Modify: `settings.gradle.kts`

- [ ] **Step 1.10-1: 구 모듈 잔존 파일 확인 (이전 누락 없는지)**

```bash
for m in app core market-data strategy ai order-exec web; do
  echo "=== $m ==="
  find $m -name "*.kt" 2>/dev/null
done
```

Expected: 출력 없음. 출력 있다면 어느 영역으로 가야 할지 case-by-case 결정 후 이전.

- 특히 `app/` (Spring Boot 메인) 는 P2에서 api-app으로 재구성될 예정 — 본 Task에서는 전체 삭제. 단 `app/src/main/resources/application.yml`은 사전에 archive/_legacy_application.yml로 백업.

- [ ] **Step 1.10-2: application.yml 백업 (P2에서 분할 참고용)**

```bash
mkdir -p _workspace/_legacy
git mv app/src/main/resources/application.yml _workspace/_legacy/application.yml.bak 2>/dev/null || true
```

- [ ] **Step 1.10-3: 7 디렉토리 일괄 삭제**

```bash
git rm -rf app core market-data strategy ai order-exec web
```

- [ ] **Step 1.10-4: settings.gradle.kts 정리**

```kotlin
include(
    "domain",
    "api",
    "batch",
    "consumer",
)
```

→ 4 모듈만 남김. frontend/pyworker는 Gradle 모듈 아니므로 include X.

- [ ] **Step 1.10-5: 전체 빌드 통과 확인**

```bash
./gradlew clean build
```

Expected: 4 모듈(domain + 3 placeholder) 모두 BUILD SUCCESSFUL. domain은 테스트 통과, api/batch/consumer는 빈 placeholder라 즉시 통과.

실패 시:
- 잔존 `kr.spdaewoon.magicjar.*` 참조 → `grep -r "kr\.spdaewoon\.magicjar" domain/src` 확인
- BOM 충돌 → spring-ai-core 좌표 재확인

- [ ] **Step 1.10-6: 커밋**

```bash
git add -A
git -c user.name="architect" -c user.email="bot+architect@magicjar.local" \
  commit -m "refactor(p1): 구 7 모듈 일괄 삭제 + settings.gradle.kts 4 모듈로 정리

application.yml은 _workspace/_legacy/application.yml.bak로 백업 (P2 분할 시 참고)."
```

---

### Task 1.11: harness 메타 갱신

- [ ] **Step 1.11-1: `_workspace/_harness-state.json` 갱신 — Phase D 시작 + P1 task 등록**

기존 JSON에 `phaseHistory` 배열 끝에 추가:

```json
{
  "phase": "D",
  "startedAt": "2026-04-27T00:00:00+09:00",
  "mode": "subagent-driven-staged",
  "currentSubPhase": "P1",
  "subPhases": [
    {
      "id": "P1",
      "agent": "architect",
      "branch": "agent/architect/p1-foundation",
      "summary": "5 모듈 + frontend/pyworker/docker 디렉토리 신설, domain 라이브러리 채우기, 구 7 모듈 git rm",
      "plan": "docs/superpowers/plans/2026-04-27-msa-restructure.md"
    }
  ]
}
```

→ `currentPhase` 필드를 `"D"`로 갱신. `nextPhase` 필드 제거.

- [ ] **Step 1.11-2: README 변경 1줄 (모듈 목록)**

`README.md`의 모듈 섹션을 신규 5 모듈 + pyworker + frontend로 갱신. spec §1 그대로 옮김.

- [ ] **Step 1.11-3: CLAUDE.md 변경 이력 1줄 추가**

```
| 2026-04-27 | Phase D-P1 — MSA 재구성 1단계: 5 신규 모듈 + frontend/pyworker/docker 디렉토리 신설, 기존 7 모듈 git rm, domain 라이브러리에 비즈니스 로직 통합. ADR 0019~0026 발행, spec/plan 커밋. | settings.gradle.kts, domain/, api/, batch/, consumer/, frontend/, pyworker/, docker/, _workspace/, README.md, CLAUDE.md | spec docs/superpowers/specs/2026-04-27-msa-restructure-design.md 사용자 승인 |
```

- [ ] **Step 1.11-4: 커밋 (orchestrator author)**

```bash
git add _workspace/_harness-state.json README.md CLAUDE.md
git -c user.name="magicjar-orchestrator" -c user.email="bot+orchestrator@magicjar.local" \
  commit -m "chore(harness): Phase D-P1 진행 기록 + README 5 모듈 반영"
```

---

### Task 1.12: PR 발행 (`agent/architect/p1-foundation` → `main`)

- [ ] **Step 1.12-1: 푸시**

```bash
git push -u origin agent/architect/p1-foundation
```

- [ ] **Step 1.12-2: PR 생성**

```bash
gh pr create --title "Phase D-P1: MSA 재구성 — Foundation + domain 라이브러리" \
  --base main \
  --body "$(cat <<'EOF'
## Summary
- 신규 4 Gradle 모듈 + frontend/pyworker/docker 디렉토리 신설 (spec §1 §3)
- 비즈니스 로직 일괄 이전: core/market-data/strategy/ai/order-exec → `domain/<영역>`
- 패키지 prefix `kr.spdaewoon.magicjar.*` → `com.magicjar.domain.*` 일괄 변경
- 구 7 모듈 디렉토리 git rm, settings.gradle.kts 4 모듈로 정리
- ChartSnapshot 도메인 객체 신규 추가 (spec §5)
- application.yml `_workspace/_legacy/`로 백업 (P2 분할 참고)

## Source
- Spec: docs/superpowers/specs/2026-04-27-msa-restructure-design.md (커밋 fdbc155)
- Plan: docs/superpowers/plans/2026-04-27-msa-restructure.md
- ADRs: 0019~0026 (커밋 bc7d24c)

## Model
opus (architect)

## Task
P1 — Foundation + domain

## QA
On-Demand 정책(ADR 0016): P4 main 진입 직전 1회 풀 게이트. 본 PR은 셀프 리뷰 + 자동 테스트.

## Test Plan
- [ ] `./gradlew :domain:build` BUILD SUCCESSFUL
- [ ] `./gradlew :domain:test` 모든 테스트 PASS
- [ ] `./gradlew clean build` 4 모듈 모두 통과
- [ ] `git grep "kr.spdaewoon.magicjar"` 결과 없음
- [ ] PackageGuardTest + ChartSnapshotTest 통과

## Next
P2: 3 JVM 앱 스켈레톤 + Kafka 와이어링 (backend-core, sonnet)
EOF
)"
```

- [ ] **Step 1.12-3: 머지 (architect 자체 squash 또는 rebase 머지, ADR 0017에 따라 rebase 권장)**

CI 통과 + 셀프 리뷰 후:

```bash
gh pr merge --rebase --delete-branch
```

ADR 0015(1단 머지) + ADR 0017(rebase 머지로 author 보존). main에 직접 머지.

- [ ] **Step 1.12-4: main 동기화 + Phase 1 종료 보고**

```bash
git checkout main
git pull --ff-only
git log -5 --oneline
```

Phase 1 종료. 다음 Phase plan(`2026-04-XX-msa-restructure-phase2.md`) 작성으로 넘어감.

---

## Phase 2 — JVM 3 앱 스켈레톤 + Kafka (개요)

**브랜치:** `agent/backend-core/p2-jvm-apps`
**담당:** backend-core (sonnet)
**입력:** P1 머지된 main + spec §2 §4 §8

**범위 (별도 plan 파일에서 상세):**
- `api/` Spring Boot 앱: controller skeleton, `@Scheduled` 빈, STOMP WebSocket config, `application-api.yml`
- `batch/` Spring Boot 앱: Spring Batch config, 더미 BacktestRunJob 1개
- `consumer/` Spring Boot 앱: Spring Kafka config, 더미 `TickListener` + `TickPublisher` 1쌍
- 각 앱 `Dockerfile` (멀티 스테이지, eclipse-temurin:21-jre-alpine, USER 1000)
- `application.yml` 분할(api/batch/consumer 각자)
- 임베디드 Kafka 통합 테스트 (Testcontainers Kafka 1개)
- `bootRun` 3 앱 모두 통과
- PR + main 머지

**Phase 2 종료 게이트:**
- `./gradlew :api:bootRun` `:batch:bootRun` `:consumer:bootRun` 모두 healthy `/actuator/health`
- 각 앱 Dockerfile 빌드 성공 (`docker build -t magicjar/api ./api`)
- TickPublisher → 임베디드 Kafka → TickListener 왕복 1건 PASS

---

## Phase 3 — pyworker + frontend 스켈레톤 (개요)

**브랜치 (병렬):** `agent/ai-engineer/p3-pyworker`, `agent/frontend/p3-frontend`
**담당:** ai-engineer (opus, pyworker), frontend (sonnet, SPA)

**Phase 3-A: pyworker (별도 plan)**
- `pyproject.toml` (uv), `Dockerfile` (python:3.12-slim + Playwright chromium)
- `app/main.py` FastAPI + `/health`, `/scrape/{source}`, `/screenshot`
- `scrapers/naver_news.py` 더미 + `kafka_producer.py` (aiokafka)
- `screenshot/capture.py` Playwright 헤드리스 + MinIO 업로드
- `pytest` + `pytest-asyncio` 단위 테스트 5건 (mock S3·Kafka)

**Phase 3-B: frontend (별도 plan)**
- `package.json` (pnpm) + `vite.config.ts` + `tsconfig.json`
- `src/main.tsx`, `App.tsx`, `router.tsx`
- shadcn/ui 셋업 + 페이지 5개 라우트 골격(빈 페이지)
- `components/chart/Chart.tsx` LWC 래퍼 (틱·캔들 시리즈, takeScreenshot 1차 캡처) — **spec §11-5 커스텀 지양 원칙 준수**
- `Dockerfile` 멀티 스테이지 (node:20-alpine → nginx:alpine)
- vitest 단위 테스트 5건

**Phase 3 종료 게이트:**
- `cd pyworker && pytest` 모두 PASS
- `cd frontend && pnpm test` 모두 PASS
- `docker compose up pyworker frontend` healthy

---

## Phase 4 — Docker compose + 통합 스모크 + main 머지 (개요)

**브랜치:** `agent/architect/p4-docker-smoke`
**담당:** architect (opus)

**범위:**
- `docker/docker-compose.yml` (kafka·postgres·redis·minio + 5 앱 서비스)
- `docker/docker-compose.dev.yml` (volume mount, 핫 리로드)
- `docker/docker-compose.infra.yml` (인프라만)
- `scripts/smoke.sh` 5 통합 시나리오 (spec §15-2)
- QA on-demand 풀 게이트 1회 (ADR 0016 트리거 조건 5/7 해당)
- `_workspace/_harness-state.json` Phase D 종료 기록
- main 머지

**Phase 4 종료 게이트:**
- `docker compose up` 5 앱 + 4 인프라 모두 healthy
- `./scripts/smoke.sh` 5 시나리오 모두 PASS
- QA APPROVED
- `_harness-state.json` finishedAt 기록

---

## Self-Review

### Spec 커버리지

- §1 모듈 레이아웃 → P1 Task 1.1 (디렉토리 + build.gradle.kts)
- §2 런타임 구성 → P2 (3 JVM 앱 main 작성)
- §3 MSA vs compose → P4 (Dockerfile + compose)
- §4 Kafka 토폴로지 → P2 (Spring Kafka config + 1쌍 listener/publisher)
- §5 domain 패키지 → P1 Task 1.4~1.9 (영역별 이전)
- §6 매핑 → P1 Task 1.4~1.10 (이전 + 삭제)
- §7 pyworker → P3-A
- §8 Docker → P4 + 각 Phase의 Dockerfile
- §9 데이터 저장소 → P2 (DataSource config) + P4 (postgres compose)
- §10 레지스트리·태깅 → P4 (CI 워크플로 추가)
- §11 프론트엔드 → P3-B
- §12 ADR 변동 → 발행 완료(`bc7d24c`)
- §13 에이전트 책임 → 갱신은 Phase 별 PR 메시지 + CLAUDE.md (P1 Task 1.11-3)
- §14 마이그레이션 → P1~P4 자체가 §14 절차
- §15 테스트·검증 → P4 smoke 시나리오 5건
- §16 하네스 갱신 → Task 1.11 + P4
- §17 위험·완화 → 각 Task의 Expected/실패 시 처리에 반영
- §18 다음 단계 → 본 plan의 Phase 분할 자체

### Placeholder 스캔
- `// TODO P2`: 의도적 마커 (JPA 의존 클래스 임시 주석). P2에서 일괄 해소.
- 그 외 TBD/TODO/구현 미정 표현 없음.

### Type 일관성
- `ChartSnapshot.source: SnapshotSource` (enum), `SnapshotSource = CLIENT_LWC | SERVER_BROWSER` — spec §11-4와 일치 (3중 fallback이 2단으로 줄면서 `CLIENT_DOM` 제거됨, spec과 plan 동일).
- 패키지 prefix `com.magicjar.domain.*` 일관 적용.
- 영역명 `common/market/strategy/ai/order/journal` 6개 일관.

### 보강 필요 발견
- Task 1.5의 NewsRepository port 위치를 `domain/journal/port`로 임시 배치한 부분은 spec §6에서 명확히 안 함. P3-A에서 pyworker가 news 전체 책임지면서 `journal/port/NewsRepository`는 삭제 또는 이동 가능 — P3-A plan에서 결정.
- `app/src/main/resources/application.yml` 백업본은 P2에서 4개 앱 yml로 분할 시 사용. P1 plan에 백업 단계 명시했음.
- ktlint가 신규 패키지 prefix 규칙(`com.magicjar.*`)을 어떻게 처리하는지 확인 필요. 기존 `kr.spdaewoon.*`은 OK였으니 prefix 길이만 다를 뿐 문제없을 가능성 높음. P1 빌드 시 자동 검증.

(보강은 inline 적용 완료.)

---

## Execution Handoff

Plan 작성·self-review·커밋 진행. 이후 사용자 결정:

**1. Subagent-Driven (recommended)** — magicjar-orchestrator가 Phase별로 fresh 서브에이전트(architect/backend-core/...)에게 task 단위 위임, 각 task 완료 후 두 단계 리뷰. 토큰 분배·격리 우수.

**2. Inline Execution** — 본 메인 세션에서 task 순차 실행, 체크포인트마다 사용자 확인.

**3. Hybrid** — P1만 Inline(아키텍처 민감, 토큰 적음)으로 시작 후 P2부터 Subagent-Driven 전환. magicJar 하네스 v2 가이드 정합.

→ 권장: **3 Hybrid**. 사용자 확인 후 진입.
