package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanEntry
import kr.spdaewoon.magicjar.domain.strategy.TradePlanExitCondition
import kr.spdaewoon.magicjar.domain.strategy.TradePlanHold
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStop
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTarget
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTrailing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [TradePlanEvaluator] 순수 함수 결정론 테스트 — ADR 0130 L2 (PlanExecutor 두뇌).
 *
 * 각 청산 사유 발화 + 우선순위(SL>MAX_HOLD>exitConditions>TP>TRAIL) + 비발화(OPEN 유지) + status 가드를 검증한다.
 * 가격 라인은 public helper(stopPriceOf/targetPriceOf/trailTriggerPriceOf)로 산정해 KRX 호가단위 내림과 정합 비교한다.
 */
class TradePlanEvaluatorTest {
    private val avg = BigDecimal("10000")

    private fun plan(
        target: TradePlanTarget? = null,
        stop: TradePlanStop? = null,
        trailing: TradePlanTrailing? = null,
        hold: TradePlanHold? = null,
        exitConditions: List<TradePlanExitCondition> = emptyList(),
        status: TradePlanStatus = TradePlanStatus.OPEN,
    ): TradePlan =
        TradePlan(
            entry = TradePlanEntry(strategyId = 100L, entryPrice = avg, bar = "1m"),
            target = target,
            stop = stop,
            trailing = trailing,
            hold = hold,
            exitConditions = exitConditions,
            status = status,
        )

    private fun state(
        price: BigDecimal,
        elapsedBars: Int = 0,
        high: BigDecimal? = null,
    ) = TradePlanEvaluator.PlanEvalState(price = price, avgPrice = avg, elapsedBars = elapsedBars, highWatermark = high)

