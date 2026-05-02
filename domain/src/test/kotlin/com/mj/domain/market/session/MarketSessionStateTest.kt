package com.mj.domain.market.session

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MarketSessionState sealed class 단위 테스트. spec §4.1, ADR 0032.
 *
 * 검증 항목:
 * 1. isTradingAllowed() — Open만 true
 * 2. isOrderAcceptable() — Open·PreMarket·PostMarket true, Closed·Holiday false
 * 3. sealed exhaustive when 컴파일 통과 (if 컴파일 에러 없으면 OK)
 */
class MarketSessionStateTest {
    @Test
    fun `isTradingAllowed - Open만 true`() {
        assertTrue(MarketSessionState.Open.isTradingAllowed())
        assertFalse(MarketSessionState.PreMarket.isTradingAllowed())
        assertFalse(MarketSessionState.PostMarket.isTradingAllowed())
        assertFalse(MarketSessionState.Closed("Saturday").isTradingAllowed())
        assertFalse(MarketSessionState.Holiday("어린이날", "PUBLIC_HOLIDAY").isTradingAllowed())
    }

    @Test
    fun `isOrderAcceptable - Open·PreMarket·PostMarket은 true`() {
        assertTrue(MarketSessionState.Open.isOrderAcceptable())
        assertTrue(MarketSessionState.PreMarket.isOrderAcceptable())
        assertTrue(MarketSessionState.PostMarket.isOrderAcceptable())
        assertFalse(MarketSessionState.Closed("Saturday").isOrderAcceptable())
        assertFalse(MarketSessionState.Holiday("어린이날", "PUBLIC_HOLIDAY").isOrderAcceptable())
    }

    @Test
    fun `sealed when exhaustive - 모든 케이스 처리 시 컴파일 OK`() {
        // exhaustive when — 컴파일 시 모든 sealed case를 커버해야 함
        val states: List<MarketSessionState> =
            listOf(
                MarketSessionState.PreMarket,
                MarketSessionState.Open,
                MarketSessionState.PostMarket,
                MarketSessionState.Closed("Saturday"),
                MarketSessionState.Holiday("어린이날", "PUBLIC_HOLIDAY"),
            )
        states.forEach { state ->
            val label: String =
                when (state) {
                    MarketSessionState.Open -> "open"
                    MarketSessionState.PreMarket -> "pre"
                    MarketSessionState.PostMarket -> "post"
                    is MarketSessionState.Closed -> "closed:${state.reason}"
                    is MarketSessionState.Holiday -> "holiday:${state.name}"
                }
            assertTrue(label.isNotBlank(), "모든 상태에 대해 label 반환 확인: $state")
        }
    }

    @Test
    fun `Closed reason 보존 검증`() {
        val closed = MarketSessionState.Closed("Saturday")
        assertTrue(closed.reason == "Saturday")
        assertFalse(closed.isTradingAllowed())
    }

    @Test
    fun `Holiday name·reason 보존 검증`() {
        val holiday = MarketSessionState.Holiday("어린이날", "PUBLIC_HOLIDAY")
        assertTrue(holiday.name == "어린이날")
        assertTrue(holiday.reason == "PUBLIC_HOLIDAY")
        assertFalse(holiday.isTradingAllowed())
    }
}
