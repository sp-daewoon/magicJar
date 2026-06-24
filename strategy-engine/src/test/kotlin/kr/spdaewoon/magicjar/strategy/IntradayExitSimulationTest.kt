package kr.spdaewoon.magicjar.strategy

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanEntry
import kr.spdaewoon.magicjar.domain.strategy.TradePlanHold
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStop
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTarget
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTrailing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

/**
 * [IntradayExitSimulation] 초단타 청산 시뮬 테스트 — ADR 0132 §1-D·§8.
 *
 * SL/TP/MAX_HOLD/min_hold 각 발화 · intrabar SL 우선 · EOD 청산 · blackout 진입 억제를 단언한다.
 * 청산 가격 라인은 라이브 [TradePlanEvaluator] helper 와 동일 산식(평단 연동·KRX tick)이므로 parity 가 성립한다.
 */
class IntradayExitSimulationTest {
    private val avg = BigDecimal("10000")
    private val noon: LocalTime = LocalTime.of(12, 0)

    private fun plan(
        target: TradePlanTarget? = null,
        stop: TradePlanStop? = null,
        trailing: TradePlanTrailing? = null,
        hold: TradePlanHold? = null,
    ): TradePlan =
        TradePlan(
            entry = TradePlanEntry(strategyId = 100L, entryPrice = avg, bar = "1m"),
            target = target,
            stop = stop,
            trailing = trailing,
            hold = hold,
            status = TradePlanStatus.OPEN,
        )

    private fun bar(
        open: String,
        high: String,
        low: String,
        close: String,
    ): Candle =
        Candle(
            code = "005930",
            interval = CandleInterval.ONE_MIN,
            ts = Instant.fromEpochSeconds(0),
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = BigDecimal(open),
            highAdj = BigDecimal(high),
            lowAdj = BigDecimal(low),
            closeAdj = BigDecimal(close),
            volume = 1000L,
            source = "TEST",
        )

    private fun eval(
        plan: TradePlan,
        bar: Candle,
        elapsedBars: Int = 5,
        highWatermark: BigDecimal? = null,
        minHoldBars: Int? = null,
        allowOvernight: Boolean = true,
        barTimeKst: LocalTime = noon,
    ) = IntradayExitSimulation.evaluateBar(
        plan = plan,
        bar = bar,
        avgPrice = avg,
        elapsedBars = elapsedBars,
        highWatermark = highWatermark,
        minHoldBars = minHoldBars,
        allowOvernight = allowOvernight,
        barTimeKst = barTimeKst,
    )

    @Test
    fun `SL 발화 — 봉 low 가 SL 라인 이하`() {
        val p = plan(stop = TradePlanStop(slPct = BigDecimal("2.0")))
        val sl = TradePlanEvaluator.stopPriceOf(p, avg)!! // 9800
        val d = eval(p, bar("10000", "10010", sl.toPlainString(), "9990"))
        assertThat(d?.reason).isEqualTo(IntradayExitReason.SL)
        assertThat(d?.triggerPrice).isEqualByComparingTo(sl)
    }

    @Test
    fun `TP 발화 — 봉 high 가 TP 라인 이상 (SL 미도달)`() {
        val p = plan(target = TradePlanTarget(tpPct = BigDecimal("3.0")))
        val tp = TradePlanEvaluator.targetPriceOf(p, avg)!! // 10300
        val d = eval(p, bar("10000", tp.toPlainString(), "9990", "10250"))
        assertThat(d?.reason).isEqualTo(IntradayExitReason.TP)
        assertThat(d?.triggerPrice).isEqualByComparingTo(tp)
    }

    @Test
    fun `intrabar SL 우선 — 같은 봉에서 SL∧TP 동시 도달 시 SL`() {
        val p = plan(target = TradePlanTarget(tpPct = BigDecimal("3.0")), stop = TradePlanStop(slPct = BigDecimal("2.0")))
        val sl = TradePlanEvaluator.stopPriceOf(p, avg)!! // 9800
        val tp = TradePlanEvaluator.targetPriceOf(p, avg)!! // 10300
        // 봉이 SL·TP 둘 다 관통(low<=SL, high>=TP) — 낙관 편향 차단으로 SL 우선.
        val d = eval(p, bar("10000", tp.toPlainString(), sl.toPlainString(), "10000"))
        assertThat(d?.reason).isEqualTo(IntradayExitReason.SL)
        assertThat(d?.triggerPrice).isEqualByComparingTo(sl)
    }

    @Test
    fun `MAX_HOLD 발화 — 경과 봉 maxHoldBars 이상 (SL·TP 미도달)`() {
        val p = plan(hold = TradePlanHold(maxHoldBars = 30))
        val d = eval(p, bar("10000", "10010", "9990", "10000"), elapsedBars = 30)
        assertThat(d?.reason).isEqualTo(IntradayExitReason.MAX_HOLD)
    }

