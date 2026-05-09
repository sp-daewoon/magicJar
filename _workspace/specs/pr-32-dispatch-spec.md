# PR-32 sub-agent 분배 명세 (T-IND R1+R2 통합 — strategy-engine + backend-core 병렬)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 정합 SoT: `_workspace/specs/pr-32-w2-t-ind-design.md` v1 + ADR 0021·0020·0050 + `_workspace/strategy/indicator-catalog.md`
- 본 문서: orchestrator가 strategy-engine·backend-core 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-32-conflict-matrix.md` 참조.
- ADR 0050 (CHANGELOG 슬롯 채번룰) 정합 — 본 spec PR이 sub-agent별 슬롯 row 사전 발행 (§5 참조).

---

## §1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-32** | architect | M (spec only) | `agent/architect/w2-t-ind-spec` | **현재 — 본 PR** |
| **PR-33a** | strategy-engine | XL | `agent/strategy-engine/pr-33-w2-t-ind-kis-source` | **PR-32 머지 후 병렬 1** |
| **PR-33b** | backend-core | M~L | `agent/backend-core/pr-33-w2-t-ind-domain-native` | **PR-32 머지 후 병렬 1** |
| **PR-34** | frontend (메인) + strategy-engine + backend-core (보조) | M | `agent/frontend/pr-34-w2-t-ind-r3` | PR-33a/b 머지 후 |

**디스패치 모드 (PR-33)**: **완전 병렬** (충돌 행렬 정합 — `pr-32-conflict-matrix.md` §결론).

**인터페이스 동결 시점**: 본 spec(`pr-32-w2-t-ind-design.md` + 본 dispatch + 충돌 행렬)이 머지되는 즉시 — strategy-engine·backend-core 양쪽이 §6-2(domain port) + §6-3(REST endpoint) + §6-4(Redis 캐시) + §5(multi-output 매트릭스) 보고 동시 시작 가능.

**최고 관리자 메모리 정합 — `feedback_orchestrator_active_parallel.md`**: architect "직렬 권장" 답습 금지. add-only 충돌은 진짜 병렬 가능. 본 PR-33은 충돌 행렬에서 modify-shared 0건 → **완전 병렬 권장**.

---

## §2. strategy-engine 위임 (PR-33a — 메인)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | strategy-engine (opus — ADR 0035 정합) |
| 브랜치 | `agent/strategy-engine/pr-33-w2-t-ind-kis-source` |
| base | `main` (PR-32 spec 머지 후) |
| 작업 author | `bot+strategy@magicjar.local` |
| 작업 author name | `magicjar-strategy-engine` |
| Task 파일 goal | `T-IND strategy-engine — KisStrategyBuilderSource 80지표 일괄 (TREND 20·MOMENTUM 20·VOLUME 12·VOLATILITY 12·OSCILLATOR 8·OTHER 8) Kotlin port + IndicatorEngineConfig (양쪽 source bean inject) + RedisIndicatorCacheAdapter (5분 분봉 / 1일 일봉 TTL) + IndicatorController REST endpoint + 단위 테스트 5종 (sma·ema·rsi·macd·bb fixture 정확성)` |
| Task 파일 deliverables | `consumer/.../application/indicator/source/KisStrategyBuilderSource.kt + kis/Indicator{Trend,Momentum,Volume,Volatility,Oscillator,Other}.kt 6 파일 (80개 calc 함수 inline private), consumer/.../application/indicator/IndicatorEngineConfig.kt @Configuration @Bean IndicatorEngine, consumer/.../infrastructure/cache/RedisIndicatorCacheAdapter.kt, api/.../controller/IndicatorController.kt + IndicatorResultDto.kt, api 모듈 IndicatorEngineConfig 별도 등록 (consumer와 동일 sources), 단위 테스트 5종 (KisStrategyBuilderSourceTest.{ma,ema,rsi,macd,bb}), CHANGELOG row in-place edit (작성: strategy-engine)` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | XL (~2500-3500 LOC — 80지표 함수 본체 평균 30줄 × 80 = 2400 + Engine·Config·Cache·Controller·Test = +1000) |

### 2-2. 작업 범위 (Done 정의)

#### A. KIS Strategy Builder source 80지표 (consumer 모듈)

- [ ] `consumer/.../application/indicator/source/KisStrategyBuilderSource.kt` — `IndicatorSource` 구현
  ```kotlin
  package kr.spdaewoon.magicjar.consumer.application.indicator.source

  import kr.spdaewoon.magicjar.domain.indicator.*
  import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
  import kr.spdaewoon.magicjar.domain.market.candle.Candle
  import org.springframework.stereotype.Component

  /**
   * KIS Strategy Builder 80지표 Kotlin port (ADR 0020 §3-1·§3-4).
   * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
   * License: MIT
   * Catalog: _workspace/strategy/indicator-catalog.md
   */
  @Component
  class KisStrategyBuilderSource : IndicatorSource {
      override val name = "kis-strategy-builder"
      override val priority = 100

      private val supportedNames: Set<String> = setOf(
          // TREND 20
          "ma", "ema", "wma", "hma", "dema", "tema", "trima", "lwma", "t3", "zlema",
          "kama", "alma", "frama", "vidya", "macd", "ichimoku_tenkan", "ichimoku_kijun",
          "supertrend", "sar", "regression_slope",
          // MOMENTUM 20
          "rsi", "stochastic_k", "stochastic_d", "stochrsi", "cci", "williams_r", "roc",
          "momentum", "apo", "ppo", "cmo", "ao", "trix", "tsi", "rvi", "dpo", "kst",
          "coppock", "schaff", "fisher",
          // VOLUME 12
          "obv", "volume_ma", "vwap", "mfi", "cmf", "cho", "ad", "adl", "force",
          "vwma", "eom", "kvo",
          // VOLATILITY 12
          "bb", "bb_width", "bb_percent", "atr", "natr", "keltner_upper", "keltner_lower",
          "donchian_upper", "donchian_lower", "chop", "mass_index", "augen",
          // OSCILLATOR 8
          "adx", "adxr", "aroon_up", "aroon_down", "vortex_plus", "vortex_minus", "ultosc", "bop",
          // OTHER 8
          "std", "variance", "disparity", "midpoint", "midprice", "logr", "pivot", "regression_intercept",
      )

      override fun supports(spec: IndicatorSpec): Boolean = spec.name in supportedNames

      override fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult =
          when (spec.name) {
              "ma" -> IndicatorTrend.calculateMa(spec, candles)
              "ema" -> IndicatorTrend.calculateEma(spec, candles)
              // ... 80개 분기
              else -> throw IndicatorException.UnsupportedSpecException(spec.name)
          }
  }
  ```

- [ ] `consumer/.../application/indicator/source/kis/IndicatorTrend.kt` — TREND 20개
  - 구현 함수: `calculateMa`, `calculateEma`, `calculateWma`, `calculateHma`, `calculateDema`, `calculateTema`, `calculateTrima`, `calculateLwma`, `calculateT3`, `calculateZlema`, `calculateKama`, `calculateAlma`, `calculateFrama`, `calculateVidya`, `calculateMacd`, `calculateIchimokuTenkan`, `calculateIchimokuKijun`, `calculateSupertrend`, `calculateSar`, `calculateRegressionSlope`
  - 각 함수 KDoc:
    ```kotlin
    /**
     * 단순이동평균(SMA) — KIS calc_ma 1:1 port.
     * Source: indicators.py Lxx-Lxx (sub-agent raw fetch 후 정확 줄번호 인용 의무 — 헌법 §3 KIS-first)
     * Multi-output keys: §5-1 매트릭스 정합 — {ma}
     */
    fun calculateMa(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult { ... }
    ```
  - return → `IndicatorResult(spec, series, source = "kis-strategy-builder")`

- [ ] `consumer/.../application/indicator/source/kis/IndicatorMomentum.kt` — MOMENTUM 20개
- [ ] `consumer/.../application/indicator/source/kis/IndicatorVolume.kt` — VOLUME 12개
- [ ] `consumer/.../application/indicator/source/kis/IndicatorVolatility.kt` — VOLATILITY 12개
- [ ] `consumer/.../application/indicator/source/kis/IndicatorOscillator.kt` — OSCILLATOR 8개
- [ ] `consumer/.../application/indicator/source/kis/IndicatorOther.kt` — OTHER 8개

**총 80개 함수**: 6 파일 분할 — 각 파일 단일 책임 (TREND·MOMENTUM·VOLUME·VOLATILITY·OSCILLATOR·OTHER) + object/class scope helper.

#### B. IndicatorEngine 등록 (consumer + api 양쪽)

- [ ] `consumer/.../application/indicator/IndicatorEngineConfig.kt`
  ```kotlin
  package kr.spdaewoon.magicjar.consumer.application.indicator

  import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
  import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
  import org.springframework.context.annotation.Bean
  import org.springframework.context.annotation.Configuration

  /**
   * IndicatorEngine bean 등록 — 모든 등록된 IndicatorSource 자동 inject (Spring autowire List).
   * 1차: NativeIndicatorSource(priority=50) + KisStrategyBuilderSource(priority=100) 2개.
   */
  @Configuration
  class IndicatorEngineConfig {
      @Bean
      fun indicatorEngine(sources: List<IndicatorSource>): IndicatorEngine = IndicatorEngine(sources)
  }
  ```

- [ ] **api 모듈에도 동일 Config 별도 발행** (consumer와 별개 — Bean scope 분리)
  - `api/.../controller/config/IndicatorEngineConfig.kt` 동일 코드
  - **사유**: master §5-2 표 — Indicator의 api 책임은 "캐시 결과 REST". REST handler가 IndicatorEngine 직접 호출하려면 api context에 bean 필요. consumer는 별도(STOMP fanout 또는 후속 T-DSL·T-SIG에서 사용).
  - **architect 결정**: 두 모듈 모두 동일 sources 자동 등록 — sources stateless라 안전. 단 KisStrategyBuilderSource·NativeIndicatorSource는 consumer 모듈에 위치하므로 api는 consumer 모듈을 build dependency로 추가 필요 (settings.gradle.kts `:api` deps에 `:consumer` 추가).
  - **대안**: api에서 별도로 두 source class 재발행 (코드 중복) — 1차 폐기. consumer dep 추가 권장.
  - **확인 의무**: 현재 `api/build.gradle.kts`에 `:consumer` dep 없으면 sub-agent가 추가 + circular dep 검증 (consumer가 api dep 가지면 안 됨 — 현재 안 가지는 것으로 알지만 sub-agent 재확인). circular 발견 시 architect 호출 (영역 침범 방지).
  - **architect 메모**: 만약 circular dep risk 발견 시 1차 안전 fallback = api 모듈에 별도 IndicatorSource 구현체 위치(consumer 코드 복사) — 1차 단순화. `_workspace/specs/pr-9-w2-design.md` §Candle JPA 같은 패턴(api/consumer 양쪽 모듈 코드 중복) 정합. **이 결정은 sub-agent가 본인 디스패치에서 빌드 검증 후 confirm**.

#### C. Redis 캐시 어댑터

- [ ] `consumer/.../infrastructure/cache/RedisIndicatorCacheAdapter.kt`
  ```kotlin
  package kr.spdaewoon.magicjar.consumer.infrastructure.cache

  import com.fasterxml.jackson.databind.ObjectMapper
  import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
  import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
  import org.springframework.data.redis.core.StringRedisTemplate
  import org.springframework.stereotype.Component
  import java.security.MessageDigest
  import java.time.Duration

  @Component
  class RedisIndicatorCacheAdapter(
      private val redis: StringRedisTemplate,
      private val mapper: ObjectMapper,
  ) {
      fun cacheKey(code: String, interval: String, spec: IndicatorSpec): String {
          val sorted = spec.params.entries.sortedBy { it.key }
              .joinToString("|") { "${it.key}=${it.value}" }
          val hash = MessageDigest.getInstance("SHA-256")
              .digest(sorted.toByteArray())
              .joinToString("") { "%02x".format(it) }
              .take(16)
          return "indicator:cache:$code:$interval:${spec.name}:$hash"
      }

      fun get(key: String): IndicatorResult? = redis.opsForValue().get(key)?.let {
          mapper.readValue(it, IndicatorResult::class.java)
      }

      fun put(key: String, result: IndicatorResult, interval: String) {
          val ttl = if (interval in setOf("1m", "5m", "15m", "30m", "60m", "90m", "1h"))
              Duration.ofSeconds(300)
          else
              Duration.ofSeconds(86400)
          redis.opsForValue().set(key, mapper.writeValueAsString(result), ttl)
      }
  }
  ```

- [ ] **api 모듈에도 별도 RedisIndicatorCacheAdapter 발행** — IndicatorController가 사용. consumer dep 가능 시 import만. 안 되면 코드 복사 (위 §B 정합).

#### D. REST controller (api 모듈)

- [ ] `api/.../controller/IndicatorController.kt` — spec §6-3 정합
  - GET endpoint 시그니처 LOCK (spec §6-3 참조)
  - Swagger 4단 어노테이션 + 한국어 설명 + example (사용자 메모리 `feedback_swagger_pattern.md` 정합)
  - candle DB read — `CandleQueryService` 의존 (PR-29c T-S1에서 만든 service 재사용)
  - error handling — `IndicatorException.UnsupportedSpecException` → 400, `InsufficientCandlesException` → 400, `CalculationFailureException` → 500

- [ ] `api/.../controller/IndicatorResultDto.kt` — JSON 직렬화 친화 DTO (spec §6-3 정합)
  - `BigDecimal` → `String` (toPlainString)
  - `Instant` → ISO 8601 String

#### E. params 파싱 (REST query → Map)

- [ ] `api/.../controller/IndicatorController.kt` 내부 private fun
  ```kotlin
  private fun parseParams(raw: String?): Map<String, Any> {
      if (raw.isNullOrBlank()) return emptyMap()
      return raw.split(",").mapNotNull {
          val (k, v) = it.split("=").let { kv -> if (kv.size != 2) return@mapNotNull null else kv[0] to kv[1] }
          k.trim() to (v.toBigDecimalOrNull() ?: v.toIntOrNull() ?: v.trim())
      }.toMap()
  }
  ```

#### F. 단위 테스트 5종 (룰 4 정합 — R3 일괄 전 정확성 동력 보장)

- [ ] `consumer/src/test/kotlin/.../KisStrategyBuilderSourceMaTest.kt` — sma fixture
- [ ] `consumer/src/test/kotlin/.../KisStrategyBuilderSourceEmaTest.kt` — ema fixture
- [ ] `consumer/src/test/kotlin/.../KisStrategyBuilderSourceRsiTest.kt` — rsi fixture
- [ ] `consumer/src/test/kotlin/.../KisStrategyBuilderSourceMacdTest.kt` — macd fixture
- [ ] `consumer/src/test/kotlin/.../KisStrategyBuilderSourceBbTest.kt` — bb fixture

각 테스트:
- 표준 input candle 30~100개 (fixture json 또는 inline)
- expected output (KIS `indicators.py` 동일 input으로 직접 실행 결과 — sub-agent가 raw fetch 후 Python 실행 가능, 또는 외부 검증 서비스 활용)
- BigDecimal compare with delta = 0.0001 (8자리 scale 중 4자리 허용)

#### G. 검증 게이트 통과

- spec §1-2 DoD #1·#2·#3·#4·#5·#6·#7·#10·#13·#15 (strategy-engine 영역)
- 헌법 §3 KIS-first raw fetch — 80개 함수 KDoc에 raw 출처 줄번호 인용 의무 (자체 추론 0)
- 헌법 §6 ADR 0051 — Repository default fun 함정 0건 (본 PR JPA Repository 신설 0)
- 룰 5 — LLM provider import 0건

### 2-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(consumer): KisStrategyBuilderSource skeleton + supportedNames 80개 (T-IND R1+R2)` | `KisStrategyBuilderSource.kt` |
| 2 | `feat(consumer): IndicatorTrend 20개 (ma·ema·...·regression_slope) — KIS indicators.py port` | `kis/IndicatorTrend.kt` |
| 3 | `feat(consumer): IndicatorMomentum 20개 (rsi·stochastic·...·fisher)` | `kis/IndicatorMomentum.kt` |
| 4 | `feat(consumer): IndicatorVolume 12개 (obv·vwap·...·kvo)` | `kis/IndicatorVolume.kt` |
| 5 | `feat(consumer): IndicatorVolatility 12개 (bb·atr·...·augen)` | `kis/IndicatorVolatility.kt` |
| 6 | `feat(consumer): IndicatorOscillator 8개 (adx·aroon·...·bop)` | `kis/IndicatorOscillator.kt` |
| 7 | `feat(consumer): IndicatorOther 8개 (std·disparity·pivot·...)` | `kis/IndicatorOther.kt` |
| 8 | `feat(consumer): IndicatorEngineConfig (consumer scope) + RedisIndicatorCacheAdapter (5m·1d TTL)` | `IndicatorEngineConfig.kt`, `RedisIndicatorCacheAdapter.kt` |
| 9 | `feat(api): IndicatorController + IndicatorResultDto + IndicatorEngineConfig (api scope)` | api/`IndicatorController.kt`, `IndicatorResultDto.kt`, api `IndicatorEngineConfig.kt` |
| 10 | `test(consumer): KIS source 5종 정확성 fixture (ma·ema·rsi·macd·bb)` | test 파일 5건 |
| 11 | `chore(changelog): PR-33a strategy-engine slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 2-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (consumer·api) — KIS Strategy Builder source 80지표 일괄 + IndicatorEngine 등록 + REST endpoint + Redis 캐시 (T-IND R1+R2)

## Summary
- PR-33a: T-IND strategy-engine — KisStrategyBuilderSource(`indicators.py` 80개 함수 Kotlin port: TREND 20·MOMENTUM 20·VOLUME 12·VOLATILITY 12·OSCILLATOR 8·OTHER 8) + IndicatorEngineConfig(consumer + api 양쪽 scope @Bean) + RedisIndicatorCacheAdapter(5m 분봉 / 1d 일봉 TTL · paramsHash SHA-256 16자리) + IndicatorController(`GET /api/indicators` + Swagger 4단 + 한국어 설명) + IndicatorResultDto(BigDecimal→String·Instant→ISO8601) + 단위 테스트 5종(ma·ema·rsi·macd·bb fixture, BigDecimal compare delta=0.0001).
- ADR 0021 §2-1~§2-5 + ADR 0020 §3-1·§3-4 정합. backend-core PR-33b가 domain port + NativeIndicatorSource 5종 동시 발행 (병렬 안전 — 충돌 행렬 §결론 modify-shared 0).
- 정합: `_workspace/specs/pr-32-w2-t-ind-design.md` §3-2·§5·§6 + `pr-32-dispatch-spec.md` §2 + `pr-32-conflict-matrix.md`.
- KIS-first raw fetch (헌법 §3): 각 80개 함수 KDoc에 `Source: indicators.py:line_NN · License: MIT` 인용 의무 — 자체 추론 0건.

## 신규 ADR
없음 — ADR 0021/0020 implementation 명세 (architect spec PR-32에서 ADR 0건 결정).

## Scope
- domain: (read-only — IndicatorSource port import만, 신규 정의는 PR-33b)
- consumer: 핵심 영역 — application/indicator/source/{KisStrategyBuilderSource,kis/IndicatorTrend·Momentum·Volume·Volatility·Oscillator·Other}.kt + IndicatorEngineConfig + infrastructure/cache/RedisIndicatorCacheAdapter
- api: 핵심 영역 — controller/IndicatorController + IndicatorResultDto + (config/IndicatorEngineConfig — api scope 별도 등록)
- batch: 변경 0
- frontend: 변경 0 (R3 PR-34에서)

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (PR-34 80지표 정확성 fixture 일괄). 본 PR에 단위 테스트 5종(ma·ema·rsi·macd·bb)만 — 정확성 검증 동력 보장.

## 사용자 검증 방법
1. PR-32 spec PR 머지 + main 위
2. PR-33b backend-core 머지 (domain port + Native 5종)
3. `scripts/build-and-deploy.sh`
4. `curl 'http://localhost:8080/api/indicators?code=005930&name=rsi&interval=1d&params=period=14&from=2024-01-01&to=2024-12-31'` → 200 OK + series + `"source":"native"` (Native priority=50 우선)
5. `curl '...name=adx&...'` → 200 OK + `"source":"kis-strategy-builder"` (Native 미지원 → KIS 라우팅)
6. `curl '...name=unknown_xyz&...'` → 400 + `"No source supports unknown_xyz"`
7. 같은 호출 2회 → 두 번째는 즉시 응답 (Redis 캐시 hit) + `redis-cli MONITOR` 확인
8. `redis-cli TTL indicator:cache:005930:1d:rsi:<hash>` → ≤86400 ≥86000

## 정합 ADR
- 0021 (architect spec PR-32 implementation 명세) — Indicator Source 추상화 §2-1~§2-5
- 0020 — KIS Strategy Builder 흡수 §3-1·§3-4 (80지표 명세 port + 라이선스 표기)
- 0051 — Repository default fun 함정 (본 PR JPA Repository 신설 0건 — 함정 발동 0)
- 0050 — CHANGELOG 슬롯 (slot row in-place edit `(작성: strategy-engine)`)
- 0019·0029 — candle storage·current candle (read-only 의존)
```

### 2-5. strategy-engine 의존성·정합 의무

- **ADR 0021 §2-1~§2-5** — IndicatorSource interface + IndicatorEngine + 1차 priority 표 + 캐시 정책
- **ADR 0020 §3-1·§3-4** — 80지표 명세 port + 라이선스 출처 표기
- **ADR 0050** — CHANGELOG slot row in-place edit `(작성: strategy-engine)` 마커 row
- **헌법 §3 KIS-first** — 각 80개 함수 KDoc에 raw 출처 줄번호 인용 의무. `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py` 실행 후 line 번호 확인 의무. 자체 추론 0건.
- **헌법 §6 ADR 0051** — 본 PR JPA Repository 신설 0건. 함정 발동 0.
- **룰 5 (AI 호출 자제)** — spring-ai·anthropic·openai import 0건
- **룰 4 (테스트 마지막 일괄)** — 5종 단위 테스트만, 80개 정확성 fixture는 PR-34 R3
- **multi-output 매트릭스** — spec §5 LOCK 정합 (single 75 + multi 5 = 80, key 매핑 자체 추론 금지)

**circular dep 처리**: api 모듈이 consumer 모듈 dep 추가 후 circular 발견 시 architect 호출 (재발행 spec 갱신 — 두 모듈 코드 복사 fallback OR domain 모듈로 source 이동). 자체 결정 금지.

---

## §3. backend-core 위임 (PR-33b — 보조)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet — ADR 0035 정합) |
| 브랜치 | `agent/backend-core/pr-33-w2-t-ind-domain-native` |
| base | `main` (PR-32 spec 머지 후) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `T-IND backend-core — domain/indicator/* port 정의 (IndicatorSource·IndicatorSpec·IndicatorResult·IndicatorPoint·IndicatorEngine·IndicatorException·IndicatorCatalog) + NativeIndicatorSource 5종(SMA·EMA·RSI·MACD·BB) Kotlin 자체 구현 + (옵션) CrossCheckEngine + 단위 테스트 5종 (Native 5종 정확성 fixture)` |
| Task 파일 deliverables | `domain/.../indicator/{IndicatorSpec,IndicatorResult,IndicatorPoint,IndicatorCatalog,IndicatorEngine,IndicatorException}.kt 6 파일, domain/.../indicator/port/IndicatorSource.kt, consumer/.../application/indicator/source/NativeIndicatorSource.kt + native/{NativeMa,NativeEma,NativeRsi,NativeMacd,NativeBollingerBands}.kt 5 파일, (옵션) consumer/.../application/indicator/CrossCheckEngine.kt, 단위 테스트 5종 (Native 정확성 fixture), CHANGELOG row in-place edit (작성: backend-core)` |
| 머지 정책 | rebase |
| 분량 | M~L (~700-1000 LOC — domain port 7파일 평균 30줄 + Native 5종 평균 60줄 + Engine·Exception·Catalog + test) |

### 3-2. 작업 범위 (Done 정의)

#### A. domain/indicator/* port + 도메인 모델 (add-only)

- [ ] `domain/.../indicator/port/IndicatorSource.kt` — spec §6-2 LOCK
  ```kotlin
  package kr.spdaewoon.magicjar.domain.indicator.port

  import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
  import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
  import kr.spdaewoon.magicjar.domain.market.candle.Candle

  interface IndicatorSource {
      val name: String
      val priority: Int
      fun supports(spec: IndicatorSpec): Boolean
      fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult
  }
  ```

- [ ] `domain/.../indicator/IndicatorSpec.kt` — spec §6-2 LOCK
- [ ] `domain/.../indicator/IndicatorResult.kt` — spec §6-2 LOCK
- [ ] `domain/.../indicator/IndicatorPoint.kt` — spec §6-2 LOCK
- [ ] `domain/.../indicator/IndicatorEngine.kt` — spec §6-2 LOCK
  ```kotlin
  class IndicatorEngine(private val sources: List<IndicatorSource>) {
      fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult {
          val source = sources
              .filter { it.supports(spec) }
              .minByOrNull { it.priority }
              ?: throw IndicatorException.UnsupportedSpecException(spec.name)
          return source.calculate(spec, candles)
      }
  }
  ```

- [ ] `domain/.../indicator/IndicatorException.kt` — sealed class spec §6-2 LOCK
- [ ] `domain/.../indicator/IndicatorCatalog.kt` — 80 spec metadata 등록 (read-only data)
  ```kotlin
  package kr.spdaewoon.magicjar.domain.indicator

  data class IndicatorMetadata(
      val id: String,                // "rsi"
      val displayName: String,       // "RSI"
      val category: IndicatorCategory,  // TREND·MOMENTUM·VOLUME·VOLATILITY·OSCILLATOR·OTHER
      val defaultParams: Map<String, Any>,  // {"period": 14}
      val outputKeys: List<String>,  // ["rsi"] or ["macd", "signal", "histogram"]
  )

  enum class IndicatorCategory { TREND, MOMENTUM, VOLUME, VOLATILITY, OSCILLATOR, OTHER }

  object IndicatorCatalog {
      val ALL: List<IndicatorMetadata> = listOf(
          IndicatorMetadata("ma", "이동평균(MA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("ma")),
          IndicatorMetadata("ema", "지수이동평균(EMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("ema")),
          // ... 80개 등록 — `indicator-catalog.md` + spec §5 multi-output 매트릭스 정합
      )

      fun findById(id: String): IndicatorMetadata? = ALL.firstOrNull { it.id == id }
      fun byCategory(category: IndicatorCategory): List<IndicatorMetadata> = ALL.filter { it.category == category }
  }
  ```
  - **사유**: T-SB(Strategy Builder UI)·T-DSL·frontend IndicatorPicker가 모두 80지표 메타데이터 조회 필요. domain 위치가 정합 (Spring 의존 0).

#### B. NativeIndicatorSource 5종 (consumer 모듈)

- [ ] `consumer/.../application/indicator/source/NativeIndicatorSource.kt` — spec §3-2 정합
  ```kotlin
  package kr.spdaewoon.magicjar.consumer.application.indicator.source

  import kr.spdaewoon.magicjar.domain.indicator.*
  import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
  import kr.spdaewoon.magicjar.domain.market.candle.Candle
  import org.springframework.stereotype.Component

  /**
   * magicJar 자체 5종 indicator (ADR 0021 §2-3).
   * priority=50 — KIS(100)보다 우선 사용. cross-check 검증 후 신뢰성 확보.
   */
  @Component
  class NativeIndicatorSource : IndicatorSource {
      override val name = "native"
      override val priority = 50

      private val supportedNames: Set<String> = setOf("ma", "ema", "rsi", "macd", "bb")

      override fun supports(spec: IndicatorSpec): Boolean = spec.name in supportedNames

      override fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult =
          when (spec.name) {
              "ma" -> NativeMa.calculate(spec, candles)
              "ema" -> NativeEma.calculate(spec, candles)
              "rsi" -> NativeRsi.calculate(spec, candles)
              "macd" -> NativeMacd.calculate(spec, candles)
              "bb" -> NativeBollingerBands.calculate(spec, candles)
              else -> throw IndicatorException.UnsupportedSpecException(spec.name)
          }
  }
  ```

- [ ] `consumer/.../application/indicator/source/native/NativeMa.kt` — SMA 자체 구현
- [ ] `consumer/.../application/indicator/source/native/NativeEma.kt` — EMA 자체 구현
- [ ] `consumer/.../application/indicator/source/native/NativeRsi.kt` — RSI 자체 구현 (Wilder smoothing)
- [ ] `consumer/.../application/indicator/source/native/NativeMacd.kt` — MACD 자체 구현 (multi-output: macd, signal, histogram — spec §5-2 정합)
- [ ] `consumer/.../application/indicator/source/native/NativeBollingerBands.kt` — BB 자체 구현 (multi-output: upper, middle, lower)

각 함수:
- candles.size < required period → `IndicatorException.InsufficientCandlesException` throw
- `BigDecimal` 정밀도 + `RoundingMode.HALF_EVEN` + `scale = 8`
- KDoc에 정확성 검증 reference (KIS 동일 input 비교 결과 첨부 — drift 0.001% 미만 명시)

#### C. (옵션) CrossCheckEngine

- [ ] `consumer/.../application/indicator/CrossCheckEngine.kt` — ADR 0021 §2-6
  ```kotlin
  @Component
  @ConditionalOnProperty(name = ["magicjar.indicator.crosscheck"], havingValue = "true")
  class CrossCheckEngine(...) {
      // 5종 (NativeMa·Ema·Rsi·Macd·Bb) 호출 시 KIS source도 동시 호출 → drift 0.001% 이상 ALERT
  }
  ```
  - 1차는 옵션 — `MAGICJAR_INDICATOR_CROSSCHECK=true` 시만 활성. default off (운영 비용)
  - PR-33b 분량 부담 시 본 항목 PR-34 R3로 미룸 (orchestrator 결정)

#### D. 단위 테스트 5종 (룰 4 정합)

- [ ] `consumer/src/test/kotlin/.../NativeMaTest.kt` — sma fixture (PR-33a `KisStrategyBuilderSourceMaTest`와 동일 input — drift 0.001% 미만 검증)
- [ ] `consumer/src/test/kotlin/.../NativeEmaTest.kt`
- [ ] `consumer/src/test/kotlin/.../NativeRsiTest.kt`
- [ ] `consumer/src/test/kotlin/.../NativeMacdTest.kt` (multi-output 검증 — values keys 정합)
- [ ] `consumer/src/test/kotlin/.../NativeBollingerBandsTest.kt` (multi-output 검증)

#### E. 검증 게이트 통과

- spec §1-2 DoD #3·#13·#15 (backend-core 영역 — Native priority=50 routing + LLM import 0 + Repository 함정 0)
- 헌법 §6 ADR 0051 — JPA Repository 신설 0건 (본 PR domain port + Spring @Component만)
- 룰 5 — LLM provider import 0건

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): IndicatorSource port + IndicatorSpec/Result/Point + IndicatorEngine + IndicatorException (T-IND R1)` | `domain/.../indicator/{IndicatorSpec,Result,Point,Engine,Exception}.kt` + `port/IndicatorSource.kt` |
| 2 | `feat(domain): IndicatorCatalog 80 metadata 등록 (T-SB·T-DSL·frontend picker 의존)` | `domain/.../indicator/IndicatorCatalog.kt` |
| 3 | `feat(consumer): NativeIndicatorSource skeleton + supportedNames 5종 (T-IND R2)` | `NativeIndicatorSource.kt` |
| 4 | `feat(consumer): NativeMa·Ema·Rsi (single-output 3종)` | `native/NativeMa.kt`·`NativeEma.kt`·`NativeRsi.kt` |
| 5 | `feat(consumer): NativeMacd·BollingerBands (multi-output 2종 — spec §5-2 매핑)` | `native/NativeMacd.kt`·`NativeBollingerBands.kt` |
| 6 | `feat(consumer): CrossCheckEngine (옵션 — MAGICJAR_INDICATOR_CROSSCHECK=true)` | `CrossCheckEngine.kt` (선택) |
| 7 | `test(consumer): Native 5종 정확성 fixture (KIS source와 drift 0.001%)` | test 파일 5건 |
| 8 | `chore(changelog): PR-33b backend-core slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 3-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (domain·consumer) — Indicator domain port + Native source 5종 + CrossCheck 옵션 (T-IND R1+R2)

