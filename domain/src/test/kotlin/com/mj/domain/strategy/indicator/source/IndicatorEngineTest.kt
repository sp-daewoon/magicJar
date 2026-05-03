package com.mj.domain.strategy.indicator.source

import com.mj.domain.common.Candle
import com.mj.domain.strategy.CandleFixtures
import com.mj.domain.strategy.indicator.IndicatorDescriptor
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.IndicatorPoint
import com.mj.domain.strategy.indicator.IndicatorResult
import com.mj.domain.strategy.indicator.IndicatorSpec
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import com.mj.domain.strategy.indicator.catalog.IndicatorCatalog
import com.mj.domain.strategy.indicator.port.IndicatorComputeException
import com.mj.domain.strategy.indicator.port.IndicatorSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * IndicatorEngine 라우팅 단위 테스트 (5 시나리오, ADR 0039 §2-2).
 *
 * S1: native-only 지원 — native(50)에서 계산
 * S2: kis-only 지원 (native 미지원) — kis source 계산
 * S3: 둘 다 지원 — priority 낮은 native 우선
 * S4: 둘 다 미지원 — IndicatorComputeException 발생
 * S5: params 검증 — 잘못된 period 타입도 default 처리
 */
class IndicatorEngineTest {
    private val candles = CandleFixtures.loadSampleSamsung()

    // ─── Stub sources ───
    private fun stubSource(
        supportedIds: Set<String>,
        priority: Int,
        sourceName: String,
    ): IndicatorSource =
        object : IndicatorSource {
            override val name = sourceName
            override val priority = priority

            override fun availableIndicators(): List<IndicatorDescriptor> = supportedIds.mapNotNull { IndicatorCatalog.BY_ID[it] }

            override fun supports(spec: IndicatorSpec): Boolean = spec.name in supportedIds

            override fun calculate(
                spec: IndicatorSpec,
                candles: List<Candle>,
            ): IndicatorResult =
                IndicatorResult(
                    spec = spec,
                    series =
                        listOf(
                            IndicatorPoint(Instant.now(), mapOf("stub" to BigDecimal.ONE)),
                        ),
                    source = name,
                )
        }

    @Test
    fun `S1 native-only — native source selected (priority=50)`() {
        val native = NativeIndicatorCalculator()
        val kis = stubSource(setOf("supertrend"), 100, "kis")
        val engine = IndicatorEngine(listOf(native, kis))

        val result = engine.calculate(IndicatorSpec("rsi", mapOf("period" to 14)), candles)
        result.source shouldBe "native"
    }

    @Test
    fun `S2 kis-only — kis stub source selected when native does not support`() {
        val native = stubSource(setOf("ma"), 50, "native")
        val kis = stubSource(setOf("supertrend", "ichimoku_tenkan"), 100, "kis")
        val engine = IndicatorEngine(listOf(native, kis))

        val result = engine.calculate(IndicatorSpec("supertrend"), listOf())
        result.source shouldBe "kis"
    }

    @Test
    fun `S3 both support same spec — priority wins (native=50 over kis=100)`() {
        val native = stubSource(setOf("rsi"), 50, "native")
        val kis = stubSource(setOf("rsi"), 100, "kis")
        val engine = IndicatorEngine(listOf(kis, native)) // 등록 순서와 무관

        val result = engine.calculate(IndicatorSpec("rsi"), listOf())
        result.source shouldBe "native"
    }

    @Test
    fun `S4 no source supports — IndicatorComputeException thrown`() {
        val native = stubSource(setOf("ma"), 50, "native")
        val kis = stubSource(setOf("ema"), 100, "kis")
        val engine = IndicatorEngine(listOf(native, kis))

        val ex =
            shouldThrow<IndicatorComputeException> {
                engine.calculate(IndicatorSpec("rsi"), listOf())
            }
        ex.message shouldContain "rsi"
    }

    @Test
    fun `S5 params as String type — intParam fallback to default`() {
        val native = NativeIndicatorCalculator()
        val engine = IndicatorEngine(listOf(native))

        // period 파라미터를 String "14"로 전달해도 정상 계산
        val result = engine.calculate(IndicatorSpec("rsi", mapOf("period" to "14")), candles)
        result.series.size shouldNotBe 0
    }

    @Test
    fun `allDescriptors — returns 80 entries from catalog`() {
        val native = NativeIndicatorCalculator()
        val kis = stubSource(IndicatorCatalog.ALL.map { it.id }.toSet(), 100, "kis")
        val engine = IndicatorEngine(listOf(native, kis))

        // native(50) 중복 id 우선, kis의 나머지 추가 → 총 80
        val all = engine.allDescriptors()
        all.size shouldBe 80
    }

    @Test
    fun `supportingSources — returns sources that support the spec`() {
        val native = stubSource(setOf("rsi", "ma"), 50, "native")
        val kis = stubSource(setOf("rsi", "supertrend"), 100, "kis")
        val engine = IndicatorEngine(listOf(native, kis))

        val supporting = engine.supportingSources(IndicatorSpec("rsi"))
        supporting.map { it.name } shouldBe listOf("native", "kis")

        val nativeOnly = engine.supportingSources(IndicatorSpec("ma"))
        nativeOnly.map { it.name } shouldBe listOf("native")
    }
}
