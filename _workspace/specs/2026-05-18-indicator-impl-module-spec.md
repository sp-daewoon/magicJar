# spec: indicator-impl 모듈 신설 — IndicatorSource 모듈 경계 결함 fix (결함 3)

- 발행: 2026-05-18 KST
- 발행자: architect (opus 4.7 1M)
- 트랙: T-IND 보강 (W2 잔재 정정 — W3 종착 v3.6.14 patch prep)
- 동반 ADR: 0083 Proposed
- 사용자 결정: 옵션 B — 새 Gradle 모듈 신설 (2026-05-18 KST 최고 관리자)
- 운영 코드 touch: 본 spec PR은 0 LOCK. 코드 이동은 sub-agent backend-core 후속 PR에서 수행.

---

## §1. 사용자 dogfooding evidence

최고 관리자(2026-05-18 KST) 보고:

```
GET http://localhost:8080/api/indicators?code=066570&name=ema&interval=1d&params=period%3D20&adjusted=false
→ HTTP/1.1 400 Bad Request
   { "status": 400, "error": "Bad Request" }
```

본 endpoint는 ADR 0021 (Indicator Source 추상화) 결정 정합 5 native indicator(SMA·EMA·RSI·MACD·BB) + KIS Strategy Builder 80지표 합계 81지표를 사용자 차트 overlay·DSL 평가에 노출하는 핵심 자원이다. `ema period=20` 같은 가장 기본 호출이 GREEN이어야 한다.

---

## §2. root cause 분석 (architect 직접 검증)

### §2-1. 모듈 경계 위반 evidence

- `api/src/main/kotlin/.../api/config/IndicatorEngineConfig.kt` (35 line):
  ```kotlin
  @Configuration
  class IndicatorEngineConfig {
      @Bean
      fun indicatorEngine(sources: List<IndicatorSource>): IndicatorEngine {
          log.info("IndicatorEngine registered sources: [{}]", ...)
          return IndicatorEngine(sources)
      }
  }
  ```
  Spring autowire `List<IndicatorSource>` — api 모듈 ApplicationContext에 등록된 `IndicatorSource` bean을 모두 수집.

- `api/src/main/kotlin/.../api/service/IndicatorService.kt`:
  ```kotlin
  result = indicatorEngine.calculate(spec, candles)
  // ↓ IndicatorException.UnsupportedSpecException → HttpStatus.BAD_REQUEST
  ```

- `consumer/src/main/kotlin/.../consumer/application/indicator/source/NativeIndicatorSource.kt` (44 line):
  ```kotlin
  @Component
  class NativeIndicatorSource : IndicatorSource {
      override val priority: Int = 50
      private val supportedNames: Set<String> = setOf("ma", "ema", "rsi", "macd", "bb")
  }
  ```
  **consumer 모듈 단독 거주** + `@Component` 의존.

- `consumer/.../source/KisStrategyBuilderSource.kt` (140 line): 동일하게 consumer 단독 + `@Component`.

- `api/build.gradle.kts`: `implementation(project(":consumer"))` **부재** (모듈 경계 LOCK — ADR 0004 §의존 방향).

### §2-2. 결과 매트릭스

| 컨테이너 | IndicatorEngineConfig 위치 | autowire List<IndicatorSource> 수집 결과 |
|---|---|---|
| api | `api/.../api/config/IndicatorEngineConfig.kt` | **0건** (NativeIndicatorSource·KisStrategyBuilderSource는 api 모듈에서 미발견 — consumer 의존 0) |
| consumer | `consumer/.../indicator/IndicatorEngineConfig.kt` | 2건 (native + kis-strategy-builder) + `LiveCandleMergingSource` decorator wrap (자본 보호 hotfix 2026-05-15 정합) |

따라서 api 컨테이너 IndicatorEngine bean은 **sources=emptyList()** 상태로 부팅 → 모든 `calculate()` 호출이 `IllegalArgumentException("No source supports ${spec.name}")` 발생 (도메인 `IndicatorEngine`은 빈 sources에서 `minByOrNull → null` → throw, IndicatorService catch 분기에서 `IndicatorException.UnsupportedSpecException` 매핑 시 400 응답).