## Summary
- PR-33b: T-IND backend-core — domain/indicator/* port 7개(IndicatorSource·IndicatorSpec·IndicatorResult·IndicatorPoint·IndicatorEngine·IndicatorException·IndicatorCatalog) + NativeIndicatorSource(priority=50, 5종 — sma·ema·rsi·macd·bb) + 5 helper 클래스 + (옵션) CrossCheckEngine(@ConditionalOnProperty default off) + 단위 테스트 5종 (Native 정확성 — KIS source와 drift 0.001% 미만 검증).
- ADR 0021 §2-1·§2-2·§2-3·§2-6 정합. strategy-engine PR-33a가 KisStrategyBuilderSource 80지표 + IndicatorEngineConfig + REST 동시 발행 (병렬 안전 — 충돌 행렬 §결론 modify-shared 0).
- 정합: `_workspace/specs/pr-32-w2-t-ind-design.md` §3-1·§5·§6-2 + `pr-32-dispatch-spec.md` §3 + `pr-32-conflict-matrix.md`.

## 신규 ADR
없음 — ADR 0021 implementation 명세 (architect spec PR-32에서 ADR 0건 결정).

## Scope
- domain: 핵심 영역 — indicator/* 7파일 add-only (port·spec·result·point·engine·exception·catalog)
- consumer: 핵심 영역 — application/indicator/source/{NativeIndicatorSource,native/Native{Ma,Ema,Rsi,Macd,BollingerBands}}.kt + (옵션) CrossCheckEngine
- api·batch·frontend: 변경 0

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (PR-34 80지표 정확성 fixture 일괄). 본 PR에 단위 테스트 5종(Native 5종)만 — 정확성 검증 동력 보장.

## 사용자 검증 방법
1. PR-32 spec PR 머지 + main 위
2. PR-33a strategy-engine 머지 (KIS source 80개 + REST endpoint)
3. `scripts/build-and-deploy.sh`
4. `curl 'http://localhost:8080/api/indicators?code=005930&name=rsi&interval=1d&params=period=14&from=2024-01-01&to=2024-12-31'`
5. response `"source":"native"` (Native priority=50 우선 라우팅 검증)
6. `curl '...name=ma&...'` → `"source":"native"` (5종 모두 Native 라우팅)
7. `curl '...name=adx&...'` → `"source":"kis-strategy-builder"` (Native 미지원 → KIS fallback)
8. `./gradlew :consumer:test --tests "*Native*Test"` GREEN

## 정합 ADR
- 0021 (architect spec PR-32 implementation 명세) — Indicator Source 추상화 §2-1·§2-2·§2-3·§2-6
- 0051 — Repository default fun 함정 (본 PR JPA Repository 신설 0 — 함정 발동 0)
- 0050 — CHANGELOG 슬롯 (slot row in-place edit `(작성: backend-core)`)
```

### 3-5. backend-core 의존성·정합 의무

- **ADR 0021 §2-1·§2-2** — IndicatorSource interface + IndicatorEngine LOCK
- **ADR 0021 §2-3** — Native priority=50 < KIS priority=100 (Native 우선 LOCK)
- **ADR 0021 §2-6** — CrossCheckEngine (옵션)
- **ADR 0050** — CHANGELOG slot row in-place edit `(작성: backend-core)` 마커 row
- **헌법 §6 ADR 0051** — 본 PR JPA Repository 신설 0건. 함정 발동 0.
- **룰 5 (AI 호출 자제)** — spring-ai·anthropic·openai import 0건
- **룰 4 (테스트 마지막 일괄)** — Native 5종 단위 테스트만, 80개 정확성 fixture는 PR-34 R3
- **multi-output 매트릭스** — spec §5-2 LOCK (NativeMacd·NativeBollingerBands는 단일 IndicatorPoint.values map에 macd/signal/histogram 또는 upper/middle/lower 3 key 일괄 inject)

---

## §4. 양쪽 공유 의존 (인터페이스 동결 시점)

본 spec 머지 즉시 양쪽이 다음을 바라보고 동시 시작:

### 4-1. domain port 시그니처 (LOCK)

| port | 시그니처 | 위치 | 만든이 |
|---|---|---|---|
| `IndicatorSource` | `name`·`priority`·`supports(spec)`·`calculate(spec, candles)` | domain | **PR-33b backend-core** |
| `IndicatorSpec` | `name: String`·`params: Map<String, Any>` | domain | PR-33b |
| `IndicatorResult` | `spec`·`series: List<IndicatorPoint>`·`source: String` | domain | PR-33b |
| `IndicatorPoint` | `time: Instant`·`values: Map<String, BigDecimal>` | domain | PR-33b |
| `IndicatorEngine` | `class(sources)` + `calculate(...)` | domain | PR-33b |
| `IndicatorException` | sealed (UnsupportedSpec·InsufficientCandles·CalculationFailure) | domain | PR-33b |
| `IndicatorCatalog` | object — 80 metadata 등록 | domain | PR-33b |

**누가 만드는가**: 모두 **PR-33b backend-core**가 add-only로 신규. PR-33a strategy-engine은 **read-only import만** (`import kr.spdaewoon.magicjar.domain.indicator.*`).

### 4-2. consumer/api 책임 분배 (LOCK)

| 영역 | PR-33a strategy-engine | PR-33b backend-core |
|---|---|---|
| `consumer/.../indicator/IndicatorEngineConfig.kt` | **신규 add-only LOCK** | (touch 0) |
| `consumer/.../indicator/source/KisStrategyBuilderSource.kt` + `kis/*` | **신규 add-only** | (touch 0) |
| `consumer/.../indicator/source/NativeIndicatorSource.kt` + `native/*` | (touch 0) | **신규 add-only** |
| `consumer/.../infrastructure/cache/RedisIndicatorCacheAdapter.kt` | **신규 add-only** | (touch 0) |
| `api/.../controller/IndicatorController.kt` | **신규 add-only** | (touch 0) |
| `api/.../controller/IndicatorResultDto.kt` | **신규 add-only** | (touch 0) |
| `api/.../controller/config/IndicatorEngineConfig.kt` | **신규 add-only LOCK** | (touch 0) |
| `domain/.../indicator/*` 7파일 | (read-only import만) | **신규 add-only** |

**modify-shared 0건** — PR-33a·PR-33b 완전 add-only.

### 4-3. Redis 캐시 키 LOCK

spec §6-4 매트릭스 정합. 양쪽 source가 같은 paramsHash 결과 (SHA-256 16자리 prefix LOCK).

### 4-4. multi-output 매트릭스 LOCK

spec §5-1 (single 75) + §5-2 (multi 5) 정합. 양쪽 source 동일 keys 사용 의무 (cross-check 정합 보장).

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
| 2026-05-09 | **docs(specs): PR-32 W2 T-IND 진입 — 80지표 + Source 추상화 spec/plan + dispatch + 충돌 행렬 + CHANGELOG 슬롯 사전 발행** — `_workspace/specs/pr-32-w2-t-ind-design.md` (domain/indicator/* 7파일 + KisStrategyBuilderSource 80지표 분류 6 파일 + NativeIndicatorSource 5종 + IndicatorEngineConfig consumer/api 양쪽 + RedisIndicatorCacheAdapter 5m·1d TTL + IndicatorController REST endpoint + multi-output 매트릭스 LOCK single 75 + multi 5 = 80), `pr-32-dispatch-spec.md` (PR-33a strategy-engine 메인 + PR-33b backend-core 보조 분배 + 인터페이스 동결 + KIS-first raw fetch 의무), `pr-32-conflict-matrix.md` (**시나리오 A 적극 병렬 권장** — modify-shared 0건 + add-only 100%). 신규 ADR 0건 — ADR 0021/0020 implementation 명세 충분. fitness gap 검증 — v1/v2 잔재 0건 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합). (architect) | docs/spec | PR-32 W2 T-IND 진입 spec/plan |
<!-- pr-32-slot-architect -->
| 2026-05-09 | **TBD: PR-33a T-IND strategy-engine — KisStrategyBuilderSource 80지표 + IndicatorEngineConfig + RedisIndicatorCacheAdapter + IndicatorController** (작성: strategy-engine) — TBD | consumer/api | TBD |
<!-- pr-32-slot-strategy-engine -->
| 2026-05-09 | **TBD: PR-33b T-IND backend-core — domain/indicator/* port 7개 + NativeIndicatorSource 5종 + (옵션) CrossCheckEngine** (작성: backend-core) — TBD | domain/consumer | TBD |
<!-- pr-32-slot-backend-core -->
<!-- pr-32-slot-end -->
```

### 5-3. sub-agent in-place edit 의무

PR-33a strategy-engine (sub-agent):
1. main rebase (architect spec PR 머지 후)
2. `CHANGELOG.md` 열어 `(작성: strategy-engine)` 마커 row 1줄 검색
3. **그 row 1줄만** in-place edit — `TBD: PR-33a T-IND strategy-engine ... (작성: strategy-engine) — TBD` → `feat(consumer+api): PR-33a T-IND strategy-engine — KisStrategyBuilderSource 80지표 (TREND 20·MOMENTUM 20·VOLUME 12·VOLATILITY 12·OSCILLATOR 8·OTHER 8 · KDoc raw 출처 인용) + IndicatorEngineConfig (consumer + api 양쪽 scope) + RedisIndicatorCacheAdapter (5m 분봉 / 1d 일봉 TTL · paramsHash SHA-256 16자리) + IndicatorController (GET /api/indicators · Swagger 4단) + 단위 테스트 5종 (strategy-engine)` (실제 산출물 한 줄 사유로 교체)
4. 사유 column도 `TBD` → `PR-33a T-IND strategy-engine 메인 (KIS 80지표 + REST + 캐시)`
5. **다른 row(architect spec row·backend-core slot row) 절대 미수정** — 영역 침범 금지

PR-33b backend-core (sub-agent):
1. main rebase
2. `(작성: backend-core)` 마커 row 검색
3. `TBD: PR-33b T-IND backend-core ... (작성: backend-core) — TBD` → `feat(domain+consumer): PR-33b T-IND backend-core — domain/indicator/* port 7파일(IndicatorSource·IndicatorSpec·IndicatorResult·IndicatorPoint·IndicatorEngine·IndicatorException·IndicatorCatalog 80 metadata) + NativeIndicatorSource(priority=50, 5종 — sma·ema·rsi·macd·bb) + 5 helper + (옵션) CrossCheckEngine + 단위 테스트 5종 (Native drift 0.001% 미만 검증) (backend-core)` (실제 산출물 사유로 교체)
4. **다른 row 절대 미수정**

### 5-4. 일탈·fallback 처리 (ADR 0050 §2-4 정합)

sub-agent가 spec PR 슬롯 row 외 추가 변경 발생 시:
- 별도 정정 commit으로 본인 row 갱신 OR 새 row append (add-only) — 본인 책임

병렬 sub-agent 머지 순서:
- backend-core 먼저 머지 → backend-core row 채워짐 → strategy-engine slot row 그대로 + strategy-engine fillout 후 머지 → CHANGELOG 충돌 0
- 동시 push 시점이라도 ADR 0050 in-place edit이라 git이 다른 line 변경으로 인식 → 충돌 0

### 5-5. orchestrator 운영 절차

orchestrator가 strategy-engine·backend-core 디스패치 시 다음 정확 위치 정보 전달:

> `CHANGELOG.md` Line ~5 (architect spec PR 머지 후 정확 line 확인 의무)에 본인 슬롯 row 1줄이 `TBD ... (작성: strategy-engine)` 또는 `(작성: backend-core)` 형식으로 있다. 본인 row만 in-place edit. 형식 예시 = dispatch-spec §5-3.

---

## §6. 운영·실패 대응

### 6-1. strategy-engine이 PR-33a 분량 초과 인지 시

PR-33a를 다음 단위로 분할 가능 (orchestrator 사전 동의 필요):
- PR-33a-1: KisStrategyBuilderSource 골격 + IndicatorTrend 20개 + IndicatorMomentum 20개 (40개)
- PR-33a-2: IndicatorVolume·Volatility·Oscillator·Other 40개 + IndicatorEngineConfig + RedisIndicatorCacheAdapter
- PR-33a-3: IndicatorController + 단위 테스트 5종

backend-core PR-33b는 PR-33a-1 진행 중에도 동시 가능 (domain port 의존만).

### 6-2. KIS `indicators.py` 함수 시그니처 불확실 시

- `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py` 로 직접 raw fetch
- `mcp__context7` 또는 `mcp__kis-code-assistant` 활용 — 단 KIS Strategy Builder는 KIS API 직접 호출이 아니라 KIS open-trading-api 레포 코드라 raw fetch 우선
- architect 호출 → spec §5 매트릭스 갱신
- 자체 추론 0건 — KIS-first 정책 (헌법 §3)

### 6-3. CI fail 시

strategy-engine·backend-core 본인 영역 fail → 본인 PR에서 수정.

### 6-4. ADR 정합 누락 발견 시

직접 ADR 변경 금지 — architect 호출 → 별도 PR로 정정.

### 6-5. circular dep 발견 시 (api → consumer dep 추가)

architect 호출 → spec 갱신 (api 모듈에 source 코드 복사 OR domain 모듈로 source 이동 결정).

### 6-6. R3 PR-34 분량

R3는 frontend 메인 + backend 보조 (80개 정확성 fixture 일괄). 별도 dispatch-spec은 PR-33 머지 후 architect가 발행. 본 PR-32에는 PR-34 outline만 §1-1 명시.

---

## §7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | PR-32 sub-agent 분배 명세 v1 발행. PR-33a (strategy-engine 메인) + PR-33b (backend-core 보조) 병렬 권장. 인터페이스 동결 — domain port 7개 신규(IndicatorSource·IndicatorSpec·IndicatorResult·IndicatorPoint·IndicatorEngine·IndicatorException·IndicatorCatalog) + Redis 캐시 키 LOCK + multi-output 매트릭스 §5(single 75 + multi 5 = 80) LOCK + KIS-first raw fetch 의무. ADR 0050 적용 — CHANGELOG slot row 3건 사전 발행 의무 §5에 명시. (architect, opus 4.7 1M) |