    @Test
    fun `min_hold 구간 — MAX_HOLD 보류 (SL·TP 외 청산 안 함)`() {
        val p = plan(hold = TradePlanHold(minHoldBars = 3, maxHoldBars = 30))
        // elapsedBars=2 < minHold=3 → MAX_HOLD 평가 자체가 보류(elapsed<max 라 어차피 미발화지만 보류 경로 단언).
        val d = eval(p, bar("10000", "10010", "9990", "10000"), elapsedBars = 2, minHoldBars = 3)
        assertThat(d).isNull()
    }

    @Test
    fun `min_hold 구간이라도 SL 은 항상 작동`() {
        val p = plan(stop = TradePlanStop(slPct = BigDecimal("2.0")), hold = TradePlanHold(minHoldBars = 3))
        val sl = TradePlanEvaluator.stopPriceOf(p, avg)!!
        val d = eval(p, bar("10000", "10010", sl.toPlainString(), "9990"), elapsedBars = 1, minHoldBars = 3)
        assertThat(d?.reason).isEqualTo(IntradayExitReason.SL)
    }

    @Test
    fun `min_hold 구간이라도 TP 는 항상 작동`() {
        val p = plan(target = TradePlanTarget(tpPct = BigDecimal("3.0")), hold = TradePlanHold(minHoldBars = 3))
        val tp = TradePlanEvaluator.targetPriceOf(p, avg)!!
        val d = eval(p, bar("10000", tp.toPlainString(), "9990", "10250"), elapsedBars = 1, minHoldBars = 3)
        assertThat(d?.reason).isEqualTo(IntradayExitReason.TP)
    }

    @Test
    fun `EOD 청산 — allowOvernight false 이고 봉 ts 15-15 이상이면 close 청산`() {
        val p = plan() // SL/TP/MAX_HOLD 미설정 — EOD 만 발화 가능.
        val d = eval(p, bar("10000", "10010", "9990", "10005"), allowOvernight = false, barTimeKst = LocalTime.of(15, 15))
        assertThat(d?.reason).isEqualTo(IntradayExitReason.EOD)
        assertThat(d?.triggerPrice).isEqualByComparingTo(BigDecimal("10005"))
    }

    @Test
    fun `EOD 미발화 — allowOvernight true 면 15-15 여도 청산 안 함`() {
        val p = plan()
        val d = eval(p, bar("10000", "10010", "9990", "10005"), allowOvernight = true, barTimeKst = LocalTime.of(15, 15))
        assertThat(d).isNull()
    }

    @Test
    fun `EOD 미발화 — 15-14 면 cutoff 미달`() {
        val p = plan()
        val d = eval(p, bar("10000", "10010", "9990", "10005"), allowOvernight = false, barTimeKst = LocalTime.of(15, 14))
        assertThat(d).isNull()
    }

    @Test
    fun `blackout — 09-00~09-15 진입 억제 경계`() {
        assertThat(IntradayExitSimulation.isOpenBlackout(LocalTime.of(9, 0))).isTrue() // 포함.
        assertThat(IntradayExitSimulation.isOpenBlackout(LocalTime.of(9, 14))).isTrue()
        assertThat(IntradayExitSimulation.isOpenBlackout(LocalTime.of(9, 15))).isFalse() // 미포함(end exclusive).
        assertThat(IntradayExitSimulation.isOpenBlackout(LocalTime.of(8, 59))).isFalse()
        assertThat(IntradayExitSimulation.isOpenBlackout(LocalTime.of(10, 0))).isFalse()
    }

    @Test
    fun `상수 단일 출처 — IntradayTradingClock 와 동일`() {
        assertThat(IntradayExitSimulation.EOD_LIQUIDATE_KST).isEqualTo(LocalTime.of(15, 15))
        assertThat(IntradayExitSimulation.LATE_ENTRY_CUTOFF_KST).isEqualTo(LocalTime.of(14, 30))
        assertThat(IntradayExitSimulation.OPEN_BLACKOUT_FROM).isEqualTo(LocalTime.of(9, 0))
        assertThat(IntradayExitSimulation.OPEN_BLACKOUT_TO).isEqualTo(LocalTime.of(9, 15))
    }

    @Test
    fun `hold — SL·TP·MAX_HOLD·EOD 모두 미충족이면 null`() {
        val p = plan(target = TradePlanTarget(tpPct = BigDecimal("3.0")), stop = TradePlanStop(slPct = BigDecimal("2.0")))
        val d = eval(p, bar("10000", "10010", "9990", "10000"))
        assertThat(d).isNull()
    }
}
