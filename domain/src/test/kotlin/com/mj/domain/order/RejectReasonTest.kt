package com.mj.domain.order

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RejectReason sealed class 검증. spec §4.2, ADR 0034 §2-2.
 *
 * 1. code 필드 정확성 — DB 저장 기준값
 * 2. exhaustive when — 모든 sub-class 분기 커버 (컴파일 보장 + 런타임 더블체크)
 * 3. toDetail() 비어있지 않음
 * 4. KisApiError.code 포맷
 */
class RejectReasonTest {
    @Test
    fun `all sealed subclasses have correct code values`() {
        assertEquals("KILL_SWITCH", RejectReason.KillSwitchActive.code)
        assertEquals("SESSION_CLOSED", RejectReason.MarketSessionClosed.code)
        assertEquals("NOT_BUSINESS_DAY", RejectReason.MarketNotBusinessDay.code)
        assertEquals("DAILY_LIMIT", RejectReason.DailyOrderLimitExceeded.code)
        assertEquals("POSITION_LIMIT", RejectReason.PositionLimitExceeded.code)
        assertEquals("DRAWDOWN_STOP", RejectReason.DrawdownStopTriggered.code)
        assertEquals("PRICE_SANITY", RejectReason.PriceSanityFailed.code)
    }

    @Test
    fun `KisApiError code includes rtCd`() {
        val err = RejectReason.KisApiError(rtCd = "40100000", msg = "영업일 아님")
        assertEquals("KIS_API:40100000", err.code)
    }

    @Test
    fun `Internal code is INTERNAL`() {
        val err = RejectReason.Internal(message = "DB write failure")
        assertEquals("INTERNAL", err.code)
    }

    @Test
    fun `toDetail returns non-empty for all subtypes`() {
        val reasons: List<RejectReason> =
            listOf(
                RejectReason.KillSwitchActive,
                RejectReason.MarketSessionClosed,
                RejectReason.MarketNotBusinessDay,
                RejectReason.DailyOrderLimitExceeded,
                RejectReason.PositionLimitExceeded,
                RejectReason.DrawdownStopTriggered,
                RejectReason.PriceSanityFailed,
                RejectReason.KisApiError("EGW00202", "GW 라우팅 오류"),
                RejectReason.Internal("test error"),
            )
        reasons.forEach { reason ->
            assertTrue(reason.toDetail().isNotBlank(), "toDetail() must not be blank for ${reason::class.simpleName}")
        }
    }

    @Test
    fun `exhaustive when covers all subtypes`() {
        // exhaustive when — 모든 sub-class를 명시해서 when 컴파일 검증
        fun mapCode(r: RejectReason): String =
            when (r) {
                RejectReason.KillSwitchActive -> "KILL_SWITCH"
                RejectReason.MarketSessionClosed -> "SESSION_CLOSED"
                RejectReason.MarketNotBusinessDay -> "NOT_BUSINESS_DAY"
                RejectReason.DailyOrderLimitExceeded -> "DAILY_LIMIT"
                RejectReason.PositionLimitExceeded -> "POSITION_LIMIT"
                RejectReason.DrawdownStopTriggered -> "DRAWDOWN_STOP"
                RejectReason.PriceSanityFailed -> "PRICE_SANITY"
                is RejectReason.KisApiError -> "KIS_API:${r.rtCd}"
                is RejectReason.Internal -> "INTERNAL"
            }

        val reasons: List<RejectReason> =
            listOf(
                RejectReason.KillSwitchActive,
                RejectReason.MarketSessionClosed,
                RejectReason.MarketNotBusinessDay,
                RejectReason.DailyOrderLimitExceeded,
                RejectReason.PositionLimitExceeded,
                RejectReason.DrawdownStopTriggered,
                RejectReason.PriceSanityFailed,
                RejectReason.KisApiError("40100000", "영업일 아님"),
                RejectReason.Internal("msg"),
            )
        reasons.forEach { r -> assertTrue(mapCode(r).isNotBlank()) }
    }

    @Test
    fun `KisApiError detail contains rtCd and msg`() {
        val r = RejectReason.KisApiError("EGW00202", "GW 라우팅 중 오류")
        assertTrue(r.toDetail().contains("EGW00202"))
        assertTrue(r.toDetail().contains("GW 라우팅 중 오류"))
    }

    @Test
    fun `Internal detail contains message`() {
        val r = RejectReason.Internal("unexpected NPE at KisOrderSender:42")
        assertTrue(r.toDetail().contains("NPE"))
    }
}
