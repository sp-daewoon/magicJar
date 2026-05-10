# PR-38 sub-agent 분배 명세 (T-PAT R1+R2 통합 — strategy-engine + backend-core 병렬)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-10
- 정합 SoT: `_workspace/specs/pr-38-w2-t-pat-design.md` v1 + `_workspace/specs/pr-38-kis-pattern-raw-verification.md` v1 + ADR 0020·0022·0047·0050 + `_workspace/strategy/pattern-catalog.md`
- 본 문서: orchestrator가 strategy-engine·backend-core 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-38-conflict-matrix.md` 참조.
- ADR 0050 (CHANGELOG 슬롯 채번룰) 정합 — 본 spec PR이 sub-agent별 슬롯 row 사전 발행 (§5 참조).

---

## §1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-38** | architect | M (spec only) | `agent/architect/w2-t-pat-spec` | **현재 — 본 PR** |
| **PR-39a** | strategy-engine | L (~2590 LOC) | `agent/strategy-engine/pr-39-w2-t-pat-detectors` | **PR-38 머지 후 병렬 1** |
| **PR-39b** | backend-core | M (~620 LOC) | `agent/backend-core/pr-39-w2-t-pat-domain-rest` | **PR-38 머지 후 병렬 1** |
| **PR-40** | frontend (메인) + strategy-engine + backend-core (보조) | M~L (~1480 LOC) | `agent/frontend/pr-40-w2-t-pat-r3` | PR-39a/b 머지 후 |

**디스패치 모드 (PR-39)**: **완전 병렬** (충돌 행렬 정합 — `pr-38-conflict-matrix.md` §결론).

**인터페이스 동결 시점**: 본 spec(`pr-38-w2-t-pat-design.md` + `pr-38-w2-t-pat-plan.md` + 본 dispatch + 충돌 행렬 + raw verification)이 머지되는 즉시 — strategy-engine·backend-core 양쪽이 §6-3(domain port) + §6-4(REST endpoint) + §6-5(Redis 캐시) + §5(stub 1줄 교체) + raw verification §2(57 매핑) 보고 동시 시작 가능.

**최고 관리자 메모리 정합 — `feedback_orchestrator_active_parallel.md`**: architect "직렬 권장" 답습 금지. add-only 충돌은 진짜 병렬 가능. 본 PR-39은 충돌 행렬에서 modify-shared 1건(PatternRecognizerStub — backend-core 단독 LOCK) + add-only 100% → **완전 병렬 권장**.

---

## §2. strategy-engine 위임 (PR-39a — 메인)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | strategy-engine (opus — ADR 0035 정합) |
| 브랜치 | `agent/strategy-engine/pr-39-w2-t-pat-detectors` |
| base | `main` (PR-38 spec 머지 후) |
| 작업 author | `bot+strategy@magicjar.local` |
| 작업 author name | `magicjar-strategy-engine` |
| Task 파일 goal | `T-PAT strategy-engine — JvmPatternRecognizer + 57 detector(R1 active 10 + R2 stub 47) + KIS 7 helper(CandleMath) + SimpleTripleHelper(KIS L562 _simple_triple) + ConsecutiveDirectionCalculator(magicJar 자체) + api PatternRecognizerConfig bean 등록 + 단위 테스트 5종(R1 fixture: doji·hammer·engulfing·morning_star·three_white_soldiers — KIS raw 룰 1:1 정합)` |
| Task 파일 deliverables | `consumer/.../pattern/JvmPatternRecognizer.kt + util/CandleMath.kt + helper/SimpleTripleHelper.kt + helper/ConsecutiveDirectionCalculator.kt + detector/single/{14 파일}.kt + detector/double/{16 파일}.kt + detector/triple/{27 파일}.kt + api/.../config/PatternRecognizerConfig.kt + 5 단위 테스트(PatternFixtureTest·JvmPatternRecognizerTest·PatternRegistryTest), CHANGELOG row in-place edit (작성: strategy-engine)` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | L (~2590 LOC — 57 detector 평균 30~50줄 + helper + Recognizer + Test) |

### 2-2. 작업 범위 (Done 정의)

#### A. KIS-first raw fetch (의무)

```bash
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/candlestick.py > /tmp/kis-candlestick.py
wc -l /tmp/kis-candlestick.py        # 약 755 라인
grep -c "^def _" /tmp/kis-candlestick.py    # 79 함수
```

raw verification §2 매트릭스 + §3 helper + §4 진입점 + §5 R1 10건 + §7 alias delegation 정독 의무.

#### B. CandleMath util (consumer 모듈) — KIS 7 helper

- [ ] `consumer/.../pattern/util/CandleMath.kt` — object
  ```kotlin
  package kr.spdaewoon.magicjar.consumer.application.pattern.util

  import kr.spdaewoon.magicjar.domain.market.candle.Candle
  import java.math.BigDecimal
  import java.math.RoundingMode

  /**
   * KIS Strategy Builder candlestick.py 7 helper Kotlin port (ADR 0020 §3-1·§3-4).
   * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py L14-L36 + L71
   * License: MIT
   */
  object CandleMath {
      /** _body(o, c) (raw L14) */
      fun body(open: BigDecimal, close: BigDecimal): BigDecimal = (close - open).abs()

      /** _upper_shadow(h, o, c) (raw L18) */
      fun upperShadow(high: BigDecimal, open: BigDecimal, close: BigDecimal): BigDecimal =
          high - maxOf(open, close)

      /** _lower_shadow(o, c, l) (raw L22) */
      fun lowerShadow(open: BigDecimal, close: BigDecimal, low: BigDecimal): BigDecimal =
          minOf(open, close) - low

      /** _range(h, l) (raw L26) */
      fun range(high: BigDecimal, low: BigDecimal): BigDecimal = high - low

      /** _is_bullish(o, c) (raw L30) */
      fun isBullish(open: BigDecimal, close: BigDecimal): Boolean = close > open

      /** _is_bearish(o, c) (raw L34) */
      fun isBearish(open: BigDecimal, close: BigDecimal): Boolean = close < open

      /**
       * _avg_body(df, n=10) (raw L71)
       * @return BigDecimal scale=8 RoundingMode.HALF_EVEN
       */
      fun avgBody(candles: List<Candle>, n: Int = 10): BigDecimal {
          val tail = candles.takeLast(n)
          if (tail.isEmpty()) return BigDecimal.ZERO
          val sum = tail.fold(BigDecimal.ZERO) { acc, c -> acc + body(c.open, c.close) }
          return sum.divide(BigDecimal(tail.size), 8, RoundingMode.HALF_EVEN)
      }
  }
  ```

#### C. SimpleTripleHelper (consumer 모듈) — KIS L562 _simple_triple

- [ ] `consumer/.../pattern/helper/SimpleTripleHelper.kt`
  ```kotlin
  /**
   * KIS Strategy Builder candlestick.py _simple_triple Kotlin port.
   * Source: candlestick.py L562 _simple_triple(df, direction)
   * License: MIT
   *
   * 7 detector가 공유: three_stars_in_south(L576) · advance_block(L577) · stalled_pattern(L578)
   *                  · unique_three_river(L617) · breakaway(L618) · mat_hold(L619) · ladder_bottom(L622)
   *                  · concealing_baby_swallow(L623)
   * (rising_three_methods·falling_three_methods·deliberation는 magicJar 카탈로그 제외 — Lean 미지원)
   *
   * @param direction "bullish" | "bearish"
   * @return 1 if 3양봉 + close 단조 증가 (bullish), -1 if 3음봉 + close 단조 감소 (bearish), 0 미감지
   */
  object SimpleTripleHelper {
      fun simpleTriple(candles: List<Candle>, direction: String): Int {
          if (candles.size < 3) return 0
          val last3 = candles.takeLast(3)
          // KIS 본문 raw L562-L575 정독 후 1:1 port
          // ... (실제 구현은 raw 정독 의무)
          return 0
      }
  }
  ```

#### D. ConsecutiveDirectionCalculator (consumer 모듈) — magicJar 자체

- [ ] `consumer/.../pattern/helper/ConsecutiveDirectionCalculator.kt`
  ```kotlin
  /**
   * magicJar own — KIS 미존재.
   * DSL `consecutive("up"|"down") >= N` 호출 호환 (T-DSL stub 시그니처 정합).
   *
   * @param direction "up" (양봉 close > open) | "down" (음봉 close < open)
   * @return 마지막 캔들부터 직전으로 같은 방향 연속 횟수
   */
  object ConsecutiveDirectionCalculator {
      fun consecutive(direction: String, candles: List<Candle>): Int {
          if (candles.isEmpty()) return 0
          val isMatch: (Candle) -> Boolean = when (direction) {
              "up" -> { c -> c.close > c.open }
              "down" -> { c -> c.close < c.open }
              else -> return 0
          }
          var count = 0
          for (i in candles.indices.reversed()) {
              if (isMatch(candles[i])) count++ else break
          }
          return count
      }
  }
  ```

#### E. 57 detector — Single (14) + Double (16) + Triple+ (27)

- [ ] `consumer/.../pattern/detector/single/DojiDetector.kt` — R1 active (raw L81)
  ```kotlin
  /**
   * Doji — KIS _doji 1:1 port (R1 active).
   * Source: candlestick.py L81-L87
   * License: MIT
   * Rule: |close-open|/range < 0.1 → BULL (KIS +1)
   */
  @Component
  class DojiDetector : PatternDetector {
      override val id = "doji"
      override fun detect(candles: List<Candle>): DetectorResult {
          val c = candles.last()
          val r = CandleMath.range(c.high, c.low)
          if (r == BigDecimal.ZERO) return DetectorResult(Direction.NEUTRAL)
          val bodyRatio = CandleMath.body(c.open, c.close).divide(r, 8, RoundingMode.HALF_EVEN)
          return if (bodyRatio < BigDecimal("0.1")) {
              DetectorResult(Direction.BULL, params = mapOf("bodyRatio" to bodyRatio))
          } else {
              DetectorResult(Direction.NEUTRAL)
          }
      }
  }
  ```

- [ ] `consumer/.../pattern/detector/single/HammerDetector.kt` — R1 active (raw L119)
- [ ] `consumer/.../pattern/detector/single/HangingManDetector.kt` — R1 active (raw L132 + 직전 5봉 상승추세 룰)
- [ ] `consumer/.../pattern/detector/single/InvertedHammerDetector.kt` — R1 active (raw L145)
- [ ] `consumer/.../pattern/detector/single/ShootingStarDetector.kt` — R1 active (raw L157 + 직전 5봉 상승추세 룰)
- [ ] `consumer/.../pattern/detector/single/{DragonflyDoji,GravestoneDoji,LongLeggedDoji,Marubozu,ClosingMarubozu,SpinningTop,BeltHold,HighWave,RickshawMan}Detector.kt` — R2 stub (9건)

  R2 stub 패턴:
  ```kotlin
  /**
   * Tristar — KIS _tristar Kotlin port (R2 stub).
   * Source: candlestick.py L636 (R2 사이클 보강 — 활성 시 KIS 본문 port)
   */
  @Component
  class TristarDetector : PatternDetector {
      override val id = "tristar"
      override fun detect(candles: List<Candle>): DetectorResult {
          // R2 사이클 보강 — KIS L636 _tristar 본문 port 후 활성화
          return DetectorResult(direction = Direction.NEUTRAL)
      }
  }
  ```

- [ ] `consumer/.../pattern/detector/double/EngulfingDetector.kt` — R1 active (raw L248)
- [ ] `consumer/.../pattern/detector/double/{15 stub}.kt` — R2 stub
  - HaramiDetector(L258), HaramiCrossDetector(L271), PiercingDetector(L282), DarkCloudCoverDetector(L291), CounterattackDetector(L300), OnNeckDetector(L330), InNeckDetector(L339), ThrustingDetector(L348), SeparatingLinesDetector(L357), KickingDetector(L379), KickingByLengthDetector(L442), MatchingLowDetector(L394), GapSideBySideWhiteDetector(L412), HomingPigeonDetector(L421), DojiStarDetector(L430)
- [ ] `consumer/.../pattern/detector/triple/MorningStarDetector.kt` — R1 active (raw L451)
- [ ] `consumer/.../pattern/detector/triple/EveningStarDetector.kt` — R1 active (raw L470)
- [ ] `consumer/.../pattern/detector/triple/ThreeWhiteSoldiersDetector.kt` — R1 active (raw L489)
- [ ] `consumer/.../pattern/detector/triple/ThreeBlackCrowsDetector.kt` — R1 active (raw L502)
- [ ] `consumer/.../pattern/detector/triple/{23 stub}.kt` — R2 stub (그 중 7건은 SimpleTripleHelper 사용)
  - MorningDojiStarDetector(L460), EveningDojiStarDetector(L479), ThreeInsideDetector(L515), ThreeOutsideDetector(L531), AbandonedBabyDetector(L547), ThreeStarsInSouthDetector(L576 — SimpleTripleHelper bullish), AdvanceBlockDetector(L577 — SimpleTripleHelper bearish), StalledPatternDetector(L578 — bearish), TasukiGapDetector(L582), UpsideGapTwoCrowsDetector(L593), ThreeLineStrikeDetector(L602), UniqueThreeRiverDetector(L617 — SimpleTripleHelper bullish), BreakawayDetector(L618 — bullish), MatHoldDetector(L619 — bullish), LadderBottomDetector(L622 — bullish), ConcealingBabySwallowDetector(L623 — bullish), StickSandwichDetector(L626), TristarDetector(L636), IdenticalThreeCrowsDetector(L655), TwoCrowsDetector(L666), UpDownGapThreeMethodsDetector(L675 — TasukiGap delegation), DownsideTasukiGapDetector(L676 — TasukiGap filter -1 only), UpsideTasukiGapDetector(L677 — TasukiGap filter +1 only)

#### F. JvmPatternRecognizer (consumer 모듈)

- [ ] `consumer/.../pattern/JvmPatternRecognizer.kt`
  ```kotlin
  package kr.spdaewoon.magicjar.consumer.application.pattern

  import kr.spdaewoon.magicjar.consumer.application.pattern.helper.ConsecutiveDirectionCalculator
  import kr.spdaewoon.magicjar.domain.market.candle.Candle
  import kr.spdaewoon.magicjar.domain.pattern.Direction
  import kr.spdaewoon.magicjar.domain.pattern.PatternMatch
  import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
  import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
  import org.slf4j.LoggerFactory
  import org.springframework.stereotype.Component
  import java.math.BigDecimal

  /**
   * 57 패턴 인식기 (KIS Strategy Builder candlestick.py 정합).
   * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py
   * License: MIT
   *
   * `@Component(name = "patternRecognizer")` — T-DSL의 PatternRecognizerStub @ConditionalOnMissingBean 자동 비활성화.
   *
   * 57 detector @Component 자동 수집 → registry Map 등록 (ADR 0020 §3-1).
   * R1 active 10 + R2 stub 47 (catalog §"R1 인식기" 정합).
   */
  @Component("patternRecognizer")
  class JvmPatternRecognizer(
      detectors: List<PatternDetector>,
  ) : PatternRecognizer {
      private val log = LoggerFactory.getLogger(javaClass)
      private val registry: Map<String, PatternDetector> = detectors.associateBy { it.id }

      init {
          require(detectors.size == 57) {
              "PatternDetector count mismatch: expected 57 (catalog), actual ${detectors.size}"
          }
          log.info("Registered {} pattern detectors (catalog id 정합)", detectors.size)
      }

      override fun recognize(candles: List<Candle>, patterns: Set<String>): List<PatternMatch> {
          if (candles.size < 5) return emptyList()    // KIS L48 정합
          val last = candles.last()
          return patterns.mapNotNull { id ->
              val detector = registry[id] ?: return@mapNotNull null   // KIS L52 미감지 graceful
              try {
                  val result = detector.detect(candles)
                  if (result.direction == Direction.NEUTRAL) null
                  else PatternMatch(
                      pattern = id,
                      startTime = last.time,
                      endTime = last.time,
                      direction = result.direction,
                      confidence = result.confidence,
                      params = result.params,
                  )
              } catch (ex: Exception) {
                  log.debug("Detector '{}' threw exception, treating as no-match: {}", id, ex.message)
                  null    // KIS L57 try/except 정합
              }
          }
      }

      override fun recognize(name: String, candles: List<Candle>): Boolean {
          // T-DSL stub 시그니처 호환
          return recognize(candles, setOf(name)).isNotEmpty()
      }

      override fun consecutive(direction: String, candles: List<Candle>): Int {
          // T-DSL stub 시그니처 호환 — magicJar 자체 룰 (KIS 미존재)
          return ConsecutiveDirectionCalculator.consecutive(direction, candles)
      }
  }
  ```

#### G. api 모듈 PatternRecognizerConfig (T-IND IndicatorEngineConfig 정합)

- [ ] `api/.../config/PatternRecognizerConfig.kt`
  ```kotlin
  /**
   * api 모듈에서 JvmPatternRecognizer bean 별도 등록 (T-IND IndicatorEngineConfig 정합).
   * detectors는 Spring auto-collect — api 모듈 component scan 범위에 consumer.application.pattern.detector.* 포함 의무.
   */
  @Configuration
  class PatternRecognizerConfig {
      @Bean(name = ["patternRecognizer"])
      fun patternRecognizer(detectors: List<PatternDetector>): PatternRecognizer =
          JvmPatternRecognizer(detectors)
  }
  ```

  **circular dep 검증 의무**: `./gradlew :api:bootJar` 빌드 검증. 만약 circular 발견되면 architect 호출 (fallback: api 모듈에 별도 detector copy — 비권장).

#### H. 단위 테스트 5종 (R1 fixture)

- [ ] `consumer/src/test/kotlin/.../pattern/PatternFixtureTest.kt` — R1 5종(doji·hammer·engulfing·morning_star·three_white_soldiers) KIS raw 룰 1:1 정합 검증
- [ ] `consumer/src/test/kotlin/.../pattern/JvmPatternRecognizerTest.kt` — recognize multi-pattern · consecutive · 캔들 부족 가드 · 미지원 패턴 graceful
- [ ] `consumer/src/test/kotlin/.../pattern/PatternRegistryTest.kt` — `assertEquals(57, detectors.size)` + catalog id 1:1 매핑 검증

#### I. CHANGELOG row in-place edit

- [ ] `<!-- pr-38-slot-strategy-engine -->` 위 row 업데이트:
  ```markdown
  | 2026-05-10 | **feat(W2 T-PAT): JvmPatternRecognizer + 57 detector + util/helper + api bean 등록 + 단위 테스트 5종 (PR-39a)** — `consumer/.../pattern/JvmPatternRecognizer.kt` (Map registry 자동 수집 + KIS L48·L57 정합 가드) · `util/CandleMath.kt` (KIS 7 helper) · `helper/SimpleTripleHelper.kt` (KIS L562 _simple_triple port) · `helper/ConsecutiveDirectionCalculator.kt` (magicJar 자체) · `detector/single/{14}.kt` + `detector/double/{16}.kt` + `detector/triple/{27}.kt` (R1 active 10 + R2 stub 47 — KIS 65 PATTERN_DETECTORS 9 제외) · `api/.../config/PatternRecognizerConfig.kt` (api 모듈 bean) · 단위 테스트 5종(R1 fixture KIS raw 1:1 정합). KIS-first raw fetch 의무 정합. (작성: strategy-engine) | consumer/api | PR-39a 코드 |
  ```

### 2-3. KIS-first raw fetch 의무 (헌법 §3 정독 의무)

PR-39a strategy-engine sub-agent는 spec §2-4 + raw verification §1 명령 실행 후 다음 사항 확인 의무:

```bash
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/candlestick.py > /tmp/kis-candlestick.py
wc -l /tmp/kis-candlestick.py     # 약 755 라인
```

각 57개 detector 구현 시 KDoc에 `Source: candlestick.py:line_NN · License: MIT` 명시 의무 (R1 10건 active은 본문 1:1 정합, R2 47건 stub은 시그니처 출처 의무 — 본문 R2 사이클 보강 명시).

자체 추론 0 — 애매한 경우 raw 출처 줄번호 인용 KDoc.

---

## §3. backend-core 위임 (PR-39b — 보조)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet — ADR 0035 정합) |
| 브랜치 | `agent/backend-core/pr-39-w2-t-pat-domain-rest` |
| base | `main` (PR-38 spec 머지 후) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `T-PAT backend-core — domain pattern/* (PatternMatch·Direction·DetectorResult·PatternCatalog 57 LOCK·PatternException) + port(PatternRecognizer·PatternDetector — T-DSL stub 시그니처 호환) + api PatternController + DTO + RedisPatternCacheAdapter + T-DSL PatternRecognizerStub 삭제 (5-A 정합) + pattern-catalog.md 갱신` |
| Task 파일 deliverables | `domain/.../pattern/{PatternMatch,Direction,DetectorResult,PatternCatalog,PatternException}.kt + port/{PatternRecognizer,PatternDetector}.kt, api/.../controller/PatternController.kt + dto/PatternMatchDto.kt, consumer/.../pattern/cache/RedisPatternCacheAdapter.kt, consumer/.../strategy/PatternRecognizerStub.kt 삭제, _workspace/strategy/pattern-catalog.md R1·R2 매트릭스 갱신, CHANGELOG row in-place edit (작성: backend-core)` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | M (~620 LOC + 60 삭제) |

### 3-2. 작업 범위 (Done 정의)

#### A. domain pattern/* 도메인 sealed class

- [ ] `domain/.../pattern/PatternMatch.kt` (data class — REST·DSL 응답 공용)
- [ ] `domain/.../pattern/Direction.kt` (enum — BULL·BEAR·NEUTRAL)
- [ ] `domain/.../pattern/DetectorResult.kt` (data class — detector 내부 응답)
- [ ] `domain/.../pattern/PatternCatalog.kt` (object — 57 Spec 등록 + `init { check(ALL.size == 57) }`)
- [ ] `domain/.../pattern/PatternException.kt` (sealed class — UnknownPatternException·InsufficientCandlesException)
- [ ] `domain/.../pattern/port/PatternRecognizer.kt` (interface — T-DSL stub 시그니처 호환 의무)
- [ ] `domain/.../pattern/port/PatternDetector.kt` (interface — 57 @Component이 구현)

**spec §6-3 시그니처 LOCK 의무** — sub-agent 자체 변경 0건.

#### B. PatternController (api 모듈)

- [ ] `api/.../controller/PatternController.kt` — Swagger 4단 (Tag·Operation·Parameter·example) + 한국어 설명
- [ ] `api/.../controller/dto/PatternMatchDto.kt` — `PatternResponseDto + matches: List<PatternMatchDto>` 직렬화 친화

**spec §6-4 endpoint LOCK** — `GET /api/patterns?code=...&interval=...&patterns=...&from=...&to=...`

#### C. RedisPatternCacheAdapter (consumer 모듈)

- [ ] `consumer/.../pattern/cache/RedisPatternCacheAdapter.kt` — StringRedisTemplate 직접 사용 + patternsHash + TTL 매트릭스 (T-IND 정합)

#### D. T-DSL PatternRecognizerStub 삭제 (5-A LOCK)

- [ ] `consumer/.../strategy/PatternRecognizerStub.kt` **삭제**
- [ ] smoke 검증: `docker logs magicjar-consumer-app | grep "PatternRecognizerStub"` 0건
- [ ] DSL `pattern("hammer")` evaluate 정상 동작 확인 (JvmPatternRecognizer로 라우팅)

**중요**: stub 삭제는 PR-39a JvmPatternRecognizer 머지 **후** 또는 동시. 단 PR-39b가 먼저 머지되면 PR-39a 머지 전까지 일시적으로 ConditionalOnMissingBean 부재 → 빌드는 OK이지만 부팅 시 PatternRecognizer bean 0건 → DSL pattern() 호출 시 NullPointerException risk.

**해결**: PR-39b는 stub 삭제 시점을 PR-39a 머지 후로 미루기 — `git fetch origin main && git rebase origin/main` 후 stub 삭제 commit 추가.

또는 PR-39b가 빈 JvmPatternRecognizer 임시 stub을 만들어 ConditionalOnMissingBean 충족 후 PR-39a가 진짜 구현으로 교체. **architect 권장 = PR-39b는 stub 삭제 commit을 마지막에 — PR-39a 머지 시점 후 rebase 시점**.

#### E. pattern-catalog.md 갱신

- [ ] `_workspace/strategy/pattern-catalog.md` §"R1 인식기 구현 범위" 표 갱신 — R1 active 10 + R2 stub 47 매트릭스 명시 (각 detector → KIS raw 줄 매핑 보강)

#### F. CHANGELOG row in-place edit

- [ ] `<!-- pr-38-slot-backend-core -->` 위 row 업데이트:
  ```markdown
  | 2026-05-10 | **feat(W2 T-PAT): domain pattern/* port + api PatternController REST + DTO + Redis 캐시 + T-DSL stub 삭제 + catalog 갱신 (PR-39b)** — `domain/.../pattern/{PatternMatch,Direction,DetectorResult,PatternCatalog,PatternException}.kt` (5 도메인 + 57 catalog Spec LOCK) · `port/{PatternRecognizer,PatternDetector}.kt` (T-DSL stub 시그니처 호환) · `api/.../controller/PatternController.kt + dto/PatternMatchDto.kt` (GET /api/patterns 단일 endpoint Swagger 4단 한국어) · `consumer/.../pattern/cache/RedisPatternCacheAdapter.kt` (T-IND 정합 — 5분 분봉 / 1일 일봉 TTL + patternsHash SHA-256 16자) · `consumer/.../strategy/PatternRecognizerStub.kt` **삭제** (5-A LOCK — JvmPatternRecognizer 동치 활성화 후 청소) · `_workspace/strategy/pattern-catalog.md` R1·R2 매트릭스 갱신. (작성: backend-core) | domain/api/consumer | PR-39b 코드 |
  ```

---

## §4. 후속 PR-40 R3 frontend·통합 (참고용)

본 문서는 PR-39 디스패치 명세. PR-40 R3은 PR-39 머지 후 **별도 dispatch 발행** 의무 — frontend(메인) + strategy-engine·backend-core(보조) 분배 + 57 fixture 일괄 + LWC marker e2e 명세.

R3 핵심:
- frontend `PatternMarker.tsx` + `PatternToggleMenu.tsx` + LWC v4 setMarkers integration
- consumer 57 detector 정확성 fixture 일괄 (R1 10건 + R2 47건 stub 그래스ful 검증)
- e2e — REST 호출 → JSON 응답 → LWC markers 동작
- 사용자 차트에서 패턴 overlay 토글 시 BULL 녹색 화살표·BEAR 빨강 화살표 표시

---

## §5. CHANGELOG 슬롯 사전 발행 (ADR 0050 정합)

본 spec PR-38이 sub-agent별 anchor + slot row 사전 발행 의무. 4 sub-agent 슬롯:

```markdown
<!-- 본 spec PR 머지 시점에 prepend (CHANGELOG 표 직후, 최신 row 먼저) -->

| 2026-05-10 | **docs(specs): PR-38 W2 T-PAT 진입 — 차트 패턴 57종 인식 spec/plan + dispatch + 충돌 행렬 + KIS-first raw verification + CHANGELOG 슬롯 사전 발행** — `_workspace/specs/pr-38-w2-t-pat-design.md` (domain/pattern/* 8 + consumer/.../pattern/detector/{single·double·triple} 57 분할 + util/helper + JvmPatternRecognizer @Component(name="patternRecognizer") + RedisPatternCacheAdapter + PatternController REST endpoint + DSL stub 5-A 삭제 LOCK), `pr-38-w2-t-pat-plan.md` (PR-39a ~2590 LOC + PR-39b ~620 LOC + PR-40 R3 ~1480 LOC · 시나리오 A 완전 병렬 23% 단축), `pr-38-dispatch-spec.md` (PR-39a strategy-engine 메인 + PR-39b backend-core 보조 분배 + 인터페이스 동결 + KIS-first raw fetch 의무 + stub 삭제 시점 LOCK), `pr-38-conflict-matrix.md` (**시나리오 A 적극 병렬 권장** — modify-shared 1건 backend-core 단독 LOCK + add-only 100%), `pr-38-kis-pattern-raw-verification.md` (KIS candlestick.py 755 lines raw fetch — 65 PATTERN_DETECTORS dict + 7 helper + detect_pattern 진입점 + alias delegation 9 제외 LOCK · 자체 추론 0). 신규 ADR 0건 — ADR 0020/0022/0047/0050 implementation 명세 충분 (T-IND PR-32 + T-DSL PR-35 패턴 정합). T-DSL PatternRecognizerStub 1줄 교체(5-A 삭제 + JvmPatternRecognizer 동치) LOCK — v1/v2 잔재 0건. fitness gap 검증 — v1/v2 잔재 0건. (architect) | docs/spec | PR-38 W2 T-PAT 진입 spec/plan |
<!-- pr-38-slot-architect -->
| 2026-05-10 | TBD ... (작성: strategy-engine) — TBD | consumer/api | TBD |
<!-- pr-38-slot-strategy-engine -->
| 2026-05-10 | TBD ... (작성: backend-core) — TBD | domain/api/consumer | TBD |
<!-- pr-38-slot-backend-core -->
| 2026-05-10 | TBD ... (작성: frontend, R3 PR-40) — TBD | frontend/consumer | TBD |
<!-- pr-38-slot-frontend -->
<!-- pr-38-slot-end -->
```

`(작성: <agent>)` 마커 row만 본인이 in-place edit 의무. 다른 row·anchor 절대 수정 금지 (헌법 §4 정합).

---

## §6. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-10 | PR-38 sub-agent 분배 명세 v1 발행. PR-39a strategy-engine 메인 (JvmPatternRecognizer + 57 detector + util/helper + api bean) + PR-39b backend-core 보조 (domain port + REST + Redis + DSL stub 삭제) + PR-40 R3 frontend 분배. 인터페이스 동결 시점 = PR-38 머지. KIS-first raw fetch 의무 sub-agent 전파. ADR 0050 슬롯 사전 발행 4 sub-agent. (architect) |