### §2-3. 이중 결함

- 결함 3-A: api 모듈 IndicatorSource 0건 → 400 응답 (사용자 dogfooding evidence 본체).
- 결함 3-B: api 모듈 IndicatorEngineConfig는 `LiveCandleMergingSource` decorator wrap 부재 → 자본 보호 critical hotfix(2026-05-15) **api 측 누락**. 향후 api 경로에서 strategy 평가/실시간 차트 호출 시 stale DB 1d close로 BUY/SELL 잘못 발생 risk 잠재. 본 spec은 결함 3-A 본체 해소 + 결함 3-B는 backlog §66 후속 항목으로 분리 검토 (api 경로는 IndicatorService cache-aside read-only 사용 — 시그널 생성 진입 0).

### §2-4. fitness gap 검증 (`feedback_v3_skepticism_no_regression`)

- "자동매매에 필요한가?" — **YES**. 지표는 룰/시그널 평가 의무 + 사용자 차트 overlay 사용자 노출 1차 자원. ADR 0021 §3에 명시.
- "v1/v2 잔재인가?" — **NO**. IndicatorSource 추상화는 W2 T-IND 트랙 핵심(2026-05-03 ADR 0021 결정). 본 결함은 W2 T-IND PR-33a/PR-33b 머지 시점에 source 구현체를 consumer 단독 거주로 배치한 모듈 경계 결함 — pattern-engine(ADR 0054) 동일 패턴 사전 인식 부재. **ADR 0054 §후속에 "indicator-engine 동일 패턴 검토 — backlog 추가"가 이미 기록**되어 있어 본 결함은 그 backlog가 운영 부채로 누적된 결과다.

---

## §3. 검토 대안 매트릭스

| 옵션 | 장점 | 단점 |
|---|---|---|
| A. api → consumer 의존 추가 | build.gradle 1줄 | **ADR 0004 §의존 방향 위반** + KafkaListener(KisWebSocketBridge 등)가 api 컨테이너에 이중 등록 → Kafka 이중 소비 risk + 컨테이너 부팅 시 KIS WS 클라이언트 2개 동시 connect → KIS 41건 한도(ADR 0048) 위반 risk |
| **B. indicator-impl 라이브러리 모듈 신설** (본 결정, 사용자 명시) | ADR 0004 정합 보존 + api·consumer 동일 빈 공유 + 컨테이너 수 불변 + Hexagonal 정석 + ADR 0054 pattern-engine 패턴 재사용 (운영 인지 비용 0) | 모듈 1개 신설 비용 + 13 파일 git mv + 패키지 리네이밍 |
| C. domain에 source 구현 흡수 + `@Component` 의존 추가 | 모듈 추가 0 | **domain Spring 의존 0 정책 위반**(ADR 0004 §의존 방향 LOCK) + Hexagonal port 순수성 위반 |
| D. api·consumer 양쪽에 source 구현 별도 발행 (중복) | 컨테이너 격리 강함 | **80지표 코드 중복** (3,560 line × 2) + drift risk + 알고리즘 정합성 유지 비용 폭증 |

**채택: 옵션 B** — 사용자 명시 결정 + ADR 0054 pattern-engine 동일 패턴 정합(운영 인지 비용 추가 0).

---

## §4. 결정 — indicator-impl 모듈 신설

### §4-1. 모듈 이름 (architect 결정)

**`indicator-impl`** 채택.

- 후보: `indicator-impl` / `indicator-engine` / `indicator-source` / `indicator-sources`
- 채택 사유:
  - `indicator-impl`이 가장 책임 정확 — IndicatorSource port 구현체 컬렉션 (domain port가 별도 거주).
  - pattern-engine은 detector + recognizer 통합이라 "engine"이 적합. indicator는 engine이 `domain/.../indicator/IndicatorEngine.kt`에 이미 존재 → 모듈명 중복 risk 회피.
  - `indicator-source`는 단수형/복수형 모호 + 향후 caching·decorator 등 비-source 자원 추가 시 부적합.
  - 모듈명 LOCK — 결정 변동 risk 0 (settings.gradle.kts·디렉토리 path·패키지 path 3중 일치).

