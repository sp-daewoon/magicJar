# PR-41 spec: pattern-engine 라이브러리 모듈 신설 (B-1)

- 작성: architect
- 날짜: 2026-05-10 (KST)
- 트랙: hotfix follow-up — 모듈 경계 정합 (W2 T-PAT 후속)
- 상태: spec — 코드 변경 0 (sub-agent 디스패치용)

## 1. 배경

W2 T-PAT PR-39b에서 패턴 인식기 구현 62파일을 `consumer/src/main/kotlin/.../application/pattern/` 하위에 배치하면서 모듈 경계 결함이 노출됨.

- `api` 모듈의 `PatternController`는 `domain.pattern.port.PatternRecognizer`에 의존하지만, 그 구현체(`@Component("patternRecognizer")` JvmPatternRecognizer)는 `consumer` 모듈에만 등록됨
- ADR 0004 §의존 방향: "api/batch/consumer는 서로 직접 의존하지 않는다" — `api → consumer` 의존을 만들면 MSA 위배 + Kafka 이중 소비 risk
- v3.1.2 hotfix(PR #261)에서 `PatternController`에 `@ConditionalOnProperty(name="magicjar.pattern.enabled", matchIfMissing=false)` 임시 게이트 + `PatternControllerTest`에 `@TestPropertySource` 추가로 응급 봉합

사용자(최고 관리자) B-1 결정: **새 라이브러리 모듈 `pattern-engine` 신설**. `domain` 본래 의도(Spring 의존 0)는 보존 + api·consumer 두 모듈이 동일 구현 빈을 공유.

## 2. 목표

1. `pattern-engine` 라이브러리 모듈 신설 — domain·indicator-engine 호환 패턴(Spring `@Component` OK, Spring Boot main 0)
2. 62파일을 `consumer/.../application/pattern/` → `pattern-engine/.../pattern/` 1:1 이동 + 패키지 리네이밍
3. api·consumer 둘 다 `implementation(project(":pattern-engine"))` 의존
4. PR #261의 `@ConditionalOnProperty` 임시 게이트 자연 제거 (정상 부팅 전환)
5. 컨테이너 수 불변 — api·batch·consumer 3개 그대로 (pattern-engine은 라이브러리 jar, 컨테이너 0)
6. 자원 카탈로그(57 detector·KIS candlestick.py 정합) 정합 보존

## 3. 비목표

- domain 모듈 정책 변경 (ADR 0004 "Spring·JPA 의존 0" 유지 — pattern-engine과 분리)
- consumer KafkaListener·STOMP 구독 변경 (격리 보존)
- api PatternController API 시그니처 변경 (다이그램 외 변경 0)
- 새 KIS endpoint·새 외부 데이터 규격 — 본 사이클 코드 이동만, KIS-first raw fetch 발동 0
- 다른 W2 트랙(T-IND·T-DSL) 영역 침범 — 본 사이클은 T-PAT 한정

## 4. 모듈 신설 사양

### 4-1. 위치 + 빌드 그래프

```
.
├─ domain/         # Kotlin java-library (Spring 0)
├─ pattern-engine/ # ← 신설 — Kotlin + Spring Context (Spring Boot main 0)
├─ api/            # Spring Boot — implementation(project(":pattern-engine")) 추가
├─ batch/          # Spring Boot — pattern 미사용 (의존 추가 0)
└─ consumer/       # Spring Boot — implementation(project(":pattern-engine")) 추가
```

`settings.gradle.kts`:

```kotlin
include(
    "domain",
    "pattern-engine",  // ← 신설
    "api",
    "batch",
    "consumer",
)
```

### 4-2. 의존 방향 (단방향)

- `domain` ← `pattern-engine`, `api`, `batch`, `consumer` 모두 의존 (도메인은 어떤 응용 모듈도 참조 0)
- `pattern-engine` ← `api`, `consumer` 의존. `batch`는 본 사이클 미의존
- `pattern-engine` → `domain`만 의존 (다른 응용 모듈 미의존)
- ADR 0004 의존 방향 정합 보존: api·batch·consumer 상호 직접 의존 0

### 4-3. 패키지 루트

- 신규: `kr.spdaewoon.magicjar.pattern.*`
- 폐기: `kr.spdaewoon.magicjar.consumer.application.pattern.*`

### 4-4. build.gradle.kts (pattern-engine)

라이브러리 jar — `org.springframework.boot` plugin 미적용. `spring-boot-dependencies` BOM은 import 가능(버전 정합용).

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":domain"))

    // Spring Context only (Spring Boot starter 미사용)
    implementation("org.springframework:spring-context")

    // Redis cache (RedisPatternCacheAdapter용 — StringRedisTemplate)
    implementation("org.springframework.data:spring-data-redis")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlin std + slf4j
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.slf4j:slf4j-api")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback")
    }
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
}

