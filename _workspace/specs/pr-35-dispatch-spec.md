# PR-35 sub-agent 분배 명세 (T-DSL R1+R2 통합 — strategy-engine + backend-core 병렬)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 정합 SoT: `_workspace/specs/pr-35-w2-t-dsl-design.md` v1 + `pr-35-w2-t-dsl-plan.md` v1 + `pr-35-conflict-matrix.md` v1 + `pr-35-kis-dsl-raw-verification.md` v1 + ADR 0022/0047/0021/0050
- 본 문서: orchestrator가 strategy-engine·backend-core 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안. T-IND PR-32 dispatch-spec 패턴 100% 정합.

---

## §1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-35** | architect | M (spec only) | `agent/architect/w2-t-dsl-spec` | **현재 — 본 PR** |
| **PR-36a** | strategy-engine | L | `agent/strategy-engine/pr-36-w2-t-dsl-parser-evaluator` | **PR-35 머지 후 병렬 1** |
| **PR-36b** | backend-core | M~L | `agent/backend-core/pr-36-w2-t-dsl-domain-rest-flyway` | **PR-35 머지 후 병렬 1** |
| **PR-37** | frontend (메인) + strategy-engine + backend-core (보조) | M~L | `agent/frontend/pr-37-w2-t-dsl-r3` | PR-36a/b 머지 후 |

**디스패치 모드 (PR-36)**: **완전 병렬** (`pr-35-conflict-matrix.md` §결론 정합).

**인터페이스 동결 시점**: 본 spec(`pr-35-w2-t-dsl-design.md` + 본 dispatch + 충돌 행렬 + raw verification)이 머지되는 즉시 — strategy-engine·backend-core 양쪽이 §6-2(domain sealed class) + §6-3(parser·evaluator) + §6-4(REST endpoint) + §6-5(Flyway) + §5-5(DSL 식별자 매핑 LOCK) 보고 동시 시작 가능.

**최고 관리자 메모리 정합 — `feedback_orchestrator_active_parallel.md`**: architect "직렬 권장" 답습 금지. 본 PR-36은 충돌 행렬에서 modify-shared 3건 단독 LOCK + add-only 100% → **완전 병렬 권장**.

---

## §2. strategy-engine 위임 (PR-36a — 메인)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | strategy-engine (opus — ADR 0035 정합) |
| 브랜치 | `agent/strategy-engine/pr-36-w2-t-dsl-parser-evaluator` |
| base | `main` (PR-35 spec 머지 후) |
| 작업 author | `bot+strategy@magicjar.local` |
| 작업 author name | `magicjar-strategy` |
| Task 파일 goal | `T-DSL strategy-engine — KisYamlDslParser (KIS parser.py 664 lines 1:1 Kotlin port — 정규식 토크나이저 21 token + 6단 재귀 하강 _parse_or→and→condition→arithmetic→term→factor + AST 빌더 + static 검증 visitor depth/노드/indicator/화이트리스트 + complexity 4단 계산) + JvmStrategyEvaluator (AST visitor + IndicatorEngine 단일 진입점 호출 + sandbox timeout 100ms·CPU budget 50ms·strategy-eval-pool 4 fixed 경유 + autoDisable 호출) + DslIdentifierMapper (DSL 식별자→IndicatorSpec 매핑 25+종 spec §5-5 LOCK) + PatternRecognizerStub (T-PAT 미진입 1차 stub) + StrategyEvalPoolConfig (@Bean ThreadPoolTaskExecutor) + StrategyEvalMetricRecorder (ring buffer N=100) + 단위 테스트 5종 (parser fixture KIS raw 8건·evaluator basic·complexity calc·sandbox timeout·KIS yaml import)` |
| Task 파일 deliverables | `consumer/.../application/strategy/KisYamlDslParser.kt + JvmStrategyEvaluator.kt + DslIdentifierMapper.kt + PatternRecognizerStub.kt + StrategyEvalPoolConfig.kt + StrategyEvalMetricRecorder.kt, consumer/src/test/kotlin/.../{KisDslParserFixtureTest,JvmStrategyEvaluatorTest,StrategyComplexityTest,StrategyTimeoutSandboxTest,KisYamlImportTest}.kt 5건, api/build.gradle.kts (:consumer dep 1줄 추가 + circular 검증), CHANGELOG row in-place edit (작성: strategy-engine)` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | L (~1500-2000 LOC — `pr-35-w2-t-dsl-plan.md` §2-1 정합) |

### 2-2. 작업 범위 (Done 정의)

#### A. KisYamlDslParser (consumer 모듈) — KIS raw 1:1 port

- [ ] `consumer/.../application/strategy/KisYamlDslParser.kt` — `IndicatorSource`처럼 `@Component`
  ```kotlin
  package kr.spdaewoon.magicjar.consumer.application.strategy

  import kr.spdaewoon.magicjar.domain.strategy.dsl.*
  import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
  import kr.spdaewoon.magicjar.domain.strategy.StrategyException
  import org.springframework.beans.factory.annotation.Value
  import org.springframework.stereotype.Component

  /**
   * KIS Strategy Builder DSL parser (Source: parser.py:L301-L530, License: MIT)
   * 정규식 토크나이저(21 token) + 6단 재귀 하강 (_parse_or → _parse_and → _parse_condition →
   *   _parse_arithmetic → _parse_term → _parse_factor)
   * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2 (자체 추론 0)
   */
  @Component
  class KisYamlDslParser(
      @Value("\${magicjar.dsl.ast-depth-limit:32}") private val astDepthLimit: Int,
      @Value("\${magicjar.dsl.node-count-limit:10000}") private val nodeCountLimit: Int,
      @Value("\${magicjar.dsl.indicator-call-limit:50}") private val indicatorCallLimit: Int,
  ) {
      // 21 token patterns — KIS raw L304-L322 LOCK (자체 추론 0)
      private val tokenPatterns: List<Pair<String, Regex>> = listOf(
          "LPAREN" to Regex("""\("""),  "RPAREN" to Regex("""\)"""),
          "COMMA" to Regex(""","""),     "PERCENT" to Regex("""%"""),
          "CROSSES_ABOVE" to Regex("""crosses_above"""),
          "CROSSES_BELOW" to Regex("""crosses_below"""),
          "BREAKS" to Regex("""breaks"""),
          "AND" to Regex("""(?:AND|and)\b"""),
          "OR" to Regex("""(?:OR|or)\b"""),
          "GTE" to Regex(""">="""),  "LTE" to Regex("""<="""),
          "NEQ" to Regex("""!="""),  "EQ" to Regex("""=="""),
          "GT" to Regex(""">"""),    "LT" to Regex("""<"""),
          "MUL" to Regex("""\*"""),  "DIV" to Regex("""/"""),
          "ADD" to Regex("""\+"""),  "SUB" to Regex("""-"""),
          "NUMBER" to Regex("""-?\d+\.?\d*"""),
          "IDENTIFIER" to Regex("""[a-zA-Z_][a-zA-Z0-9_]*"""),
          "WHITESPACE" to Regex("""\s+"""),
      )

      fun parse(dslText: String): DslNode { /* tokenize + _parse_or_expression() */ }
      fun validateStatic(ast: DslNode): StrategyComplexity { /* visitor — depth·노드·indicator + 화이트리스트 */ }
      fun parseYaml(yamlContent: String, format: YamlFormat): StrategyDefinition { /* Jackson YAML + parse buy_condition/sell_condition 텍스트 */ }
  }
  ```

