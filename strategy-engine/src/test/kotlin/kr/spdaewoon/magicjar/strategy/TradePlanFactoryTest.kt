package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanContingency
import kr.spdaewoon.magicjar.domain.strategy.TradePlanEntry
import kr.spdaewoon.magicjar.domain.strategy.TradePlanHold
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStop
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStopType
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTarget
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTrailing
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [TradePlanFactory] hoist 동치 테스트 — ADR 0132 §1-C (라이브 `SignalGeneratedKafkaListener.buildTradePlan` parity).
 *
 * ## 동치 단언 전략 (거동 byte-동일 증명)
 * 라이브 `buildTradePlan` 은 consumer private 라 직접 호출할 수 없으므로, **hoist 이전 인라인 산식을 그대로 복제한
 * golden reference**([legacyBuildTradePlan])를 본 테스트에 두고 [TradePlanFactory.build] 와 전 필드 동일을 단언한다.
 * golden reference 는 hoist 직전 `SignalGeneratedKafkaListener.buildTradePlan(:1940)` 의 코드를 1:1 옮긴 것이다.
 */
class TradePlanFactoryTest {
    private val hundred = BigDecimal("100")

    /** hoist 직전 라이브 인라인 산식 1:1 복제 — 동치 비교 기준(golden reference). */
    private fun legacyBuildTradePlan(
        strategyId: Long,
        definition: StrategyDefinition,
        entryPrice: BigDecimal,
        bar: String?,
        openedAt: String?,
        thesis: String?,
    ): TradePlan {
        val tpPct = definition.takeProfitPct?.takeIf { it > BigDecimal.ZERO }
        val slPct = definition.fixedStopLoss?.takeIf { it > BigDecimal.ZERO }
        val trailPct = definition.trailingStop?.takeIf { it > BigDecimal.ZERO }
        val minHold = definition.minHoldBars?.takeIf { it >= 0 }

        val target =
            tpPct?.let {
                TradePlanTarget(
                    tpPct = it,
                    tpPrice = entryPrice.add(entryPrice.multiply(it).divide(hundred, 0, RoundingMode.DOWN)),
                )
            }
        val stop =
            slPct?.let {
                TradePlanStop(
                    type = TradePlanStopType.FIXED,
                    slPct = it,
                    slPrice = entryPrice.subtract(entryPrice.multiply(it).divide(hundred, 0, RoundingMode.UP)),
                )
            }
        // 감사 부록 A-3 (2026-07-03) — 트레일링 즉시 무장 금지: activatePct default = max(trailPct, 1.0%).
        //   golden reference 도 동일 amend(라이브·백테스트가 공유하는 TradePlanFactory 가 단일 권위).
        val trailing =
            trailPct?.let {
                TradePlanTrailing(enabled = true, trailPct = it, activatePct = it.max(BigDecimal("1.0")))
            }
        val hold = minHold?.let { TradePlanHold(minHoldBars = it) }
        val contingency =
            TradePlanContingency(
                viAction = definition.viActionPolicy.name,
                eod = if (definition.allowOvernight) "ALLOW_OVERNIGHT" else "LIQUIDATE",
            )
        return TradePlan(
            entry =
                TradePlanEntry(
                    strategyId = strategyId,
                    thesis = thesis?.takeIf { it.isNotBlank() },
                    entryPrice = entryPrice,
                    bar = bar,
                    openedAt = openedAt,
                ),
            target = target,
            stop = stop,
            trailing = trailing,
            hold = hold,
            contingency = contingency,
            status = TradePlanStatus.OPEN,
            exitReason = null,
        )
    }

    private fun def(
        tp: String? = null,
        sl: String? = null,
        trail: String? = null,
        minHold: Int? = null,
        vi: ViActionPolicy = ViActionPolicy.ALERT_ONLY,
        overnight: Boolean = true,
    ) = StrategyDefinition(
        dsl = DslStrategyDefinition(name = "test"),
        trailingStop = trail?.let { BigDecimal(it) },
        fixedStopLoss = sl?.let { BigDecimal(it) },
        takeProfitPct = tp?.let { BigDecimal(it) },
        minHoldBars = minHold,
        viActionPolicy = vi,
        allowOvernight = overnight,
    )

