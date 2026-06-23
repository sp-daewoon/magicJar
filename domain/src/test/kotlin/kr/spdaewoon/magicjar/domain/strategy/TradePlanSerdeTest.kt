package kr.spdaewoon.magicjar.domain.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [TradePlanSerde] round-trip + null 하위호환 + BigDecimal 양측 파싱 — ADR 0130 L2 R1.
 */
class TradePlanSerdeTest {
    private fun fullPlan() =
        TradePlan(
            entry =
                TradePlanEntry(
                    strategyId = 105,
                    thesis = "vwap reclaim + vol surge",
                    entryPrice = BigDecimal("12340"),
                    bar = "1m",
                    openedAt = "2026-06-24T01:12:00Z",
                ),
            target = TradePlanTarget(tpPct = BigDecimal("3.0"), tpPrice = BigDecimal("12710")),
            stop = TradePlanStop(type = TradePlanStopType.FIXED, slPct = BigDecimal("2.0"), slPrice = BigDecimal("12093")),
            trailing = TradePlanTrailing(enabled = true, activatePct = BigDecimal("1.5"), trailPct = BigDecimal("1.0")),
            hold = TradePlanHold(minHoldBars = 3, maxHoldBars = 30),
            exitConditions = listOf(TradePlanExitCondition(rule = "vwap_cross_down", params = mapOf("src" to "1m"))),
            contingency = TradePlanContingency(viAction = "EXIT_MARKET", eod = "LIQUIDATE"),
            status = TradePlanStatus.OPEN,
            exitReason = null,
        )

    @Test
    fun `전체 필드 round-trip — toMap to fromMap 동치`() {
        val plan = fullPlan()
        val restored = TradePlanSerde.fromMap(TradePlanSerde.toMap(plan))
        assertEquals(plan, restored)
    }

    @Test
    fun `최소 plan round-trip — entry만 있고 나머지 null`() {
        val plan = TradePlan(entry = TradePlanEntry(strategyId = 7, entryPrice = BigDecimal("1000")))
        val restored = TradePlanSerde.fromMap(TradePlanSerde.toMap(plan))
        assertEquals(plan, restored)
        assertNull(restored?.target)
        assertNull(restored?.stop)
        assertNull(restored?.trailing)
        assertNull(restored?.hold)
        assertTrue(restored?.exitConditions?.isEmpty() == true)
        assertNull(restored?.contingency)
    }

    @Test
    fun `null map과 빈 map은 null TradePlan(미기록=현행 동작)`() {
        assertNull(TradePlanSerde.fromMap(null))
        assertNull(TradePlanSerde.fromMap(emptyMap()))
    }

    @Test
    fun `entry 부재면 null(계획 무효 — caller 현행 경로 유지)`() {
        assertNull(TradePlanSerde.fromMap(mapOf(TradePlanSerde.KEY_STATUS to "OPEN")))
        // entry 안에 entryPrice 없으면 무효
        assertNull(
            TradePlanSerde.fromMap(
                mapOf(TradePlanSerde.KEY_ENTRY to mapOf(TradePlanSerde.KEY_ENTRY_STRATEGY_ID to 1)),
            ),
        )
    }

    @Test
    fun `BigDecimal 숫자·문자 양쪽 표현 허용 — jackson number 또는 string 둘 다 파싱`() {
        val fromString =
            TradePlanSerde.fromMap(
                mapOf(
                    TradePlanSerde.KEY_ENTRY to
                        mapOf(
                            TradePlanSerde.KEY_ENTRY_STRATEGY_ID to "105",
                            TradePlanSerde.KEY_ENTRY_PRICE to "12340",
                        ),
                    TradePlanSerde.KEY_TARGET to mapOf(TradePlanSerde.KEY_TP_PCT to "3.0"),
                    TradePlanSerde.KEY_STOP to mapOf(TradePlanSerde.KEY_SL_PCT to "2.0"),
                ),
            )
        val fromNumber =
            TradePlanSerde.fromMap(
                mapOf(
                    TradePlanSerde.KEY_ENTRY to
                        mapOf(
                            TradePlanSerde.KEY_ENTRY_STRATEGY_ID to 105,
                            TradePlanSerde.KEY_ENTRY_PRICE to 12340,
                        ),
                    TradePlanSerde.KEY_TARGET to mapOf(TradePlanSerde.KEY_TP_PCT to 3.0),
                    TradePlanSerde.KEY_STOP to mapOf(TradePlanSerde.KEY_SL_PCT to BigDecimal("2.0")),
                ),
            )
        assertEquals(105L, fromString?.entry?.strategyId)
        assertTrue(fromString?.entry?.entryPrice?.compareTo(BigDecimal("12340")) == 0)
        assertTrue(fromString?.target?.tpPct?.compareTo(BigDecimal("3.0")) == 0)
        assertEquals(105L, fromNumber?.entry?.strategyId)
        assertTrue(fromNumber?.entry?.entryPrice?.compareTo(BigDecimal("12340")) == 0)
        assertTrue(fromNumber?.stop?.slPct?.compareTo(BigDecimal("2.0")) == 0)
    }

    @Test
    fun `status·stopType 미상값은 default 폴백`() {
        val plan =
            TradePlanSerde.fromMap(
                mapOf(
                    TradePlanSerde.KEY_ENTRY to
                        mapOf(
                            TradePlanSerde.KEY_ENTRY_STRATEGY_ID to 1,
                            TradePlanSerde.KEY_ENTRY_PRICE to 1000,
                        ),
                    TradePlanSerde.KEY_STATUS to "UNKNOWN_STATUS",
                    TradePlanSerde.KEY_STOP to
                        mapOf(
                            TradePlanSerde.KEY_STOP_TYPE to "UNKNOWN_TYPE",
                            TradePlanSerde.KEY_SL_PCT to 2.0,
                        ),
                ),
            )
        assertEquals(TradePlanStatus.OPEN, plan?.status)
        assertEquals(TradePlanStopType.FIXED, plan?.stop?.type)
    }

    @Test
    fun `exitConditions 빈 리스트는 키 생략 — round-trip 보존`() {
        val plan = fullPlan().copy(exitConditions = emptyList())
        val map = TradePlanSerde.toMap(plan)
        assertTrue(!map.containsKey(TradePlanSerde.KEY_EXIT_CONDITIONS))
        val restored = TradePlanSerde.fromMap(map)
        assertTrue(restored?.exitConditions?.isEmpty() == true)
    }

    @Test
    fun `CLOSING status + exitReason round-trip`() {
        val plan = fullPlan().copy(status = TradePlanStatus.CLOSING, exitReason = "TP")
        val restored = TradePlanSerde.fromMap(TradePlanSerde.toMap(plan))
        assertEquals(TradePlanStatus.CLOSING, restored?.status)
        assertEquals("TP", restored?.exitReason)
    }
}
