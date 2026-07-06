package kr.spdaewoon.magicjar.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [TrendlineStopRestamp] 순수 재박제 판정 단위 테스트 — ADR 0140 §3-4 R2b.
 *
 * 커버: 최초 박제 · 상향 채택 · 손실구간 하향 추종 · 이익잠금 하향 거부(래칫 가드) · 선 부재 유지 · 무효 유지 ·
 * 무변 유지 · 호가단위 내림.
 */
class TrendlineStopRestampTest {
    private val entry = BigDecimal("10000") // 진입가(tick 10 구간).

    private fun newPriceOf(d: TrendlineStopRestamp.Decision): BigDecimal {
        assertTrue(d is TrendlineStopRestamp.Decision.NewPrice, "expected NewPrice but was $d")
        return (d as TrendlineStopRestamp.Decision.NewPrice).slPrice
    }

    private fun keepReasonOf(d: TrendlineStopRestamp.Decision): TrendlineStopRestamp.KeepReason {
        assertTrue(d is TrendlineStopRestamp.Decision.Keep, "expected Keep but was $d")
        return (d as TrendlineStopRestamp.Decision.Keep).reason
    }

    @Test
    fun `최초 박제 — current null 이면 선 가격을 호가단위 내림해 첫 박제`() {
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = null, linePrice = BigDecimal("9500"))
        assertEquals(0, newPriceOf(d).compareTo(BigDecimal("9500")))
    }

    @Test
    fun `우상향 선 — 후보가 현재보다 높으면 상향 채택(트레일링 유사)`() {
        // 진입 9500 손절 → 선이 우상향해 9700 → 손절 9700 으로 조임.
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("9500"), linePrice = BigDecimal("9700"))
        assertEquals(0, newPriceOf(d).compareTo(BigDecimal("9700")))
    }

    @Test
    fun `손실 구간 하향 — 현재 손절이 진입가 아래면 선을 따라 하향 추종`() {
        // current 9500 < entry 10000(손실 구간). 선이 9300 으로 하락 → 사용자 지정 선 충실 추종.
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("9500"), linePrice = BigDecimal("9300"))
        assertEquals(0, newPriceOf(d).compareTo(BigDecimal("9300")))
    }

    @Test
    fun `이익 잠금 하향 금지 — 현재 손절이 진입가 이상이면 하향 재박제 거부(래칫)`() {
        // current 10200 >= entry 10000(이익 잠금). 선이 10100 으로 내려가도 되돌리지 않음(손실 확대 방향 금지).
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("10200"), linePrice = BigDecimal("10100"))
        assertEquals(TrendlineStopRestamp.KeepReason.LOCKED_ABOVE_ENTRY, keepReasonOf(d))
    }

    @Test
    fun `이익 잠금 상향 — 진입가 이상이어도 상향은 계속 채택`() {
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("10200"), linePrice = BigDecimal("10400"))
        assertEquals(0, newPriceOf(d).compareTo(BigDecimal("10400")))
    }

    @Test
    fun `선 부재 — linePrice null 이면 마지막 박제값 유지(§11-2 caller WARN)`() {
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("9500"), linePrice = null)
        assertEquals(TrendlineStopRestamp.KeepReason.LINE_UNAVAILABLE, keepReasonOf(d))
    }

    @Test
    fun `무효 선 가격 — 호가단위 내림 결과 0 이하면 유지`() {
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("9500"), linePrice = BigDecimal("0.4"))
        assertEquals(TrendlineStopRestamp.KeepReason.INVALID_LINE_PRICE, keepReasonOf(d))
    }

    @Test
    fun `무변 — 호가단위 내림 후 현재와 동일하면 write 절약 유지`() {
        // 9505 → floorToTick(tick 10) = 9500 == current 9500.
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("9500"), linePrice = BigDecimal("9505"))
        assertEquals(TrendlineStopRestamp.KeepReason.NO_CHANGE, keepReasonOf(d))
    }

    @Test
    fun `호가단위 내림 — 선 가격이 tick 위가 아니면 내림 정렬`() {
        // 9507 → tick 10 내림 = 9500(> current 9400 이므로 상향 채택).
        val d = TrendlineStopRestamp.decide(entry, currentSlPrice = BigDecimal("9400"), linePrice = BigDecimal("9507"))
        assertEquals(0, newPriceOf(d).compareTo(BigDecimal("9500")))
    }
}