    private fun assertEquivalent(
        definition: StrategyDefinition,
        entryPrice: BigDecimal,
        bar: String? = "1m",
        openedAt: String? = "2026-06-24T01:12:00Z",
        thesis: String? = "vwap reclaim",
    ) {
        val expected = legacyBuildTradePlan(105L, definition, entryPrice, bar, openedAt, thesis)
        val actual = TradePlanFactory.build(105L, definition, entryPrice, bar, openedAt, thesis)
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `동치 — 풀 설정 (tp sl trail minHold)`() {
        assertEquivalent(
            def(tp = "3.0", sl = "2.0", trail = "1.0", minHold = 3, vi = ViActionPolicy.EXIT_MARKET, overnight = false),
            BigDecimal("12340"),
        )
    }

    @Test
    fun `동치 — TP만`() {
        assertEquivalent(def(tp = "2.5"), BigDecimal("9870"))
    }

    @Test
    fun `동치 — 미설정 전부 null`() {
        assertEquivalent(def(), BigDecimal("50000"))
    }

    @Test
    fun `동치 — allowOvernight true 이면 contingency eod ALLOW_OVERNIGHT`() {
        val plan = TradePlanFactory.build(1L, def(overnight = true), BigDecimal("10000"))
        assertThat(plan.contingency?.eod).isEqualTo("ALLOW_OVERNIGHT")
    }

    @Test
    fun `동치 — allowOvernight false 이면 contingency eod LIQUIDATE`() {
        val plan = TradePlanFactory.build(1L, def(overnight = false), BigDecimal("10000"))
        assertThat(plan.contingency?.eod).isEqualTo("LIQUIDATE")
    }

    @Test
    fun `TP 절대가 — entryPrice + entryPrice x tpPct div 100 내림`() {
        // 12340 + floor(12340 * 3.0 / 100) = 12340 + 370 = 12710 (ADR 0130 §2 예시).
        val plan = TradePlanFactory.build(1L, def(tp = "3.0"), BigDecimal("12340"))
        assertThat(plan.target?.tpPrice).isEqualByComparingTo(BigDecimal("12710"))
    }

    @Test
    fun `SL 절대가 — entryPrice - ceil(entryPrice x slPct div 100) 보수적 올림`() {
        // 12340 - ceil(12340 * 2.0 / 100) = 12340 - 247 = 12093 (ADR 0130 §2 예시).
        val plan = TradePlanFactory.build(1L, def(sl = "2.0"), BigDecimal("12340"))
        assertThat(plan.stop?.slPrice).isEqualByComparingTo(BigDecimal("12093"))
    }

    @Test
    fun `트레일링 activatePct default — trailPct 1 미만이면 1 퍼센트로 하한 (감사 부록 A-3)`() {
        val plan = TradePlanFactory.build(1L, def(trail = "0.5"), BigDecimal("10000"))
        assertThat(plan.trailing?.enabled).isTrue()
        assertThat(plan.trailing?.trailPct).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(plan.trailing?.activatePct).isEqualByComparingTo(BigDecimal("1.0"))
    }

    @Test
    fun `트레일링 activatePct default — trailPct 1 이상이면 trailPct 그대로 (감사 부록 A-3)`() {
        val plan = TradePlanFactory.build(1L, def(trail = "2.0"), BigDecimal("10000"))
        assertThat(plan.trailing?.activatePct).isEqualByComparingTo(BigDecimal("2.0"))
    }

    @Test
    fun `thesis 공백이면 null 정규화 (라이브 isNotBlank 정합)`() {
        val plan = TradePlanFactory.build(1L, def(), BigDecimal("10000"), thesis = "   ")
        assertThat(plan.entry.thesis).isNull()
    }
}