    @Test
    fun `SL 발화 — 손절가 이하면 SL 결정`() {
        val p = plan(stop = TradePlanStop(slPct = BigDecimal("2.0")))
        val sl = TradePlanEvaluator.stopPriceOf(p, avg)!!
        val d = TradePlanEvaluator.evaluate(p, state(price = sl))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.SL)
    }

    @Test
    fun `TRENDLINE 손절 — 재박제된 slPrice 가 SL 라인 권위(slPct 없이 slPrice 사용)`() {
        // ADR 0140 §3-4 R2b — 봉 경계 재박제로 갱신되는 TRENDLINE 손절은 slPct 없이 slPrice(선 가격)를 그대로 쓴다.
        val p =
            plan(
                stop =
                    TradePlanStop(
                        type = kr.spdaewoon.magicjar.domain.strategy.TradePlanStopType.TRENDLINE,
                        slPrice = BigDecimal("9700"),
                        trendlineId = 77L,
                    ),
            )
        val sl = TradePlanEvaluator.stopPriceOf(p, avg)!!
        assertThat(sl.compareTo(BigDecimal("9700"))).isEqualTo(0) // slPrice 그대로(호가단위 위).
        val d = TradePlanEvaluator.evaluate(p, state(price = BigDecimal("9700")))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.SL) // SL 최우선 유지(회귀 0).
    }

    @Test
    fun `SL 미발화 — 손절가 초과면 청산 없음`() {
        val p = plan(stop = TradePlanStop(slPct = BigDecimal("2.0")))
        val d = TradePlanEvaluator.evaluate(p, state(price = avg)) // 평단=현재가 → 손절선 위.
        assertThat(d).isNull()
    }

    @Test
    fun `TP 발화 — 익절가 이상이면 TP 결정`() {
        val p = plan(target = TradePlanTarget(tpPct = BigDecimal("3.0")))
        val tp = TradePlanEvaluator.targetPriceOf(p, avg)!!
        val d = TradePlanEvaluator.evaluate(p, state(price = tp))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.TP)
    }

    @Test
    fun `MAX_HOLD 발화 — 경과 봉이 maxHoldBars 이상`() {
        val p = plan(hold = TradePlanHold(maxHoldBars = 30))
        val d = TradePlanEvaluator.evaluate(p, state(price = avg, elapsedBars = 30))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.MAX_HOLD)
    }

    @Test
    fun `MAX_HOLD 미발화 — 경과 봉 미달`() {
        val p = plan(hold = TradePlanHold(maxHoldBars = 30))
        val d = TradePlanEvaluator.evaluate(p, state(price = avg, elapsedBars = 29))
        assertThat(d).isNull()
    }

    @Test
    fun `TRAIL 발화 — 고점 대비 trailPct 하락`() {
        val p = plan(trailing = TradePlanTrailing(enabled = true, trailPct = BigDecimal("1.0")))
        val high = BigDecimal("11000")
        val trailStop = TradePlanEvaluator.trailTriggerPriceOf(p, state(price = high, high = high))!!
        val d = TradePlanEvaluator.evaluate(p, state(price = trailStop, high = high))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.TRAIL)
    }

    @Test
    fun `TRAIL 미발화 — activatePct 미도달이면 무장 안 됨`() {
        // 고점이 평단 대비 1.5% 미만이면 트레일링 무장 안 함 → 현재가가 trail 라인 아래여도 미발화.
        val p = plan(trailing = TradePlanTrailing(enabled = true, activatePct = BigDecimal("1.5"), trailPct = BigDecimal("1.0")))
        val high = BigDecimal("10100") // +1.0% 만 — activate 1.5% 미만.
        val d = TradePlanEvaluator.evaluate(p, state(price = BigDecimal("9990"), high = high))
        assertThat(d).isNull()
    }

    @Test
    fun `exitCondition 발화 — price_below rule`() {
        val p = plan(exitConditions = listOf(TradePlanExitCondition(rule = "price_below", params = mapOf("price" to "9500"))))
        val d = TradePlanEvaluator.evaluate(p, state(price = BigDecimal("9500")))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.EXIT_COND)
    }

    @Test
    fun `exitCondition 미발화 — 캔들 의존 rule(vwap_cross_down)은 tick 평가 no-op`() {
        val p = plan(exitConditions = listOf(TradePlanExitCondition(rule = "vwap_cross_down", params = mapOf("src" to "1m"))))
        val d = TradePlanEvaluator.evaluate(p, state(price = BigDecimal("5000")))
        assertThat(d).isNull()
    }

    @Test
    fun `우선순위 SL이 TP보다 우선 — 둘 다 충족 시 SL 채택 (자본 보호)`() {
        // SL·TP 둘 다 충족하도록 — 현재가가 손절선 이하이면서(SL) 동시에 익절선 이상일 수는 없으나,
        // 구조적 우선순위를 검증하기 위해 SL 발화 케이스에 TP 도 설정해 SL 이 먼저 반환됨을 확인한다.
        val p =
            plan(
                target = TradePlanTarget(tpPct = BigDecimal("3.0")),
                stop = TradePlanStop(slPct = BigDecimal("2.0")),
            )
        val sl = TradePlanEvaluator.stopPriceOf(p, avg)!!
        val d = TradePlanEvaluator.evaluate(p, state(price = sl))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.SL)
    }

    @Test
    fun `우선순위 MAX_HOLD가 TP보다 우선 — 둘 다 충족 시 MAX_HOLD 채택`() {
        // 익절가 이상 + maxHold 초과 동시 → MAX_HOLD 가 TP 보다 우선(보유 상한 강제).
        val p =
            plan(
                target = TradePlanTarget(tpPct = BigDecimal("3.0")),
                hold = TradePlanHold(maxHoldBars = 10),
            )
        val tp = TradePlanEvaluator.targetPriceOf(p, avg)!!
        val d = TradePlanEvaluator.evaluate(p, state(price = tp, elapsedBars = 10))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.MAX_HOLD)
    }

    @Test
    fun `우선순위 exitCondition이 TP보다 우선`() {
        val p =
            plan(
                target = TradePlanTarget(tpPct = BigDecimal("3.0")),
                exitConditions = listOf(TradePlanExitCondition(rule = "price_above", params = mapOf("price" to "10100"))),
            )
        // 현재가 10300 → exitCond(>=10100) 충족 + tp(10300) 충족. exitCond 가 우선.
        val tp = TradePlanEvaluator.targetPriceOf(p, avg)!!
        val d = TradePlanEvaluator.evaluate(p, state(price = tp.max(BigDecimal("10300"))))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.EXIT_COND)
    }

    @Test
    fun `우선순위 TP가 TRAIL보다 우선 — 둘 다 충족 시 TP 채택`() {
        val p =
            plan(
                target = TradePlanTarget(tpPct = BigDecimal("3.0")),
                trailing = TradePlanTrailing(enabled = true, trailPct = BigDecimal("1.0")),
            )
        val tp = TradePlanEvaluator.targetPriceOf(p, avg)!!
        // 고점 매우 높게 → trail 라인도 충족 가능하지만 현재가가 tp 이상이라 TP 가 먼저 반환.
        val d = TradePlanEvaluator.evaluate(p, state(price = tp, high = BigDecimal("20000")))
        assertThat(d?.reason).isEqualTo(TradePlanEvaluator.ExitReason.TP)
    }

    @Test
    fun `status가 OPEN 아니면 평가 0 (CLOSING 재발화 방지)`() {
        val p = plan(stop = TradePlanStop(slPct = BigDecimal("2.0")), status = TradePlanStatus.CLOSING)
        val sl = TradePlanEvaluator.stopPriceOf(p, avg)!!
        val d = TradePlanEvaluator.evaluate(p, state(price = sl))
        assertThat(d).isNull()
    }

    @Test
    fun `계획 비어 있으면(목표·손절 등 전부 null) 평가 0`() {
        val d = TradePlanEvaluator.evaluate(plan(), state(price = BigDecimal("1")))
        assertThat(d).isNull()
    }

    @Test
    fun `가격 0 이하면 평가 0 (방어)`() {
        val p = plan(stop = TradePlanStop(slPct = BigDecimal("2.0")))
        assertThat(TradePlanEvaluator.evaluate(p, state(price = BigDecimal.ZERO))).isNull()
    }
}