### §4-2. 모듈 위치·종류

- 위치: `indicator-impl/` (root 직속, `domain`·`pattern-engine`과 동일 레벨)
- 종류: Kotlin + Spring Context (Spring Boot main 0, 라이브러리 jar) — pattern-engine 패턴 정확 동일.
- 패키지 루트: `kr.spdaewoon.magicjar.indicator.impl.*`
- 컨테이너: 0 (라이브러리 jar — api·batch·consumer 컨테이너 수 불변).

### §4-3. 의존 그래프 (단방향, ADR 0004 정합 보존)

```
domain  ←  indicator-impl  ←  api
                          ←  consumer
        ←  pattern-engine  ←  api, consumer  (ADR 0054 — 기존)
```

- `domain` ← 모든 모듈 의존 (이전과 동일).
- `indicator-impl` → `domain`만 의존. 다른 응용 모듈 미의존 LOCK.
- `api`, `consumer` → `indicator-impl` 신규 의존.
- `batch` → `indicator-impl` 미의존 (batch는 indicator 미사용 — 현행 정합 보존).
- `api ↔ consumer ↔ batch` 서로 직접 의존 0 LOCK (ADR 0004 §의존 방향 정합 보존).

cycle 검증: domain(0) → indicator-impl(domain) → api/consumer(domain + indicator-impl) — cycle 0건.

### §4-4. 책임 분리

| 자원 | 거주 모듈 | 사유 |
|---|---|---|
| `IndicatorEngine` (라우팅 본체) | **domain** (현행 유지) | 순수 — Spring 의존 0, sources List 인자 받는 POJO |
| `IndicatorSpec`·`IndicatorResult`·`IndicatorPoint`·`IndicatorCatalog`·`IndicatorException`·`IndicatorSource` port | **domain** (현행 유지) | Hexagonal port |
| `NativeIndicatorSource` (`@Component`) | **indicator-impl** (이동 — consumer 떠남) | Spring `@Component` 의존 + IndicatorSource port 구현 |
| `KisStrategyBuilderSource` (`@Component`) | **indicator-impl** (이동) | 동일 |
| `Native*` helper 5 파일 | **indicator-impl** (이동) | NativeIndicatorSource가 의존 |
| `Indicator*` (Trend/Momentum/Volume/Volatility/Oscillator/Other + KisIndicatorMath) 7 파일 | **indicator-impl** (이동) | KisStrategyBuilderSource가 의존 |
| `LiveCandleMerger` (`@Component`, Redis StringRedisTemplate 의존) | **consumer** (잔류) | Redis key `candle:today:{code}:1d` 정합 — KisWebSocketBridge가 update 책임 + consumer 모듈 자본 보호 hotfix(2026-05-15) 컨텍스트 잔류가 정합. api 측은 별도 wiring 없음(결함 3-B — backlog §66 후속). |
| `LiveCandleMergingSource` decorator | **consumer** (잔류) | LiveCandleMerger 의존 — 동일 사유 |
| `IndicatorEngineConfig` (api) | **api** (현행 유지) | api ApplicationContext bean wiring 책임 |
| `IndicatorEngineConfig` (consumer) | **consumer** (현행 유지) | consumer ApplicationContext bean wiring + LiveCandleMerger wrap 책임 |

**핵심 정합**: indicator-impl에 거주하는 두 `@Component` source는 api·consumer **양쪽 ApplicationContext에 동시 등록**된다. api Config는 wrap 0(read-only cache-aside endpoint 한정), consumer Config는 LiveCandleMergingSource wrap (자본 보호 critical 정합 보존).

### §4-5. Gradle 설정 매트릭스

**`settings.gradle.kts`** (add-only 1줄):