- [ ] **token name LOCK** (KIS raw L304-L322 정합 — 자체 추론 0)
- [ ] **6단 재귀 하강 우선순위 LOCK** (KIS raw L412-L470 정합)
- [ ] **ConditionType 결정 룰 LOCK** (KIS raw L361-L390 정합 — operator 분기)
- [ ] **화이트리스트 LOCK** (DSL 식별자 — `IndicatorCatalog.allSpecs().map { it.id }` + alias 25+ + `consecutive`·`pattern`·`change`·`highest`·`lowest`·`close`·`prev_close`·`high`·`low`·`volume`)

#### B. JvmStrategyEvaluator (consumer 모듈)

- [ ] `consumer/.../application/strategy/JvmStrategyEvaluator.kt`
  ```kotlin
  @Component
  class JvmStrategyEvaluator(
      private val indicatorEngine: IndicatorEngine,
      private val patternRecognizerStub: PatternRecognizerStub,
      private val identifierMapper: DslIdentifierMapper,
      @Qualifier("strategyEvalPool") private val evalPool: ThreadPoolTaskExecutor,
      private val metricRecorder: StrategyEvalMetricRecorder,
      private val strategyService: StrategyService,
      @Value("\${magicjar.dsl.eval.timeout-ms:100}") private val timeoutMs: Long,
      @Value("\${magicjar.dsl.cpu-budget-ms:50}") private val cpuBudgetMs: Long,
  ) {
      fun evaluate(strategy: Strategy, candles: List<Candle>, ctx: EvalContext): StrategyEvalResult { ... }
      private fun walk(node: DslNode, candles: List<Candle>): EvalResult { ... }
      private fun submitWithTimeout(callable: Callable<StrategyEvalResult>): StrategyEvalResult { ... }
  }
  ```
- [ ] AST walk visitor — IndicatorRef → DslIdentifierMapper → IndicatorEngine.calculate 호출 + Condition·CompositeCondition 평가
- [ ] sandbox: Future.get(100ms) + ThreadMXBean.getThreadCpuTime
- [ ] timeout 1회 발생 시 → strategyService.autoDisable(strategyId, reason="TIMEOUT") + log WARN

#### C. DslIdentifierMapper (consumer 모듈)

- [ ] `consumer/.../application/strategy/DslIdentifierMapper.kt`
  ```kotlin
  @Component
  class DslIdentifierMapper {
      /** spec §5-5 LOCK — 자체 추론 0 */
      fun toIndicatorSpec(ref: IndicatorRef): IndicatorSpec = when (ref.name) {
          "ma", "ema", "rsi", "cci", "adx", "mfi", "atr",
          "disparity", "volatility", "returns", "highest", "lowest", "volume_ma",
          "obv", "change",
              -> IndicatorSpec(ref.name, paramsOf(ref))
          "macd" -> IndicatorSpec("macd", paramsOfMacd(ref))
          "macd_signal" -> IndicatorSpec("macd", paramsOfMacd(ref))
          "macd_hist" -> IndicatorSpec("macd", paramsOfMacd(ref))
          "bb_upper", "bb_lower" -> IndicatorSpec("bb", paramsOfBb(ref))
          "bb_middle" -> IndicatorSpec("bb", mapOf("period" to (ref.params.firstOrNull() ?: 20)))
          "stoch_k" -> IndicatorSpec("stochastic_k", paramsOf(ref))
          "stoch_d" -> IndicatorSpec("stochastic_d", paramsOf(ref))
          // ... 80지표 모두 (T-IND IndicatorCatalog.ALL.map { it.id })
          else -> throw StrategyException.UnknownDslSymbolException(ref.name)
      }

      fun extractValue(result: IndicatorResult, ref: IndicatorRef): BigDecimal {
          val outputKey = when (ref.name) {
              "macd_signal" -> "signal"
              "macd_hist" -> "histogram"
              "bb_upper" -> "upper"
              "bb_lower" -> "lower"
              "bb_middle" -> "middle"
              "stoch_k" -> "k"
              "stoch_d" -> "d"
              else -> ref.name  // single-output
          }
          return result.series.last().values[outputKey]
              ?: throw IllegalStateException("Output key $outputKey not found in IndicatorResult")
      }
  }
  ```

#### D. PatternRecognizerStub (consumer 모듈) — T-PAT 미진입 1차 stub

- [ ] `consumer/.../application/strategy/PatternRecognizerStub.kt`
  ```kotlin
  @Component
  @ConditionalOnMissingBean(name = ["patternRecognizer"])  // T-PAT 머지 시 진짜 bean 우선
  class PatternRecognizerStub {
      private val log = LoggerFactory.getLogger(javaClass)

      fun recognize(name: String, candles: List<Candle>): Boolean {
          log.warn("PatternRecognizerStub called for '{}' — T-PAT not yet implemented, returning false", name)
          return false
      }

      fun consecutive(direction: String, candles: List<Candle>): Int {
          log.warn("PatternRecognizerStub.consecutive('{}') — T-PAT not yet implemented, returning 0", direction)
          return 0
      }
  }
  ```

#### E. StrategyEvalPoolConfig (consumer 모듈) — ADR 0047 §2-3 정합

- [ ] `consumer/.../application/strategy/StrategyEvalPoolConfig.kt`
  ```kotlin
  @Configuration
  class StrategyEvalPoolConfig {
      @Bean("strategyEvalPool")
      fun strategyEvalPool(
          @Value("\${magicjar.dsl.eval-pool.size:4}") poolSize: Int,
          @Value("\${magicjar.dsl.eval-pool.queue:100}") queueSize: Int,
      ): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
          corePoolSize = poolSize
          maxPoolSize = poolSize
          queueCapacity = queueSize
          setThreadNamePrefix("strategy-eval-")
          setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
          setWaitForTasksToCompleteOnShutdown(true)  // ADR 0045 graceful shutdown 정합
          setAwaitTerminationSeconds(2)               // ADR 0047 §2-8
          initialize()
      }
  }
  ```

#### F. StrategyEvalMetricRecorder (consumer 모듈)

- [ ] `consumer/.../application/strategy/StrategyEvalMetricRecorder.kt`
  ```kotlin
  @Component
  class StrategyEvalMetricRecorder(
      private val strategyEvalMetricRepository: StrategyEvalMetricRepository,
  ) {
      fun record(strategyId: Long, result: StrategyEvalResult) {
          // INSERT — strategy_eval_metric 테이블
          // ring buffer N=100 — 별도 retention cron으로 처리 (post-MVP)
      }
  }
  ```

#### G. api 모듈 — `:consumer` dep 추가 (T-IND PR-32 정합)

