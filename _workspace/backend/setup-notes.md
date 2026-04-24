# Phase B 셋업 노트 — 구현 시작 전 체크리스트

이 노트는 Phase B(구현 병렬)를 시작하기 전에 backend-core가 먼저 확인·준비해야 하는 항목을 정리한다.

## 1. 로컬 도구

| 항목 | 요구 버전 | 확인 |
|------|---------|------|
| JDK | 21 (Temurin) | `java -version` → `21` |
| Kotlin | 2.0.x (K2) | `kotlinc -version` (IDE 내 번들로 충분) |
| Gradle | Wrapper 사용 (8.x) | `./gradlew -v` |
| Docker | 최신 | `docker --version` |
| Docker Compose | v2 | `docker compose version` |
| Node.js (Tailwind CLI용) | 20 LTS (선택) | `node -v` |

## 2. 로컬 인프라 기동

루트에 `docker-compose.yml` 생성 (Phase B에서 backend-core가 작성):

```yaml
services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: magicjar
      POSTGRES_USER: magicjar
      POSTGRES_PASSWORD: magicjar
    ports: ["5432:5432"]
  redis:
    image: redis:7
    ports: ["6379:6379"]
```

`docker compose up -d`로 기동. 프로파일 `mock`에서 이 값을 기본 바라봄.

## 3. 환경변수 템플릿

`.env.example`을 루트에 커밋, `.env`는 `.gitignore`에 포함됨. 개발자는 `.env.example`을 복사해 자신의 값을 채운다.

```
# .env.example
SPRING_PROFILES_ACTIVE=mock
MAGICJAR_MODE=BACKTEST

KIS_MOCK_APP_KEY=
KIS_MOCK_APP_SECRET=
KIS_ACCOUNT_CANO=
KIS_ACCOUNT_PRDT=01

ANTHROPIC_API_KEY=
DART_API_KEY=

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/magicjar
SPRING_DATASOURCE_USERNAME=magicjar
SPRING_DATASOURCE_PASSWORD=magicjar

SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379
```

## 4. Gradle 프로젝트 뼈대 (backend-core 책임)

```
settings.gradle.kts:
  rootProject.name = "magicjar"
  include("app", "core", "market-data", "strategy", "ai", "order-exec", "web")
```

루트 `build.gradle.kts` 공통 플러그인:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    kotlin("plugin.jpa") version "2.0.21" apply false
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
    kotlin { jvmToolchain(21) }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict")
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}
```

`plugin.spring`(all-open)은 모든 서브모듈에, `plugin.jpa`(no-arg)는 JPA 엔티티를 쓰는 모듈(`strategy`, `market-data`, `web` 중 해당)에만 적용.

## 5. AI 비활성 부팅 지원

`ANTHROPIC_API_KEY` 미설정 시:
- `AiClient` 빈은 `NoOpAiClient`로 주입
- 모든 AI 서비스가 `Optional.empty()` 반환 또는 `aiEnabled=false` 플래그 응답
- UI는 "AI 기능은 API 키가 없어서 비활성화되어 있습니다" 배너 표시

이 전략은 Phase B 초기에도 키 없이 빌드·실행·테스트가 가능하게 한다.

## 6. API 키 없는 상태의 Phase B 실행 방법

사용자 상태: **KIS·Anthropic·DART 키 모두 없음**.

- **KIS 클라이언트**는 인터페이스·요청 빌더까지 완성하고, `BrokerGateway`의 테스트 구현체(`FakeBrokerGateway`)에서 Stub 응답을 반환하도록 한다 (샘플 JSON을 `test-support`에 둠).
- **AI 서비스**는 위 `NoOpAiClient`로 비활성 부팅.
- **DART/뉴스 수집**은 RSS/샘플 피드로 시작, 키 필요한 부분은 플래그로 비활성.

이로써 Phase B·C의 전 구간을 **키 없이 끝까지 실행 가능**하게 한다. 키 발급 후 `.env`만 채우면 실제 호출 경로가 즉시 활성화된다.

## 7. Phase B 시작 진입점

새 세션에서 아래 문구를 입력:

```
magicjar-orchestrator 스킬로 Phase B 구현 시작해줘.
_workspace/arch/ 의 ADR과 module-boundaries.md를 근거로,
backend-core부터 스캐폴딩하고 이후 market-data / strategy / ai / frontend 를 병렬로 진행.
```

오케스트레이터는 `_workspace/_harness-state.json`을 읽어 Phase A가 완료된 상태임을 확인하고 Phase B로 진입한다.