```kotlin
include(
    "domain",
    "pattern-engine",
    "indicator-impl",   // ← 신규 (ADR 0083)
    "api",
    "batch",
    "consumer",
)
```

**`indicator-impl/build.gradle.kts`** (신규 — pattern-engine 패턴 1:1 정합):

```kotlin
// indicator-impl — 라이브러리 모듈 (Spring Boot main 0)
// architect spec §4-5 (2026-05-18-indicator-impl-module-spec.md) LOCK 정합 + ADR 0083
// 의존 방향: domain ← indicator-impl ← api, consumer
// api·consumer 둘 다 implementation(project(":indicator-impl")) 의존
// NativeIndicatorSource·KisStrategyBuilderSource 두 @Component 빈을 양쪽 ApplicationContext에 노출

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":domain"))

    // Spring Context only (Spring Boot starter 미사용 — pattern-engine 동일)
    implementation("org.springframework:spring-context")

    // Kotlin std
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // slf4j (Logger)
    implementation("org.slf4j:slf4j-api")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback")
    }
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

tasks.jar {
    enabled = true
}
```

**`api/build.gradle.kts`** (add-only 1줄):

```kotlin
dependencies {
    implementation(project(":domain"))
    implementation(project(":pattern-engine"))
    implementation(project(":indicator-impl"))   // ← 신규 (ADR 0083)
    // ... 이하 동일
}
```

**`consumer/build.gradle.kts`** (add-only 1줄):

```kotlin
dependencies {
    implementation(project(":domain"))
    implementation(project(":pattern-engine"))
    implementation(project(":indicator-impl"))   // ← 신규 (ADR 0083)
    // ... 이하 동일
}
```

**`batch/build.gradle.kts`** — 변경 0 (batch는 indicator-impl 미의존 — indicator 사용 0).

---

## §5. 마이그레이션 매트릭스

### §5-1. 이동 파일 13건 (git mv + 패키지 rename)

| # | from (consumer) | to (indicator-impl) | line |
|---|---|---|---|
| 1 | `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/indicator/source/NativeIndicatorSource.kt` | `indicator-impl/src/main/kotlin/kr/spdaewoon/magicjar/indicator/impl/NativeIndicatorSource.kt` | 44 |
| 2 | `consumer/.../indicator/source/KisStrategyBuilderSource.kt` | `indicator-impl/.../indicator/impl/KisStrategyBuilderSource.kt` | 140 |
| 3 | `consumer/.../indicator/source/native/NativeMa.kt` | `indicator-impl/.../indicator/impl/native/NativeMa.kt` | 41 |
| 4 | `consumer/.../indicator/source/native/NativeEma.kt` | `indicator-impl/.../indicator/impl/native/NativeEma.kt` | 42 |
| 5 | `consumer/.../indicator/source/native/NativeRsi.kt` | `indicator-impl/.../indicator/impl/native/NativeRsi.kt` | 84 |
| 6 | `consumer/.../indicator/source/native/NativeMacd.kt` | `indicator-impl/.../indicator/impl/native/NativeMacd.kt` | 57 |
| 7 | `consumer/.../indicator/source/native/NativeBollingerBands.kt` | `indicator-impl/.../indicator/impl/native/NativeBollingerBands.kt` | 57 |
| 8 | `consumer/.../indicator/source/native/IndicatorMath.kt` | `indicator-impl/.../indicator/impl/native/IndicatorMath.kt` | 111 |
| 9 | `consumer/.../indicator/source/kis/IndicatorTrend.kt` | `indicator-impl/.../indicator/impl/kis/IndicatorTrend.kt` | 622 |
| 10 | `consumer/.../indicator/source/kis/IndicatorMomentum.kt` | `indicator-impl/.../indicator/impl/kis/IndicatorMomentum.kt` | 706 |
| 11 | `consumer/.../indicator/source/kis/IndicatorVolume.kt` | `indicator-impl/.../indicator/impl/kis/IndicatorVolume.kt` | 334 |
| 12 | `consumer/.../indicator/source/kis/IndicatorVolatility.kt` | `indicator-impl/.../indicator/impl/kis/IndicatorVolatility.kt` | 291 |
| 13 | `consumer/.../indicator/source/kis/IndicatorOscillator.kt` | `indicator-impl/.../indicator/impl/kis/IndicatorOscillator.kt` | 301 |
| 14 | `consumer/.../indicator/source/kis/IndicatorOther.kt` | `indicator-impl/.../indicator/impl/kis/IndicatorOther.kt` | 200 |
| 15 | `consumer/.../indicator/source/kis/KisIndicatorMath.kt` | `indicator-impl/.../indicator/impl/kis/KisIndicatorMath.kt` | 303 |