- [ ] `api/build.gradle.kts` — `:consumer` dep 1줄 추가
  ```kotlin
  // api/build.gradle.kts
  dependencies {
      implementation(project(":domain"))
      implementation(project(":consumer"))   // 본 PR 추가 — KisYamlDslParser bean inject용
      // ...
  }
  ```
  - **circular dep 검증 의무**: `./gradlew :api:bootJar` 실행 → BUILD SUCCESS
  - circular fail 시 즉시 architect 호출 (영역 침범 방지)
  - **fallback (architect 결정)**: api 모듈에 `KisYamlDslParser` 코드 복사 (parser.py 1 파일 self-contained라 복사 비용 낮음)

#### H. 단위 테스트 5종 (룰 4 정합 — R3 일괄 전 검증 동력 보장)

- [ ] `consumer/src/test/kotlin/.../strategy/KisDslParserFixtureTest.kt` — 8 fixture (KIS raw L1-L16 정합)
  ```kotlin
  class KisDslParserFixtureTest {
      private val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)

      @ParameterizedTest
      @CsvSource(
          "ma(5) crosses_above ma(20),CROSSOVER",
          "rsi(14) < 30,THRESHOLD",
          "close breaks high(250),BREAKOUT",
          "consecutive(up) >= 5,PATTERN",
          "close > ma(60) AND change > 0,COMPOSITE",
          "volume > volume_ma(20) * 1.5,THRESHOLD",
          "returns(1) > -3.5,THRESHOLD",
          "returns(5) > 5%,THRESHOLD",  // PERCENT
      )
      fun `parses KIS raw fixture 8건`(input: String, expectedType: String) { ... }
  }
  ```

- [ ] `consumer/src/test/kotlin/.../strategy/JvmStrategyEvaluatorTest.kt` — basic evaluator (mock IndicatorEngine)
- [ ] `consumer/src/test/kotlin/.../strategy/StrategyComplexityTest.kt` — static 검증 한도(depth·노드·indicator·화이트리스트)
- [ ] `consumer/src/test/kotlin/.../strategy/StrategyTimeoutSandboxTest.kt` — timeout 시나리오 (Future.cancel + StrategyTimeoutException)
- [ ] `consumer/src/test/kotlin/.../strategy/KisYamlImportTest.kt` — KIS .kis.yaml 1건 import → DslStrategyDefinition 정합

#### I. 검증 게이트 통과

- spec §1-2 DoD #1·#2·#3·#4·#5·#6·#7·#8·#9·#10·#11·#13·#16·#17·#19·#20 (strategy-engine 영역)
- 헌법 §3 KIS-first raw fetch — KisYamlDslParser KDoc에 raw 출처 줄번호 인용 의무 (자체 추론 0)
- 헌법 §6 ADR 0051 — 본 PR JPA Repository 신설 0건
- 룰 5 (W2 LLM provider import 0) — `grep -rE "spring-ai\|anthropic\|openai" consumer/src/main` 0건

### 2-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(consumer): KisYamlDslParser tokenize + 21 token patterns (T-DSL R1+R2)` | `KisYamlDslParser.kt` (skeleton + tokenize) |
| 2 | `feat(consumer): KisYamlDslParser 6단 재귀 하강 (_parse_or→and→condition→arithmetic→term→factor) — KIS raw L412-L530 1:1 port` | `KisYamlDslParser.kt` (parse methods) |
| 3 | `feat(consumer): KisYamlDslParser static 검증 visitor (depth·노드·indicator·화이트리스트) + complexity 4단 계산 (ADR 0047 §2-1)` | `KisYamlDslParser.kt` (validateStatic) |
| 4 | `feat(consumer): KisYamlDslParser yaml parse (.kis.yaml + magicjar superset format)` | `KisYamlDslParser.kt` (parseYaml) |
| 5 | `feat(consumer): DslIdentifierMapper — DSL 식별자 → IndicatorSpec 매핑 25+ (spec §5-5 LOCK)` | `DslIdentifierMapper.kt` |
| 6 | `feat(consumer): JvmStrategyEvaluator + StrategyEvalPoolConfig (ADR 0047 §2-3 — 4 fixed pool · timeout 100ms)` | `JvmStrategyEvaluator.kt`, `StrategyEvalPoolConfig.kt` |
| 7 | `feat(consumer): PatternRecognizerStub (T-PAT 미진입 1차 stub) + StrategyEvalMetricRecorder` | `PatternRecognizerStub.kt`, `StrategyEvalMetricRecorder.kt` |
| 8 | `feat(api): :consumer dep 추가 (api StrategyController KisYamlDslParser bean inject 용)` | `api/build.gradle.kts` |
| 9 | `test(consumer): DSL parser fixture 8 + evaluator basic + complexity + timeout sandbox + yaml import` | test 5건 |
| 10 | `chore(changelog): PR-36a strategy-engine slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 2-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (consumer·api build.gradle) — KIS DSL parser + AST visitor + JvmStrategyEvaluator + sandbox + DSL 식별자 매핑 + T-PAT stub (T-DSL R1+R2)

## Summary
- PR-36a: T-DSL strategy-engine — KisYamlDslParser(KIS `parser.py` 664 lines 1:1 Kotlin port — 21 token patterns 정규식 + 6단 재귀 하강 + AST 빌더 + static 검증 visitor depth/노드/indicator/화이트리스트 + complexity 4단 LOW/MEDIUM/HIGH/CRITICAL 응답) + JvmStrategyEvaluator(AST visitor + IndicatorEngine 단일 진입점 + sandbox timeout 100ms·CPU budget 50ms·strategy-eval-pool 4 fixed 경유) + DslIdentifierMapper(DSL 식별자→IndicatorSpec 매핑 25+ spec §5-5 LOCK) + PatternRecognizerStub(T-PAT 미진입 1차 stub @ConditionalOnMissingBean) + StrategyEvalPoolConfig(@Bean ThreadPoolTaskExecutor + graceful shutdown 2s) + StrategyEvalMetricRecorder(ring buffer N=100) + 단위 테스트 5종(KIS raw fixture 8건·evaluator basic·complexity·timeout sandbox·yaml import).
- ADR 0022 §2-1·§2-4 + ADR 0047 §2-1·§2-2·§2-3·§2-8 정합. backend-core PR-36b가 domain sealed class 15+ + REST + Flyway 동시 발행 (병렬 안전 — 충돌 행렬 §결론 modify-shared 3건 단독 LOCK).
- 정합: `_workspace/specs/pr-35-w2-t-dsl-design.md` §3-2·§5·§6 + `pr-35-dispatch-spec.md` §2 + `pr-35-conflict-matrix.md` + `pr-35-kis-dsl-raw-verification.md`.
- KIS-first raw fetch (헌법 §3): KisYamlDslParser KDoc에 `Source: parser.py:line_NN · License: MIT` 인용 의무 — 자체 추론 0건.

## 신규 ADR
없음 — ADR 0022/0047 implementation 명세 (architect spec PR-35에서 ADR 0건 결정 — T-IND PR-32 정합).

## Scope
- domain: (read-only — sealed class import만, 신규 정의는 PR-36b)
- consumer: 핵심 영역 — application/strategy/{KisYamlDslParser,JvmStrategyEvaluator,DslIdentifierMapper,PatternRecognizerStub,StrategyEvalPoolConfig,StrategyEvalMetricRecorder}.kt
- api: build.gradle.kts (:consumer dep 1줄 추가만 — controller·service는 PR-36b)
- batch: 변경 0
- frontend: 변경 0 (R3 PR-37에서)

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (PR-37 KIS 10 프리셋 e2e + 정확성 fixture 일괄). 본 PR에 단위 테스트 5종(parser·evaluator·complexity·timeout·yaml)만 — 검증 동력 보장.

## 사용자 검증 방법
1. PR-35 spec PR 머지 + main 위
2. PR-36b backend-core 머지 (domain sealed class + REST + Flyway V26·V27)
3. `scripts/build-and-deploy.sh`
4. `curl -X POST http://localhost:8080/api/strategy -H "Content-Type: application/json" -d '{"name":"goldcross","entry":"ma(5) crosses_above ma(20)","exit":"ma(5) crosses_below ma(20)"}'`
5. response 200 + `{"strategyId":N,"complexity":{"astDepth":3,"nodeCount":7,"indicatorCallCount":2,"complexityClass":"LOW"}}`
6. `curl -X POST http://localhost:8080/api/strategy -d '{"entry":"system_exit() > 0",...}'` → 400 + `UnknownDslSymbolException`
7. depth 33 짜리 식 → 400 + `DslDepthExceededException`
8. `./gradlew :consumer:test --tests "*KisDslParser*"` GREEN

