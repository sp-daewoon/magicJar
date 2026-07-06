package kr.spdaewoon.magicjar.consumer.trendline

import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.config.JpaScanConfiguration
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.pattern.PatternMatch
import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlinePricePort
import kr.spdaewoon.magicjar.persistence.shared.trendline.TrendlineAnchorJpaAdapter
import kr.spdaewoon.magicjar.persistence.shared.trendline.TrendlineAnchorJpaRepository
import kr.spdaewoon.magicjar.persistence.shared.trendline.TrendlineAnchorRow
import kr.spdaewoon.magicjar.strategy.DslIdentifierMapper
import kr.spdaewoon.magicjar.strategy.JvmStrategyEvaluator
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import kr.spdaewoon.magicjar.strategy.StrategyEvalMetricRecorder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Optional
import java.util.concurrent.ThreadPoolExecutor
import java.time.Instant as JInstant

/**
 * 추세매매 Phase 2 R2 — consumer ApplicationContext 가 [TrendlinePricePort] 빈을 발견하는지 정적 검증 +
 * 어댑터 → 평가기 통합 경로 (ADR 0140 §5, spec R2 모듈 합류 검증).
 *
 * ## 배경 (헌법 §11-1 · RiskConfig 2026-06-10 incident 선례)
 * [JvmStrategyEvaluator] 의 `trendlinePricePort: TrendlinePricePort?` 는 optional 주입이다. 구현 빈
 * ([TrendlineAnchorJpaAdapter]) 이 consumer 에 합류하지 않으면 TrendlineRef 평가가 영구 false(port 부재)로
 * 접혀 기울선 전략이 절대 발화하지 않는 silent 결함이 된다. persistence-shared 배치로 api·consumer 양쪽 합류를
 * 보장한다(RiskConfigJpaAdapter 패턴 1:1).
 *
 * ## 본 테스트 (DB 컨테이너 0)
 *   1. [TrendlineAnchorJpaAdapter] 는 [TrendlinePricePort] 구현 @Component (ComponentScan 발견 대상).
 *   2. 어댑터·엔티티·리포지터리 패키지가 consumer [JpaScanConfiguration] scan 범위에 포함.
 *   3. 통합 — 실제 어댑터(DB row → anchors 변환)를 평가기에 주입해 `close > trendline(1)` 이 BUY 로 발화.
 *
 * // AI 호출 0 — 추세매매/T-SIG 트랙 정책.
 */
class ConsumerTrendlinePriceWiringTest {
    @Test
    fun `TrendlineAnchorJpaAdapter는 TrendlinePricePort를 구현한 @Component다`() {
        assertThat(TrendlinePricePort::class.java.isAssignableFrom(TrendlineAnchorJpaAdapter::class.java))
            .`as`("TrendlineAnchorJpaAdapter must implement TrendlinePricePort (evaluator optional 주입 타입)")
            .isTrue()
        assertThat(TrendlineAnchorJpaAdapter::class.java.getAnnotation(Component::class.java))
            .`as`("TrendlineAnchorJpaAdapter must be @Component for consumer ComponentScan discovery")
            .isNotNull()
    }

    @Test
    fun `consumer JpaScanConfiguration basePackages가 trendline JPA 어댑터 패키지를 커버한다`() {
        val adapterPackage = TrendlineAnchorJpaAdapter::class.java.packageName // ...persistence.shared.trendline

        val enableJpa = JpaScanConfiguration::class.java.getAnnotation(EnableJpaRepositories::class.java)
        val entityScan = JpaScanConfiguration::class.java.getAnnotation(EntityScan::class.java)
        assertThat(enableJpa).isNotNull()
        assertThat(entityScan).isNotNull()

        assertThat(enableJpa.basePackages.any { adapterPackage.startsWith(it) })
            .`as`("@EnableJpaRepositories must cover %s (TrendlineAnchorJpaRepository 발견)", adapterPackage)
            .isTrue()
        assertThat(entityScan.basePackages.any { adapterPackage.startsWith(it) })
            .`as`("@EntityScan must cover %s (TrendlineAnchorRow 발견)", adapterPackage)
            .isTrue()
    }

    @Test
    fun `통합 — 실제 어댑터 anchors 로 close crosses trendline 이 BUY 발화`() {
        val repo = mockk<TrendlineAnchorJpaRepository>()
        // 전 구간 평평한 선 100 (extend 양방향) — close(100+i) 가 마지막 봉에서 선 위 → BUY.
        every { repo.findById(1L) } returns
            Optional.of(
                TrendlineAnchorRow(
                    id = 1L,
                    anchor1Ts = JInstant.ofEpochSecond(1_699_000_000L),
                    anchor1Price = BigDecimal("100.0000"),
                    anchor2Ts = JInstant.ofEpochSecond(1_703_000_000L),
                    anchor2Price = BigDecimal("100.0000"),
                    extendRight = true,
                    extendLeft = true,
                ),
            )
        val adapter = TrendlineAnchorJpaAdapter(repo, cacheTtlMs = 30_000)

        val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)
        val ast = parser.parse("close > trendline(1)")
        val result = evaluatorWith(adapter).evaluate(1L, ast, candleSeries(20))

        assertEquals(Signal.BUY, result.signal)
    }

    // ── evaluator fixtures (R1 TrendlineRefDslTest 패턴) ──────────────────

    private fun evaluatorWith(port: TrendlinePricePort): JvmStrategyEvaluator {
        val evalPool =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 2
                maxPoolSize = 2
                queueCapacity = 10
                setThreadNamePrefix("trendline-r2-test-")
                setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
                initialize()
            }
        val noopPattern =
            object : PatternRecognizer {
                override fun recognize(
                    candles: List<Candle>,
                    patterns: Set<String>,
                ): List<PatternMatch> = emptyList()

                override fun recognize(
                    name: String,
                    candles: List<Candle>,
                ): Boolean = false

                override fun consecutive(
                    direction: String,
                    candles: List<Candle>,
                ): Int = 0
            }
        return JvmStrategyEvaluator(
            indicatorEngine = IndicatorEngine(listOf(FakeCloseSource())),
            patternRecognizer = noopPattern,
            identifierMapper = DslIdentifierMapper(),
            evalPool = evalPool,
            metricRecorder = StrategyEvalMetricRecorder(),
            autoDisableCallback = { _: Long, _: StrategyDisabledReason -> },
            timeoutMs = 5000,
            cpuBudgetMs = 50,
            backtestTimeoutMs = 5000,
            cpuBudgetViolationThreshold = 5,
            trendlinePricePort = port,
        )
    }

    private fun candleSeries(count: Int): List<Candle> =
        (0 until count).map { i ->
            Candle(
                code = "005930",
                interval = CandleInterval.ONE_DAY,
                ts = Instant.fromEpochSeconds(1_700_000_000L + i * 86_400L),
                openRaw = BigDecimal("100"),
                highRaw = BigDecimal("105"),
                lowRaw = BigDecimal("95"),
                closeRaw = BigDecimal("100").add(BigDecimal(i)),
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = 1_000_000L,
                source = "test",
            )
        }

    private class FakeCloseSource : IndicatorSource {
        override val name: String = "fake-close"
        override val priority: Int = 1

        override fun supports(spec: IndicatorSpec): Boolean = true

        override fun calculate(
            spec: IndicatorSpec,
            candles: List<Candle>,
        ): IndicatorResult {
            val series = candles.map { IndicatorPoint(time = it.ts, values = mapOf(spec.name to BigDecimal("50"))) }
            return IndicatorResult(spec = spec, series = series, source = name)
        }
    }
}