**합계: 15 파일 git mv · ~3,433 line · 패키지 rename `kr.spdaewoon.magicjar.consumer.application.indicator.source` → `kr.spdaewoon.magicjar.indicator.impl`**

(이전 §G 작업 요청에서 13으로 명시했으나 실측 결과 15건이 정확 — kis/ 7건 + native/ 6건 + source/ 직접 2건. 작업 요청 §C 의제 "각 파일의 의존 그래프" 검증 결과 LiveCandleMerger·LiveCandleMergingSource·IndicatorEngineConfig는 consumer 잔류로 명확히 분리됨.)

### §5-2. 잔류 파일 3건 (consumer 모듈)

| # | 파일 | line | 잔류 사유 |
|---|---|---|---|
| 1 | `consumer/.../indicator/LiveCandleMerger.kt` | 151 | Redis StringRedisTemplate + KisWebSocketBridge Redis key `candle:today:{code}:1d` 정합 — 자본 보호 hotfix(2026-05-15) consumer 컨텍스트 잔류 정합 |
| 2 | `consumer/.../indicator/LiveCandleMergingSource.kt` | 35 | LiveCandleMerger 의존 decorator — 동일 사유 |
| 3 | `consumer/.../indicator/IndicatorEngineConfig.kt` | 41 | consumer ApplicationContext bean wiring (LiveCandleMergingSource wrap) — 컨텍스트별 wiring 분리 정합 |

### §5-3. 패키지 rename 영향 매트릭스

#### A. 이동 파일 내부 import (15 파일)

- `package kr.spdaewoon.magicjar.consumer.application.indicator.source` → `package kr.spdaewoon.magicjar.indicator.impl`
- `package kr.spdaewoon.magicjar.consumer.application.indicator.source.native` → `package kr.spdaewoon.magicjar.indicator.impl.native`
- `package kr.spdaewoon.magicjar.consumer.application.indicator.source.kis` → `package kr.spdaewoon.magicjar.indicator.impl.kis`

#### B. 외부 import 수정 영향 (검색 결과)

```bash
grep -rln "consumer.application.indicator.source" consumer/src/main/kotlin/
```

영향 파일:
- `consumer/.../indicator/IndicatorEngineConfig.kt` — `LiveCandleMergingSource` 참조 (잔류 — consumer 내부 import 변경 0)
- `consumer/.../indicator/LiveCandleMergingSource.kt` — IndicatorSource import (domain 그대로 — 변경 0)

**=> consumer 내부 잔류 파일 3건은 import 변경 0** (이동 source 직접 참조 없음 — LiveCandleMergingSource는 domain port 추상화 사용).

#### C. api 모듈 import 수정 영향

```bash
grep -rln "consumer.application.indicator" api/src/main/kotlin/
```

영향 파일: **0건** (api의 IndicatorEngineConfig는 `domain.indicator.port.IndicatorSource`만 import — Spring autowire가 모듈 무관 자동 collect).

#### D. batch 모듈 — 영향 0건 (indicator 미의존).

#### E. test 파일 영향

```bash
grep -rln "consumer.application.indicator.source" consumer/src/test/kotlin/ 2>/dev/null
```

향후 sub-agent 단계에서 검증 의무 — 본 spec PR 단계는 운영 코드 0 touch.

### §5-4. ktlint·검증