// Spring Boot main 0 — bootJar 비활성화
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }
```

(정확한 BOM·버전은 backend-core PR-43에서 root build.gradle.kts·domain build.gradle.kts 패턴 정합으로 결정 — architect는 시그니처만 LOCK)

## 5. 62파일 매핑 매트릭스 (현 위치 → 새 위치 1:1)

검증: `find consumer/src/main/kotlin/.../application/pattern -type f | wc -l = 62`

### 5-1. 패턴 인식 본체 + 헬퍼·캐시 (5파일)

| 현 위치 (consumer) | 새 위치 (pattern-engine) |
|---|---|
| `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/pattern/JvmPatternRecognizer.kt` | `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/JvmPatternRecognizer.kt` |
| `consumer/.../pattern/cache/RedisPatternCacheAdapter.kt` | `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/cache/RedisPatternCacheAdapter.kt` |
| `consumer/.../pattern/util/CandleMath.kt` | `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/util/CandleMath.kt` |
| `consumer/.../pattern/helper/ConsecutiveDirectionCalculator.kt` | `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/helper/ConsecutiveDirectionCalculator.kt` |
| `consumer/.../pattern/helper/SimpleTripleHelper.kt` | `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/helper/SimpleTripleHelper.kt` |

### 5-2. 57 detector (single 14 + triple 27 + pair 16 = 57파일)

`consumer/.../application/pattern/detector/{single,triple,pair}/*.kt` → `pattern-engine/.../pattern/detector/{single,triple,pair}/*.kt`

(57파일 detector 명단은 strategy-engine PR-42 plan에서 git mv 일괄 처리 — architect는 카운트·디렉토리 구조만 LOCK)

### 5-3. RedisPatternCacheAdapter 중복 정리 (사용자 지적 대응)

현 상황 검증 완료:
- `api/.../adapter/pattern/RedisPatternCacheAdapter.kt` (104 lines)
- `consumer/.../application/pattern/cache/RedisPatternCacheAdapter.kt` (107 lines — `encode()` 헬퍼만 추가)

99% 동일 (key·TTL 정책·patternsHash 알고리즘 동일). pattern-engine 단일 구현으로 unify:

- pattern-engine의 RedisPatternCacheAdapter는 `encode()` 포함 (consumer 버전을 기준 채택)
- api·consumer는 둘 다 pattern-engine의 빈을 주입 받음
- api 모듈의 기존 `RedisPatternCacheAdapter` (`api/.../adapter/pattern/`)는 **삭제** + import 갱신 (`PatternController` + `PatternControllerTest`)
- consumer 모듈의 기존 `RedisPatternCacheAdapter` (`consumer/.../application/pattern/cache/`)는 **삭제** (pattern-engine 새 위치로 이동된 것이 사실상 그 자체)
- 패키지: `kr.spdaewoon.magicjar.pattern.cache.RedisPatternCacheAdapter`

### 5-4. 테스트 5파일 (consumer/src/test)

| 현 위치 (consumer test) | 새 위치 |
|---|---|
| `consumer/src/test/kotlin/.../application/pattern/JvmPatternRecognizerTest.kt` | `pattern-engine/src/test/kotlin/kr/spdaewoon/magicjar/pattern/JvmPatternRecognizerTest.kt` |
| `consumer/src/test/.../pattern/PatternRegistryTest.kt` | `pattern-engine/src/test/kotlin/kr/spdaewoon/magicjar/pattern/PatternRegistryTest.kt` |
| `consumer/src/test/.../pattern/CandleMathTest.kt` | `pattern-engine/src/test/kotlin/kr/spdaewoon/magicjar/pattern/CandleMathTest.kt` |
| `consumer/src/test/.../pattern/PatternFixtureTest.kt` | `pattern-engine/src/test/kotlin/kr/spdaewoon/magicjar/pattern/PatternFixtureTest.kt` |
| `consumer/src/test/.../pattern/PatternDslTokenTest.kt` | (보류 — DSL token은 strategy 컨텍스트일 수 있음. PR-42에서 import 추적 후 결정. 디폴트는 pattern-engine 이동) |

## 6. 인터페이스 동결 (LOCK)

| 항목 | LOCK 값 |
|---|---|
| Spring bean name | `"patternRecognizer"` (`@Component("patternRecognizer")`) |
| 도메인 port | `kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer` (변경 0) |
| 도메인 port | `kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector` (변경 0) |
| Detector init 검증 | `init { require(detectors.size == 57) }` 보존 |
| 카탈로그 정합 | KIS candlestick.py 1:1 port — KDoc 인용 줄 보존 (`Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py`) |
| Redis 캐시 키·TTL | `pattern:cache:{code}:{interval}:{patternsHash}` + 분봉 300s / 일봉 86400s 보존 |
| `patternsHash` 알고리즘 | SHA-256 16자리 hex prefix (sorted patterns + from + to) 보존 |

## 7. PatternController 게이트 제거 (backend-core PR-43 책임)

PR #261 hotfix 잔재 자연 제거:

```kotlin
// 제거 대상 1 (PatternController.kt)
@ConditionalOnProperty(name = ["magicjar.pattern.enabled"], havingValue = "true", matchIfMissing = false)

// 제거 대상 2 (PatternControllerTest.kt)
@TestPropertySource(properties = ["magicjar.pattern.enabled=true"])
```

게이트 제거 후 부팅 검증: `pattern-engine` 의존 추가 시 `JvmPatternRecognizer` (`@Component("patternRecognizer")`) 빈이 api 컨테이너에서도 등록 → controller가 의존 주입 성공 → 정상 부팅.

## 8. 대안 검토 (ADR-0054 본문 참조)

| 대안 | 장점 | 단점 | 결정 |
|---|---|---|---|
| A. api → consumer 의존 | 변경 최소 | MSA 위배 + Kafka 이중 소비 risk + ADR 0004 §의존 방향 위반 | ✗ 거부 |
| **B-1. pattern-engine 신설** | domain 순수성 보존 + api·consumer 공유 + 컨테이너 수 불변 | 모듈 1개 신설 비용 | ✓ **채택** (사용자 결정) |
| B-2. domain에 Spring 흡수 | 모듈 추가 0 | ADR 0004 "domain Spring 의존 0" 정책 깨짐 | ✗ 거부 |
| C. controller 영구 비활성화 | hotfix 그대로 | 기능 미배포 = T-PAT 효과 0 | ✗ 응급 한정 |

## 9. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression.md`)

- v1/v2 잔재 검증: 본 사이클은 W2 T-PAT(v3 신규 트랙) 코드 이동만 → v1/v2 잔재 0건
- 자동매매 적합성: 패턴 인식은 W2 T-PAT 카탈로그 본체 + W3 T-SIG 시그널 룰의 입력 — 자동매매 본체 적합 ✓
- 모듈 경계 깔끔성: domain port + pattern-engine 구현 = Hexagonal 정석 정합 ✓

## 10. 룰 5 검증 (development-rules.md)

- 룰 1 (QA 게이트 배제): 본 사이클 spec/plan only, QA 발동 0 ✓
- 룰 2 (단일 책임 PR): PR-42 strategy-engine = 코드 이동 단일 책임, PR-43 backend-core = 빌드 그래프 + 게이트 제거 단일 책임 ✓
- 룰 3 (라운드 분할): 본 사이클은 R0 = 모듈 경계 정합 — 신규 라운드 추가 0 (W2 T-PAT R3는 별도) ✓
- 룰 4 (코드 먼저, 테스트 나중): 테스트 5파일은 코드와 함께 이동만(신규 테스트 0) ✓
- 룰 5 (AI 호출 자제): pattern-engine은 W2 라이브러리 — LLM provider import 0 ✓

## 11. KIS-first raw fetch 의무 (헌법 §3)

본 사이클은 코드 이동만으로 KIS API endpoint·외부 데이터 규격 결정 0건 → raw fetch 발동 비대상.

단, 옮기는 62파일의 KDoc 안에 KIS candlestick.py 줄 인용은 그대로 보존 의무. PR-42에서 sub-agent 자체 추론 0 — 기존 KDoc 1:1 보존 + import 갱신만.

## 12. ADR 갱신 동반

- **ADR-0004 갱신**: 모듈 매트릭스 4 → 5 (pattern-engine 행 추가, 의존 방향 정정)
- **ADR-0054 신규**: pattern-engine 라이브러리 모듈 분리 결정 — domain 순수성 보존 + api·consumer 공유

## 13. master-architecture.md 갱신 동반

ADR 0030 SoT 정합 — `docs/architecture/master-architecture.md` §6-1 컨테이너 표 + §5-1 도메인 § 직후 5-1A "pattern-engine 라이브러리 §" 신설:

```
| 모듈 | 종류 | 책임 |
|---|---|---|
| `domain` | Kotlin java-library (Spring 0) | 도메인 모델·port·DSL·정책 |
| `pattern-engine` | Kotlin + Spring Context (Boot 0) | 57 패턴 detector + Redis 캐시 (api·consumer 공유) |
| `api` | Spring Boot | REST·STOMP·Swagger·외부 어댑터 |
| `batch` | Spring Boot | 스케줄 잡 |
| `consumer` | Spring Boot | Kafka·KIS WS·실시간 |
```

(architect 단독 갱신 — 본 spec은 권위 인용 출처)

## 14. 후속 검토 (post-cycle, ADR 미발행)

- indicator-engine 동일 패턴 적용 검토 — 80개 in-process 지표 합성. 현재는 consumer 단독 컨텍스트로 이동 부담 미발생, T-IND 본 사이클 종료 후 영향 행렬 점검 시 결정
- strategy-engine(DSL 평가기) 동일 패턴 — KisYamlDslParser·JvmStrategyEvaluator는 현재 consumer 단독 사용, 분리 부담 미발생

본 spec은 pattern-engine 한정. 다른 엔진은 본 사이클 비대상.

## 15. 영향 모듈

| 모듈 | 변경 | sub-agent |
|---|---|---|
| `pattern-engine` | 신설 + 62파일 import (ADD) | strategy-engine PR-42 |
| `pattern-engine/build.gradle.kts` | 신설 (ADD) | backend-core PR-43 |
| `settings.gradle.kts` | include 1줄 추가 | backend-core PR-43 |
| `api/build.gradle.kts` | implementation 1줄 추가 | backend-core PR-43 |
| `consumer/build.gradle.kts` | implementation 1줄 추가 | backend-core PR-43 |
| `consumer/.../application/pattern/` 62파일 | 삭제 (git mv) | strategy-engine PR-42 |
| `consumer/.../JvmStrategyEvaluator.kt` | import 갱신 (1~2줄) | strategy-engine PR-42 |
| `consumer/src/test/.../pattern/` 5파일 | 삭제 (git mv) | strategy-engine PR-42 |
| `api/.../adapter/pattern/RedisPatternCacheAdapter.kt` | 삭제 + PatternController·PatternControllerTest import 갱신 | backend-core PR-43 |
| `api/.../PatternController.kt` | `@ConditionalOnProperty` 1줄 제거 | backend-core PR-43 |
| `api/.../PatternControllerTest.kt` | `@TestPropertySource` 제거 | backend-core PR-43 |
| ADR-0004 | 갱신 | architect (본 PR-41) |
| ADR-0054 | 신규 | architect (본 PR-41) |
| `master-architecture.md` §5·§6 | 5모듈 갱신 | architect (본 PR-41) |
| `_workspace/arch/adr/README.md` | 0054 추가 | architect (본 PR-41) |
| CHANGELOG.md | 슬롯 row 3건 + anchor 4건 prepend | architect (본 PR-41) |

## 16. Links

- ADR 0004 (MSA 모듈 재구성) — 본 spec이 갱신 대상
- ADR 0054 (pattern-engine 라이브러리 모듈) — 본 spec이 발급 동반
- ADR 0030 (master-architecture SoT)
- ADR 0050 (CHANGELOG 슬롯 채번)
- 헌법 §3 (KIS-first), §4 (CHANGELOG 슬롯), §5 (rebase)
- pr-38-w2-t-pat-design.md (W2 T-PAT 본 spec — 62파일 카탈로그 진입점)
- pr-38-kis-pattern-raw-verification.md (KIS candlestick.py raw fetch baseline)
- PR #261 (v3.1.2 hotfix — 본 사이클의 응급 봉합 잔재 정정 대상)