## 정합 ADR
- 0022 (architect spec PR-35 implementation 명세) — Strategy DSL `.kis.yaml` superset §2-1·§2-4
- 0047 — DSL Evaluation Sandbox §2-1(static 검증)·§2-2(timeout/CPU)·§2-3(thread pool)·§2-8(graceful shutdown)
- 0021 — Indicator Source 추상화 (IndicatorEngine 단일 진입점 의존)
- 0051 — Repository default fun 함정 (본 PR JPA Repository 신설 0건 — 함정 발동 0)
- 0050 — CHANGELOG 슬롯 (slot row in-place edit `(작성: strategy-engine)`)
```

### 2-5. strategy-engine 의존성·정합 의무

- **ADR 0022 §2-1·§2-4** — 11 클래스 1:1 port + 평가 흐름
- **ADR 0047 §2-1~§2-3·§2-8** — static 검증 + runtime sandbox + thread pool + graceful shutdown
- **ADR 0021 §2-2** — IndicatorEngine 단일 진입점 의존
- **ADR 0050** — CHANGELOG slot row in-place edit `(작성: strategy-engine)` 마커 row
- **헌법 §3 KIS-first** — KisYamlDslParser 각 클래스 KDoc에 `parser.py:line_NN` 인용. `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/strategy_core/dsl/parser.py` 실행 후 line 번호 확인 의무. 자체 추론 0건.
- **헌법 §6 ADR 0051** — 본 PR JPA Repository 신설 0건. 함정 발동 0.
- **룰 5 (W2 LLM provider import 0)** — spring-ai·anthropic·openai import 0건 의무 (자연어 변환 코드 0건). `grep -rE "spring-ai\|anthropic\|openai\|naturalLanguage\|nlToDsl" consumer/src/main/kotlin` → 0건 의무.
- **룰 4 (테스트 마지막 일괄)** — 5종 단위 테스트만, KIS 10 프리셋 + 정확성 fixture는 PR-37 R3.
- **DSL 식별자 매핑 LOCK** — spec §5-5 LOCK 정합 (자체 추론 0). 매핑 누락 시 R3 fixture cross-check 실패.

**circular dep 처리**: api 모듈이 consumer 모듈 dep 추가 후 circular 발견 시 architect 호출 (재발행 spec 갱신 — api 모듈에 KisYamlDslParser 코드 복사 fallback). 자체 결정 금지.

---

## §3. backend-core 위임 (PR-36b — 보조)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet — ADR 0035 정합) |
| 브랜치 | `agent/backend-core/pr-36-w2-t-dsl-domain-rest-flyway` |
| base | `main` (PR-35 spec 머지 후) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `T-DSL backend-core — domain/strategy/* sealed class 15+ (KIS 11 클래스 1:1 port + 3 rename 정합 — Indicator→IndicatorRef·Value→LiteralValue·StrategyDefinition→DslStrategyDefinition · ConditionType·Operator·LogicalOperator·ArithmeticOperator·DslNode sealed root·StrategyComplexity·ComplexityClass·StrategyDisabledReason·Signal·StrategyEvalResult·StrategyException sealed class) + Universe·TimeFilter·Sizing·Timeframe (magicJar 확장) + StrategyController CRUD/validate/import/export/enable/getEvalMetric (Swagger 4단 + 한국어) + StrategyService(parser → static 검증 → complexity → DB persist + autoDisable) + StrategyJpaEntity·Repository·EvalMetric·JpaAdapter (Repository abstract fun만 — ADR 0051 함정 회피) + Flyway V26 (strategy 컬럼 disabled_reason·last_disabled_at·eval_metric_summary 추가) + V27 (strategy_eval_metric 신규 테이블) + .env.example 10 변수 + application.yml DSL env mapping` |
| Task 파일 deliverables | `domain/.../strategy/dsl/{ConditionType,Operator,LogicalOperator,ArithmeticOperator,DslNode,IndicatorRef,LiteralValue,ArithmeticExpr,Condition,CompositeCondition,DslStrategyDefinition}.kt 11 파일, domain/.../strategy/{Strategy,StrategyDefinition,StrategyComplexity,ComplexityClass,StrategyDisabledReason,StrategyEvalResult,Signal,StrategyException,Universe,TimeFilter,Sizing,Timeframe}.kt 12 파일, api/.../controller/StrategyController.kt + dto/* 6+ 파일, api/.../application/strategy/StrategyService.kt, api/.../persistence/strategy/{StrategyJpaEntity,StrategyRepository,StrategyEvalMetricJpaEntity,StrategyEvalMetricRepository,StrategyJpaAdapter}.kt 5 파일, api/src/main/resources/db/migration/{V26__strategy_disabled_reason.sql,V27__strategy_eval_metric.sql}, api/src/main/resources/application.yml + consumer/src/main/resources/application.yml DSL env mapping, .env.example 10 env 추가, CHANGELOG row in-place edit (작성: backend-core)` |
| 머지 정책 | rebase |
| 분량 | M~L (~1500-1700 LOC — `pr-35-w2-t-dsl-plan.md` §3-1 정합) |

### 3-2. 작업 범위 (Done 정의)

#### A. domain/strategy/dsl/* (KIS 11 클래스 1:1 port)

- [ ] `domain/.../strategy/dsl/ConditionType.kt` — KIS raw L23 정합 (CROSSOVER·THRESHOLD·BREAKOUT·PATTERN·COMPOSITE)
- [ ] `domain/.../strategy/dsl/Operator.kt` — KIS raw L34 정합 (9종)
- [ ] `domain/.../strategy/dsl/LogicalOperator.kt` — KIS raw L46 정합 (AND·OR)
- [ ] `domain/.../strategy/dsl/ArithmeticOperator.kt` — KIS raw L52 정합 (4종)
- [ ] `domain/.../strategy/dsl/DslNode.kt` — sealed class root
- [ ] `domain/.../strategy/dsl/IndicatorRef.kt` — KIS raw L60 (Indicator → IndicatorRef rename) + requiredDays() port (raw L290-L292)
- [ ] `domain/.../strategy/dsl/LiteralValue.kt` — KIS raw L218 (Value → LiteralValue rename) + isPercent normalized()
- [ ] `domain/.../strategy/dsl/ArithmeticExpr.kt` — KIS raw L235
- [ ] `domain/.../strategy/dsl/Condition.kt` — KIS raw L251 + requiredDays() (raw L283-L296)
- [ ] `domain/.../strategy/dsl/CompositeCondition.kt` — KIS raw L283
- [ ] `domain/.../strategy/dsl/DslStrategyDefinition.kt` — KIS raw L555 (StrategyDefinition → DslStrategyDefinition rename)

각 파일 KDoc:
```kotlin
/**
 * KIS Strategy Builder DSL Indicator → IndicatorRef rename.
 * Source: parser.py:L60 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2
 */