- `./gradlew :indicator-impl:check` (신규 모듈 단독)
- `./gradlew :api:check` + `./gradlew :consumer:check` (의존 그래프 변동 후 회귀 검증)
- `./gradlew check` (전체 모듈)

---

## §6. 영향 ADR·문서 매트릭스

| 자원 | 변경 | 책임 |
|---|---|---|
| `_workspace/arch/adr/0083-indicator-impl-module.md` | **신규 발행 (Proposed)** | 본 spec PR (architect) |
| `_workspace/arch/adr/README.md` §2 + §11 + §14 | 0083 row add + 변경 이력 row | 본 spec PR (architect) |
| `_workspace/arch/adr/0004-msa-module-restructure.md` | **갱신 0** — 본 ADR 헤더 "갱신일" 표기는 ADR 0054 시점(2026-05-10) 유지 + 본 ADR 0083는 ADR 0054 패턴 확장 정합으로 ADR 0083 자체에서 본 ADR 0004를 갱신 references 마킹 | 본 spec PR — 별도 갱신 commit 분리 가능 |
| `_workspace/arch/adr/0021-indicator-source-abstraction.md` §2-3 | **갱신 0** — source 구현 위치(consumer→indicator-impl)만 변동, 추상화·인터페이스 결정 변동 0. ADR 0083 §References에서 본 ADR 0021을 인용 + 본 ADR 0021 §3 References에 ADR 0083 add (architect 단독 후속 PR 또는 본 PR 동반 — 본 PR 동반 권장) | 본 spec PR (architect, add-only References 1줄) |
| `_workspace/arch/adr/0054-pattern-engine-module.md` §후속 | **선택적 갱신** — 본문 "향후 indicator-engine 동일 패턴 적용 검토" 라인이 본 ADR 0083 발행으로 완수 → 본 ADR 0054 §Links에 0083 add-only 1줄 (또는 후속 사이클) | 본 spec PR (선택) |
| `docs/architecture/master-architecture.md` §5·§6·§10 | 5 모듈 → 6 모듈 (`indicator-impl` row 추가) + §10 ADR 0083 row add | 본 spec PR (architect) |
| `docs/architecture/tracks-roadmap.md` | T-IND row 변동 0 (트랙 결과물 변동 없음 — 모듈 경계 fix만) | 변경 0 |
| `CLAUDE.md` | 변경 0 — "4 모듈" 자리표시 텍스트는 이미 ADR 0004 갱신(5 모듈) 시점에 master에 흡수되어 CLAUDE.md는 인덱스 슬림 정책 정합 | 변경 0 |
| `_workspace/backlog.md` §66 | 신규 — "결함 3 indicator-impl 모듈 신설 (3-A 처리) + 3-B api 측 LiveCandleMerger wiring 검토 별도" | 본 spec PR (architect) |
| `CHANGELOG.md` | row 1건 prepend (anchor + 본 spec 사유) | 본 spec PR (architect) |
| `_workspace/INDEX.md` | 변경 0 — INDEX는 의도별 진입 경로만, 본 spec은 모듈 분기 결정 — master + ADR README 갱신으로 자연 노출 | 변경 0 |

---

## §7. ADR 번호 결정

### §7-1. 점유 현황 (2026-05-18 KST 시점)

- 0081 — T-ACCT (Proposed, 본 spec 발행자 architect 의 ae27aa8d8e67ca690 worktree 기준 본 root branch sha 정합)
- 0082 — **점유 가능성** — T-MAN architect 워크트리(`agent-a3ffe86a791c3b79d` / `agent/architect/t-man-manual-order-issue-spec`) 동시 작업 중. 본 spec 발행 시점에 0082 디스크 미발행이나 충돌 risk 회피 의무.
- 0083 — **본 spec 채택**.

### §7-2. 충돌 회피 정책

본 spec PR 발행 시점에 T-MAN architect가 0082 발행 또는 0083 발행 가능성을 모두 고려:
- T-MAN이 0082 발행: 본 spec 0083 채택 — 충돌 0.
- T-MAN이 0083 발행: 본 spec PR rebase 시점에 충돌 인지 → architect 자율로 0084 시프트(headers + 파일 rename + ADR README row 시프트) — 후속 사이클 처리.

본 spec PR 본문에 "ADR 번호 0083 채택 + T-MAN dispatch 결과에 따라 0084 시프트 가능성 명시" 의무 (orchestrator 머지 시점 검증).

---

## §8. sub-agent dispatch plan

### §8-1. Phase 매트릭스

| Phase | sub-agent | 영역 | 우선순위 | PR |
|---|---|---|---|---|
| 1 (Gradle 설정) | backend-core | settings.gradle.kts + indicator-impl/build.gradle.kts + api/build.gradle.kts + consumer/build.gradle.kts | HIGH | 단일 PR 권장 |
| 2 (파일 이동 + 패키지 rename) | backend-core | 15 파일 git mv + 패키지 declaration 갱신 + 외부 import 갱신 (consumer 잔류 3건 검증) | HIGH | 단일 PR (Phase 1·2 묶음 권장) |
| 3 (dogfooding 검증) | orchestrator (또는 사용자) | `curl http://localhost:8080/api/indicators?code=066570&name=ema&interval=1d&params=period=20&adjusted=false` → 200 GREEN | HIGH (Phase 1·2 머지 후 즉시) | — (운영 검증) |

### §8-2. 권장 PR 분할

**단일 PR 권장 (backend-core 단독)**:
- 영역: Gradle 설정 4개 파일 + 15 파일 git mv + 패키지 rename.
- 사유: 단일 영역(Gradle + Kotlin source 모듈 이동) + 일관 변경 + Phase 1·2 분리 시 빌드 불완전 상태 발생(Phase 1 단독 시 indicator-impl 빈 모듈 → consumer 빌드 실패 위험 / Phase 2 단독 시 indicator-impl 디렉토리 미존재).
- 룰 정합: 개발 룰 §2 "단일 책임 코드 — 한 PR = 한 영역" + 룰 §3 "API 빌딩 한 스텝씩 순차" — 본 PR은 R1(모듈 경계 정정 = 도메인+시그니처 stage)로 분류, 운영 endpoint 변경 0 — 동일 PR OK.

### §8-3. 충돌 행렬 (병렬 dispatch 시)

- T-ACCT Phase 1·2 (backend-core, 진행 중) — domain·api 신규 add-only — **충돌 0** (다른 모듈·다른 파일).
- T-MAN architect spec (architect, 진행 중) — spec/plan 문서만 — **충돌 0** (다른 spec 본문).
- T-ACCT frontend Dashboard — 영역 다름 — **충돌 0**.

**병렬 dispatch 안전**.

### §8-4. 검증 의무 (Phase 1·2 PR sub-agent)

- `./gradlew :indicator-impl:check` (신규 모듈 단독 빌드)
- `./gradlew :api:bootJar` + `./gradlew :consumer:bootJar` (운영 컨테이너 빌드 회귀 검증)
- `./gradlew check` (전체 회귀)
- ktlint PASS
- 부팅 smoke — api 컨테이너 부팅 로그에 `IndicatorEngine registered sources: [native (priority=50), kis-strategy-builder (priority=100)]` 출력 확인 (현행 emptyList()에서 2건으로 회복)
- dogfooding curl evidence 인용 — `code=066570&name=ema&interval=1d&params=period=20` 응답 200 + source=native + 시계열 데이터 포함

---

## §9. v3.x cut 시점

### §9-1. 권장: v3.6.14 patch cut 합류

- `feedback_release_policy_patch_first` 정합 — minor cut(W4·W5) LOCK 외 모든 변경은 patch.
- W2 T-IND 트랙 결함 정정 — minor 진입 아님.
- T-ACCT(ADR 0081 · v3.6.14 patch prep) + T-MAN(ADR 0082 가능성) + 본 spec(ADR 0083) 3건 합류 patch 권장.

### §9-2. 대안: 단일 hotfix patch cut