```

#### B. domain/strategy/* (magicJar 확장 + 도메인 메타)

- [ ] `domain/.../strategy/Strategy.kt` — DB 영속 entity 짝
- [ ] `domain/.../strategy/StrategyDefinition.kt` — magicJar 확장 wrapper
- [ ] `domain/.../strategy/StrategyComplexity.kt` — ADR 0047 §2-1-B
- [ ] `domain/.../strategy/ComplexityClass.kt` — enum LOW·MEDIUM·HIGH·CRITICAL
- [ ] `domain/.../strategy/StrategyDisabledReason.kt` — enum NULL·TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL
- [ ] `domain/.../strategy/StrategyEvalResult.kt` — data class
- [ ] `domain/.../strategy/Signal.kt` — enum BUY·SELL·HOLD
- [ ] `domain/.../strategy/StrategyException.kt` — sealed class (ADR 0047 §2-1)
- [ ] `domain/.../strategy/Universe.kt`·`TimeFilter.kt`·`Sizing.kt`·`Timeframe.kt` — magicJar 확장 (ADR 0022 §2-2)

#### C. api/.../controller/StrategyController + DTO

- [ ] `api/.../controller/StrategyController.kt` — spec §6-4 LOCK
  - `POST /api/strategy` (등록 + complexity 응답)
  - `GET /api/strategy/{id}?format=magicjar|kis-yaml` (export 시 KIS 호환 comment fallback)
  - `POST /api/strategy/validate` (등록 전 미리보기)
  - `POST /api/strategy/import?format=kis-yaml` (multipart file upload)
  - `PUT /api/strategy/{id}/enable` (auto-disabled 다시 활성화)
  - `GET /api/strategy/{id}/eval-metric` (마지막 N=20 metric)
  - Swagger 4단 + 한국어 + example 값 (사용자 메모리 `feedback_swagger_pattern.md` 정합)
- [ ] `api/.../controller/dto/*` 6+ 파일 (CreateStrategyRequest·CreateStrategyResponse·StrategyResponse·ValidateRequest·EvalMetricSummaryDto·UniverseDto·TimeFilterDto·SizingDto·StrategyComplexityDto)

#### D. api/.../application/strategy/StrategyService

- [ ] `api/.../application/strategy/StrategyService.kt`
  ```kotlin
  @Service
  class StrategyService(
      private val kisYamlDslParser: KisYamlDslParser,
      private val strategyRepository: StrategyRepository,
      private val strategyJpaAdapter: StrategyJpaAdapter,
  ) {
      fun create(req: CreateStrategyRequest): CreateStrategyResponse {
          val entryAst = kisYamlDslParser.parse(req.entry)
          val exitAst = kisYamlDslParser.parse(req.exit)
          val complexity = kisYamlDslParser.validateStatic(entryAst)  // + exitAst 검증
          // ... DB persist (StrategyJpaAdapter)
          return CreateStrategyResponse(strategyId, complexity)
      }

      fun autoDisable(strategyId: Long, reason: StrategyDisabledReason) {
          strategyJpaAdapter.updateDisabledReason(strategyId, reason, Instant.now())
          // (T-RISK 합류 시) risk.violation.v1 publish
      }

      fun import(file: MultipartFile, format: String): CreateStrategyResponse { ... }
      fun export(id: Long, format: String): StrategyResponse { ... }
      fun enable(id: Long): StrategyResponse { ... }
  }
  ```

#### E. api/.../persistence/strategy/* (ADR 0051 함정 회피 의무)

- [ ] `api/.../persistence/strategy/StrategyJpaEntity.kt` — V26 컬럼 정합
  - `disabled_reason VARCHAR(32)` enum mapping
  - `last_disabled_at TIMESTAMPTZ`
  - `eval_metric_summary JSONB` (jsonb_to_jsonb converter)
- [ ] `api/.../persistence/strategy/StrategyRepository.kt` — **abstract fun만 (ADR 0051 함정 회피)**
  ```kotlin
  interface StrategyRepository : JpaRepository<StrategyJpaEntity, Long> {
      fun findByUserIdAndDisabledReasonIsNull(userId: Long): List<StrategyJpaEntity>
      // ❌ 절대 금지: fun upsertWrapper(...) { ... } — body 0 의무
  }
  ```
- [ ] `api/.../persistence/strategy/StrategyEvalMetricJpaEntity.kt` — V27 신규
- [ ] `api/.../persistence/strategy/StrategyEvalMetricRepository.kt` — abstract fun만
- [ ] `api/.../persistence/strategy/StrategyJpaAdapter.kt` — domain port 구현 + private fun helper

#### F. Flyway V26·V27

- [ ] `api/src/main/resources/db/migration/V26__strategy_disabled_reason.sql`
  ```sql
  ALTER TABLE strategy
      ADD COLUMN IF NOT EXISTS disabled_reason VARCHAR(32),
      ADD COLUMN IF NOT EXISTS last_disabled_at TIMESTAMPTZ,
      ADD COLUMN IF NOT EXISTS eval_metric_summary JSONB;

  COMMENT ON COLUMN strategy.disabled_reason IS 'NULL·TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL — ADR 0047 §2-2-D';
  ```
- [ ] `api/src/main/resources/db/migration/V27__strategy_eval_metric.sql`
  ```sql
  CREATE TABLE strategy_eval_metric (
      id BIGSERIAL PRIMARY KEY,
      strategy_id BIGINT NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
      evaluated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      eval_time_ms INTEGER NOT NULL,
      cpu_time_ms INTEGER,
      outcome VARCHAR(32) NOT NULL,
      error_msg TEXT
  );
  CREATE INDEX idx_strategy_eval_metric_strategy_id_evaluated_at
      ON strategy_eval_metric(strategy_id, evaluated_at DESC);
  COMMENT ON TABLE strategy_eval_metric IS 'ADR 0047 §2-6 — 마지막 N=100건 ring buffer';
  ```

**전제**: `strategy` 테이블이 V15 이전에 이미 존재. 만약 미존재 시 V25에서 별도 추가 필요 (ADR 0022 §2-6 기존 결정 정합 — sub-agent backend-core 부팅 검증 의무).

#### G. application.yml + .env.example (ADR 0047 §2-7 정합)

- [ ] `api/src/main/resources/application.yml` + `consumer/src/main/resources/application.yml`
  ```yaml
  magicjar:
    dsl:
      ast-depth-limit: ${MAGICJAR_DSL_AST_DEPTH_LIMIT:32}
      node-count-limit: ${MAGICJAR_DSL_NODE_COUNT_LIMIT:10000}
      indicator-call-limit: ${MAGICJAR_DSL_INDICATOR_CALL_LIMIT:50}
      eval:
        timeout-ms: ${MAGICJAR_DSL_EVAL_TIMEOUT_MS:100}
        cpu-budget-ms: ${MAGICJAR_DSL_CPU_BUDGET_MS:50}
      backtest-timeout-ms: ${MAGICJAR_DSL_BACKTEST_TIMEOUT_MS:1000}
      cpu-budget-violation-threshold: ${MAGICJAR_DSL_CPU_BUDGET_VIOLATION_THRESHOLD:5}
      eval-pool:
        size: ${MAGICJAR_DSL_EVAL_POOL_SIZE:4}
        queue: ${MAGICJAR_DSL_EVAL_POOL_QUEUE:100}
        overload-threshold: ${MAGICJAR_DSL_EVAL_POOL_OVERLOAD_THRESHOLD:0.80}
  ```
- [ ] `.env.example` 10 변수 추가 (시크릿 아님 — ADR 0046 비위반)

#### H. 검증 게이트 통과

- spec §1-2 DoD #3·#4·#5·#6·#7·#11·#13·#14·#15·#18·#20 (backend-core 영역)
- 헌법 §6 ADR 0051 — Repository abstract fun만 검증 의무
- 룰 5 (W2 LLM provider import 0) — `grep -rE "spring-ai\|anthropic\|openai\|naturalLanguage" {domain,api}/src/main` 0건

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): DSL 4 enum (ConditionType·Operator·LogicalOperator·ArithmeticOperator) — KIS raw L23·L34·L46·L52 1:1 port (T-DSL R1)` | `domain/.../strategy/dsl/*.kt` 4개 enum |
| 2 | `feat(domain): DSL 7 sealed/data class (DslNode·IndicatorRef·LiteralValue·ArithmeticExpr·Condition·CompositeCondition·DslStrategyDefinition) + requiredDays port` | `domain/.../strategy/dsl/*.kt` 7개 |
| 3 | `feat(domain): Strategy domain — Strategy·StrategyDefinition·Complexity·DisabledReason·EvalResult·Signal·Exception sealed + Universe·TimeFilter·Sizing·Timeframe (magicJar 확장)` | `domain/.../strategy/*.kt` 12개 |
| 4 | `feat(api): StrategyController CRUD + validate + import + export + enable + getEvalMetric (Swagger 4단)` | `api/.../controller/StrategyController.kt` + dto/* 6+ |
| 5 | `feat(api): StrategyService — parser → static 검증 → complexity → persist + autoDisable` | `api/.../application/strategy/StrategyService.kt` |
| 6 | `feat(api): StrategyJpaEntity·Repository·EvalMetric·Adapter (Repository abstract fun만 — ADR 0051 함정 회피)` | `api/.../persistence/strategy/*.kt` 5개 |
| 7 | `feat(api): Flyway V26 strategy disabled_reason 컬럼 + V27 strategy_eval_metric 테이블 (ADR 0047 §2-6)` | `db/migration/V26·V27` |
| 8 | `chore(env): DSL env 10 변수 + application.yml mapping (ADR 0047 §2-7)` | `application.yml` (api·consumer) + `.env.example` |
| 9 | `chore(changelog): PR-36b backend-core slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 3-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (domain·api) — Strategy domain sealed class 15+ + REST endpoint + Flyway V26·V27 + DSL env 10 변수 (T-DSL R1+R2)

## Summary
- PR-36b: T-DSL backend-core — domain/strategy/* 23+ 파일(KIS 11 클래스 1:1 port + 3 rename 정합 — IndicatorRef·LiteralValue·DslStrategyDefinition · 4 enum + 7 sealed/data class) + magicJar 확장 12 파일(Strategy·Definition·Complexity·DisabledReason·EvalResult·Signal·Exception·Universe·TimeFilter·Sizing·Timeframe) + StrategyController(CRUD/validate/import/export/enable/getEvalMetric · Swagger 4단 + 한국어) + StrategyService(parser → static 검증 → complexity → persist + autoDisable) + StrategyJpaEntity·Repository·EvalMetric·JpaAdapter(Repository abstract fun만 — ADR 0051 함정 회피) + Flyway V26(disabled_reason·last_disabled_at·eval_metric_summary 컬럼 추가) + V27(strategy_eval_metric 신규 테이블) + .env.example 10 변수.
- ADR 0022 §2-1·§2-2·§2-3 + ADR 0047 §2-1-B·§2-6·§2-7 정합. strategy-engine PR-36a가 KisYamlDslParser + JvmStrategyEvaluator + sandbox 동시 발행 (병렬 안전 — 충돌 행렬 §결론 modify-shared 3건 단독 LOCK).
- 정합: `_workspace/specs/pr-35-w2-t-dsl-design.md` §3-1·§6-2·§6-4·§6-5 + `pr-35-dispatch-spec.md` §3 + `pr-35-conflict-matrix.md`.

## 신규 ADR
없음 — ADR 0022/0047 implementation 명세 (architect spec PR-35에서 ADR 0건 결정 — T-IND PR-32 정합).

## Scope
- domain: 핵심 영역 — strategy/* 23+ 파일 add-only (4 enum + 7 sealed/data class DSL + 12 magicJar 확장)
- api: 핵심 영역 — controller/StrategyController + dto + application/strategy/StrategyService + persistence/strategy/* 5개 + Flyway V26·V27
- consumer·batch: 변경 0 (consumer는 application.yml DSL env mapping 1건만)
- frontend: 변경 0 (R3 PR-37에서)

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (PR-37 KIS 10 프리셋 e2e + 정확성 fixture 일괄). 본 PR에 단위 테스트 0건 — domain sealed class·REST·Flyway는 통합 e2e가 자연스러움.

## 사용자 검증 방법
1. PR-35 spec PR 머지 + main 위
2. PR-36a strategy-engine 머지 (KisYamlDslParser bean 등록)
3. `scripts/build-and-deploy.sh`
4. `psql -c "\d strategy"` → disabled_reason·last_disabled_at·eval_metric_summary 컬럼 존재
5. `psql -c "\d strategy_eval_metric"` → 신규 테이블 존재
6. `curl -X POST http://localhost:8080/api/strategy -d '{...}'` → 200 + complexity 응답
7. `curl -X POST http://localhost:8080/api/strategy/validate -d '{"entry":"ma(5) > ma(20)"}'` → 200 + complexity
8. swagger ui (`http://localhost:8080/swagger-ui/index.html`) StrategyController 한국어 4단 어노테이션 검증

## 정합 ADR
- 0022 (architect spec PR-35 implementation 명세) — Strategy DSL `.kis.yaml` superset §2-1·§2-2·§2-3
- 0047 — DSL Evaluation Sandbox §2-1-B(complexity 응답)·§2-6(Flyway V26·V27)·§2-7(env 10건)
- 0051 — Repository default fun 함정 (Repository abstract fun만 — 함정 발동 0)
- 0050 — CHANGELOG 슬롯 (slot row in-place edit `(작성: backend-core)`)
- 0046 — Onboarding (env 10건은 시크릿 아님 — 환경변수 OK)
```

### 3-5. backend-core 의존성·정합 의무

- **ADR 0022 §2-1·§2-2·§2-3** — 11 클래스 1:1 port + magicJar 확장 + import/export
- **ADR 0047 §2-1-B·§2-6·§2-7** — complexity 응답 + Flyway V26·V27 + env 10건
- **ADR 0021** — IndicatorEngine 단일 진입점 (사용자 결재 #7 정합) — domain Strategy는 IndicatorEngine 직접 의존 0 (interface만 import)
- **ADR 0050** — CHANGELOG slot row in-place edit `(작성: backend-core)` 마커 row
- **헌법 §6 ADR 0051** — `StrategyRepository`·`StrategyEvalMetricRepository` abstract fun만 의무. body 가진 fun 0건. helper는 `StrategyService` 또는 `StrategyJpaAdapter` private fun.
- **룰 5 (W2 LLM provider import 0)** — domain/api 모두 spring-ai·anthropic·openai import 0건. 자연어 변환 코드 0건.
- **룰 4 (테스트 마지막 일괄)** — 본 PR 단위 테스트 0건 OK (domain sealed class·REST·Flyway는 R3 e2e 자연스러움).
- **3 rename 정합** — KIS Indicator/Value/StrategyDefinition → IndicatorRef/LiteralValue/DslStrategyDefinition (각 KDoc에 KIS raw 이름·줄번호 명시 의무).

---

## §4. 양쪽 공유 의존 (인터페이스 동결 시점)

본 spec 머지 즉시 양쪽이 다음을 바라보고 동시 시작:

### 4-1. domain sealed class 시그니처 (LOCK)

| sealed class·data class | 위치 | 만든이 |
|---|---|---|
| `ConditionType`·`Operator`·`LogicalOperator`·`ArithmeticOperator` | domain/strategy/dsl/ | **PR-36b backend-core** |
| `DslNode`·`IndicatorRef`·`LiteralValue`·`ArithmeticExpr`·`Condition`·`CompositeCondition`·`DslStrategyDefinition` | domain/strategy/dsl/ | PR-36b |
| `Strategy`·`StrategyDefinition`·`StrategyComplexity`·`ComplexityClass`·`StrategyDisabledReason`·`StrategyEvalResult`·`Signal`·`StrategyException` | domain/strategy/ | PR-36b |
| `Universe`·`TimeFilter`·`Sizing`·`Timeframe` | domain/strategy/ | PR-36b |

**누가 만드는가**: 모두 **PR-36b backend-core**가 add-only로 신규. PR-36a strategy-engine은 **read-only import만** (`import kr.spdaewoon.magicjar.domain.strategy.dsl.*`).

### 4-2. consumer/api 책임 분배 (LOCK)

| 영역 | PR-36a strategy-engine | PR-36b backend-core |
|---|---|---|
| `consumer/.../strategy/KisYamlDslParser·JvmStrategyEvaluator·DslIdentifierMapper·PatternRecognizerStub·StrategyEvalPoolConfig·StrategyEvalMetricRecorder` | **신규 add-only** (6 파일) | (touch 0) |
| `api/build.gradle.kts` `:consumer` dep | **수정 LOCK** | (touch 0) |
| `api/.../controller/StrategyController + dto/*` | (touch 0) | **신규 add-only** (8+ 파일) |
| `api/.../application/strategy/StrategyService` | (touch 0) | **신규 add-only** |
| `api/.../persistence/strategy/*` | (touch 0) | **신규 add-only** (5 파일) |
| `api/src/main/resources/db/migration/V26·V27` | (touch 0) | **신규 add-only** |
| `api/src/main/resources/application.yml` + `consumer/.../application.yml` DSL env mapping | (touch 0) | **수정 LOCK** |
| `.env.example` DSL env 10건 | (touch 0) | **수정 LOCK** |
| `domain/.../strategy/*` 23+ 파일 | (read-only import) | **신규 add-only** |

**modify-shared 3건 (api/build.gradle.kts·application.yml·.env.example)** — 단독 LOCK으로 충돌 0.

### 4-3. DSL 식별자 → IndicatorSpec 매핑 LOCK

spec §5-5 매트릭스 정합. PR-36a sub-agent가 자체 추론 0 — 그대로 `DslIdentifierMapper.kt`에 LOCK.

### 4-4. KIS 텍스트 표현식 LOCK

spec §5-1 raw fixture 8건 + token patterns LOCK. R3 PR-37에서 KIS 10 프리셋 cross-check 의무.

### 4-5. SoT 변경 시 처리

본 spec 머지 후 변경 필요 시 sub-agent 즉시 중단 → orchestrator → architect.

---

## §5. CHANGELOG 슬롯 채번룰 (ADR 0050 정합)

본 spec PR이 sub-agent별 슬롯 row 사전 발행. spec PR 머지 시점에 `CHANGELOG.md`에 다음 row 3건 prepend 의무.

### 5-1. 슬롯 row 정확 위치 — `CHANGELOG.md` 표 헤더(3행) 바로 다음 (4번째 row 위치)

`CHANGELOG.md` 현재 구조:
- Line 1: `# magicJar CHANGELOG`
- Line 2: (빈 줄)
- Line 3: `| 날짜 | 변경 내용 | 대상 | 사유 |`
- Line 4: `|------|----------|------|------|`
- Line 5+: 데이터 행 (최신이 위)

본 spec PR이 prepend할 위치 = **Line 5 (현재 첫 데이터 행) 위**.

### 5-2. 슬롯 row 3건 형식

```markdown
| 2026-05-09 | **docs(specs): PR-35 W2 T-DSL 진입 — Strategy DSL 텍스트 파서 + 평가 엔진 + 자연어 변환 W4 이월 spec/plan + dispatch + 충돌 행렬 + KIS-first raw verification + CHANGELOG 슬롯 사전 발행** — `_workspace/specs/pr-35-w2-t-dsl-design.md` (domain/strategy/* 23+ sealed class · enum · data class — KIS 11 클래스 1:1 port + 3 rename 정합 + magicJar 확장 12 + consumer/.../strategy/ 6 파일(KisYamlDslParser 21 token + 6단 재귀 하강 · JvmStrategyEvaluator + sandbox · DslIdentifierMapper 25+ · PatternRecognizerStub · StrategyEvalPoolConfig · StrategyEvalMetricRecorder) + api/.../{controller·application·persistence}/strategy/* + Flyway V26·V27 + .env.example 10 변수), `pr-35-w2-t-dsl-plan.md` (PR-36a ~2050 LOC + PR-36b ~1620 LOC + PR-37 R3 ~1900 LOC · 시나리오 A 완전 병렬 40% 단축), `pr-35-dispatch-spec.md` (PR-36a strategy-engine 메인 + PR-36b backend-core 보조 분배 + 인터페이스 동결 + KIS-first raw fetch 의무 + 자연어 변환 W4 이월 LOCK), `pr-35-conflict-matrix.md` (**시나리오 A 적극 병렬 권장** — modify-shared 3건 단독 LOCK + add-only 100%), `pr-35-kis-dsl-raw-verification.md` (KIS parser.py 664 lines raw fetch — 11 클래스 + token patterns + parse 우선순위 + DSL 식별자 매핑 LOCK · 자체 추론 0). 신규 ADR 0건 — ADR 0022/0047/0021 implementation 명세 충분 (T-IND PR-32 패턴 정합). 자연어 변환 W4 이월 LOCK — 룰 5 + ADR 0022·0026·0047 + tracks-roadmap 5건 권위 정합. fitness gap 검증 — v1/v2 잔재 0건. (architect) | docs/spec | PR-35 W2 T-DSL 진입 spec/plan |
<!-- pr-35-slot-architect -->
| 2026-05-09 | **TBD: PR-36a T-DSL strategy-engine — KisYamlDslParser + JvmStrategyEvaluator + DslIdentifierMapper + PatternRecognizerStub + StrategyEvalPoolConfig + StrategyEvalMetricRecorder + 단위 테스트 5종** (작성: strategy-engine) — TBD | consumer/api | TBD |
<!-- pr-35-slot-strategy-engine -->
| 2026-05-09 | **TBD: PR-36b T-DSL backend-core — domain/strategy/* 23+ sealed class · enum · data class + StrategyController + StrategyService + StrategyJpaEntity·Repository·Adapter + Flyway V26·V27 + .env.example 10 변수** (작성: backend-core) — TBD | domain/api | TBD |
<!-- pr-35-slot-backend-core -->
<!-- pr-35-slot-end -->
```

### 5-3. sub-agent in-place edit 의무

PR-36a strategy-engine (sub-agent):
1. main rebase (architect spec PR 머지 후)
2. `CHANGELOG.md` 열어 `(작성: strategy-engine)` 마커 row 1줄 검색
3. **그 row 1줄만** in-place edit — TBD를 실제 산출물 사유로 교체:
   `feat(consumer+api-build): PR-36a T-DSL strategy-engine — KisYamlDslParser(KIS parser.py 664 lines 1:1 port — 21 token patterns + 6단 재귀 하강 + AST 빌더 + static 검증 visitor depth/노드/indicator/화이트리스트 + complexity 4단 LOW/MEDIUM/HIGH/CRITICAL · KDoc raw 출처 인용) + JvmStrategyEvaluator(IndicatorEngine 단일 진입점 + sandbox timeout 100ms·CPU budget 50ms·strategy-eval-pool 4 fixed 경유) + DslIdentifierMapper(DSL 식별자→IndicatorSpec 매핑 25+) + PatternRecognizerStub(T-PAT 미진입 1차 stub) + StrategyEvalPoolConfig(@Bean ThreadPoolTaskExecutor + graceful shutdown 2s) + StrategyEvalMetricRecorder + 단위 테스트 5종(parser fixture 8·evaluator basic·complexity·timeout sandbox·yaml import) (strategy-engine)`
4. 사유 column도 `TBD` → `PR-36a T-DSL strategy-engine 메인 (KIS DSL parser + AST + sandbox + 매핑 + stub)`
5. **다른 row(architect spec row·backend-core slot row) 절대 미수정** — 영역 침범 금지

PR-36b backend-core (sub-agent):
1. main rebase
2. `(작성: backend-core)` 마커 row 검색
3. TBD 교체:
   `feat(domain+api+flyway): PR-36b T-DSL backend-core — domain/strategy/* 23+ 파일(KIS 11 클래스 1:1 port + 3 rename 정합 — IndicatorRef·LiteralValue·DslStrategyDefinition · 4 enum + 7 sealed/data class DSL + 12 magicJar 확장) + StrategyController CRUD/validate/import/export/enable/getEvalMetric (Swagger 4단 + 한국어) + StrategyService(parser → static 검증 → complexity → persist + autoDisable) + StrategyJpaEntity·Repository·EvalMetric·JpaAdapter(Repository abstract fun만 — ADR 0051 함정 회피) + Flyway V26(disabled_reason·last_disabled_at·eval_metric_summary 컬럼 추가) + V27(strategy_eval_metric 신규 테이블) + .env.example 10 변수 (backend-core)`
4. **다른 row 절대 미수정**

### 5-4. 일탈·fallback 처리 (ADR 0050 §2-4 정합)

T-IND PR-32와 동일.

### 5-5. orchestrator 운영 절차

T-IND PR-32 정합 — 두 PR 모니터링 + 머지 순서 race 발생 시 두 번째가 main rebase (CHANGELOG conflict 0 보장).

---

## §6. 운영·실패 대응

### 6-1. strategy-engine PR-36a 분량 초과 인지 시

PR-36a를 다음 단위로 분할 가능 (orchestrator 사전 동의 필요):
- PR-36a-1: KisYamlDslParser + 단위 테스트 (parser fixture 8건)
- PR-36a-2: JvmStrategyEvaluator + DslIdentifierMapper + PatternRecognizerStub + StrategyEvalPoolConfig + StrategyEvalMetricRecorder + 나머지 테스트

backend-core PR-36b는 PR-36a-1 진행 중에도 동시 가능 (domain sealed class 의존만).

### 6-2. KIS `parser.py` 함수 시그니처 불확실 시

- `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/strategy_core/dsl/parser.py` 직접 raw fetch
- `_workspace/specs/pr-35-kis-dsl-raw-verification.md` §2 매트릭스 정합 검증
- architect 호출 → spec §6-2 갱신
- 자체 추론 0건 — KIS-first 정책 (헌법 §3)

### 6-3. CI fail 시

strategy-engine·backend-core 본인 영역 fail → 본인 PR에서 수정.

### 6-4. ADR 정합 누락 발견 시

직접 ADR 변경 금지 — architect 호출 → 별도 PR로 정정.

### 6-5. circular dep 발견 시 (api → consumer dep 추가)

architect 호출 → spec 갱신 (api 모듈에 KisYamlDslParser 코드 복사 fallback).

### 6-6. T-PAT 합류 시점

T-PAT 머지 후 진짜 `PatternRecognizer` bean 등록 → `PatternRecognizerStub` `@ConditionalOnMissingBean` 자동 비활성화. 별도 정정 PR 0건. JvmStrategyEvaluator 코드 변경 0건.

### 6-7. R3 PR-37 분량

R3는 frontend 메인 + backend 보조 (KIS 10 프리셋 e2e + 정확성 fixture 일괄). 별도 dispatch-spec은 PR-36 머지 후 architect가 발행. 본 PR-35에는 PR-37 outline만 §1-1 명시.

---

## §7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | PR-35 sub-agent 분배 명세 v1 발행. PR-36a (strategy-engine 메인) + PR-36b (backend-core 보조) 병렬 권장. 인터페이스 동결 — domain sealed class 23+ 신규 + DSL 식별자 매핑 LOCK + KIS 텍스트 표현식 LOCK + KIS-first raw fetch 의무. ADR 0050 적용 — CHANGELOG slot row 3건 사전 발행 의무 §5에 명시. (architect, opus 4.7 1M) |