- 본 spec 단독 머지 후 v3.6.12 (또는 다음 patch 번호) hotfix cut.
- 사용자 dogfooding evidence 본체 fix + 자본 보호 critical 결함이 아니라 사용자 노출 indicator 0건 → 사용자 체감 즉각.
- 단점: cut 빈도 증가 (release 정책 patch-first 정합이지만 합류 권장).

### §9-3. architect 권장

**v3.6.14 합류 cut**:
- T-ACCT + T-MAN + 결함 3 indicator-impl 3건 합류 — patch row 1건 발행.
- 머지 직렬 권장 — T-ACCT backend Phase 1·2 → T-MAN spec → 본 spec PR → 본 spec sub-agent dispatch → 사용자 검증 → v3.6.14 cut.

---

## §10. rollback

- single revert (PR 단위 — git revert):
  - Gradle 설정 4 파일 revert
  - 15 파일 git mv revert (consumer/.../indicator/source/ 원래 위치 복귀)
  - settings.gradle.kts `indicator-impl` row 제거
  - indicator-impl/ 디렉토리 git rm
- 자본 영향: 0 (사용자 노출 endpoint가 revert 시 다시 400 응답으로 회귀 — 결함 3-A 회복).
- 검증 의무: `./gradlew check` GREEN.

---

## §11. 본 spec PR 산출물

| 자원 | 종류 | 책임 |
|---|---|---|
| `_workspace/specs/2026-05-18-indicator-impl-module-spec.md` | 본 파일 (신규) | architect |
| `_workspace/arch/adr/0083-indicator-impl-module.md` | ADR Proposed (신규) | architect |
| `_workspace/arch/adr/README.md` | §2 row + §14 변경 이력 row 갱신 | architect |
| `_workspace/arch/adr/0021-indicator-source-abstraction.md` §3 References | add-only 1줄 (ADR 0083 인용) | architect |
| `docs/architecture/master-architecture.md` §5 + §10 | 6 모듈 매트릭스 + ADR 0083 row | architect |
| `_workspace/backlog.md` §66 | 신규 § | architect |
| `CHANGELOG.md` | row 1건 prepend (anchor + 사유) | architect |
| `_workspace/tasks/T-20260518-1927-*.md` | task 파일 (이미 stage) | architect (task.mjs done 후 자동 갱신) |

**운영 코드 touch: 0** LOCK.

---

## §12. References

- 사용자 dogfooding evidence — `GET /api/indicators?code=066570&name=ema&interval=1d&params=period=20&adjusted=false` → 400 (2026-05-18 KST)
- ADR 0004 (MSA 모듈 재구성) — §의존 방향 정합 보존 + 본 ADR 0083로 5 모듈 → 6 모듈 갱신
- ADR 0021 (Indicator Source 추상화) — §2-3 source 구현 위치만 변동 (consumer→indicator-impl)
- ADR 0054 (pattern-engine 모듈) — 본 ADR 0083 1:1 정합 패턴, §후속 "indicator-engine 동일 패턴 적용 검토" 완수
- ADR 0030 (master-architecture SoT) — §5·§10 갱신 정합
- ADR 0050 (CHANGELOG 슬롯 채번) — 본 spec PR은 architect 단독 → slot row 사전 발행 면제(`feedback_spec_plan_scope` 정합 — 본 spec 단일 PR + sub-agent dispatch 1건 단일 영역)
- 헌법 §3 KIS-first — 본 사이클 raw fetch 발동 0 (모듈 경계 fix만 — KIS API 신규 호출 0)
- 헌법 §5 PR 생성 직전 rebase — sub-agent dispatch PR 시점에 의무
- 사용자 메모리 `feedback_v3_skepticism_no_regression` — fitness gap 정합 (§2-4)
- 사용자 메모리 `feedback_release_policy_patch_first` — v3.6.14 patch 권장 (§9)
- 사용자 메모리 `feedback_spec_plan_scope` — 신규 트랙 아닌 결함 fix이지만 모듈 경계 변경 = spec/plan 발행 의무
