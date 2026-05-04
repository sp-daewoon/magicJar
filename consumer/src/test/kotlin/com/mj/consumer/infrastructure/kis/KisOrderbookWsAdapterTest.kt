package com.mj.consumer.infrastructure.kis

import com.mj.consumer.messaging.producer.OrderbookPublisher
import com.mj.domain.market.event.OrderbookEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * KisOrderbookWsAdapter 단위 테스트 — plan T2-R2-A2 §T11.
 */
class KisOrderbookWsAdapterTest {
    private val publisher = mockk<OrderbookPublisher>(relaxed = true)
    private val adapter = KisOrderbookWsAdapter(publisher)

    private fun makeColumns(
        code: String = "005930",
        time: String = "143015",
    ): List<String> {
        // 50 columns 채움 (KIS 호가 frame ~58 columns 가정)
        val cols = MutableList(50) { "" }
        cols[0] = code
        cols[1] = time
        // ASKP1~ASKP10 (idx 3..12)
        for (i in 0 until 10) cols[3 + i] = (75000 + i * 100).toString()
        // BIDP1~BIDP10 (idx 13..22) — 매수1이 가장 높음
        for (i in 0 until 10) cols[13 + i] = (74900 - i * 100).toString()
        // ASKP_RSQN1~10 (idx 23..32)
        for (i in 0 until 10) cols[23 + i] = (1000 + i * 100).toString()
        // BIDP_RSQN1~10 (idx 33..42)
        for (i in 0 until 10) cols[33 + i] = (2000 + i * 100).toString()
        cols[43] = "55000" // total ask qty
        cols[44] = "65000" // total bid qty
        return cols
    }

    @Test
    fun `정상 frame — OrderbookEvent publish`() {
        val captured = slot<OrderbookEvent>()
        every { publisher.publish(capture(captured)) } just Runs

        adapter.onFrame(KisOrderbookWsAdapter.TR_ID, "005930", makeColumns())

        verify(exactly = 1) { publisher.publish(any()) }
        val ev = captured.captured
        assert(ev.code == "005930")
        assert(ev.asks.size == 10)
        assert(ev.bids.size == 10)
        assert(ev.asks[0].price == BigDecimal("75000"))
        assert(ev.asks[9].price == BigDecimal("75900"))
        assert(ev.bids[0].price == BigDecimal("74900"))
        assert(ev.bids[9].price == BigDecimal("74000"))
        assert(ev.asks[0].quantity == 1000L)
        assert(ev.bids[0].quantity == 2000L)
        assert(ev.totalAskQty == 55000L)
        assert(ev.totalBidQty == 65000L)
        assert(ev.source == "KIS_WS")
        assert(!ev.stale)
    }

    @Test
    fun `다른 trId — publish skip`() {
        adapter.onFrame("H0UNCNT0", "005930", makeColumns())
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `MIN_COLUMNS 미만 — warn + skip`() {
        adapter.onFrame(KisOrderbookWsAdapter.TR_ID, "005930", listOf("005930", "143015"))
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `잘못된 가격 (blank) — 0으로 fallback + publish 진행`() {
        val cols = makeColumns().toMutableList()
        cols[3] = "" // ASKP1 blank
        val captured = slot<OrderbookEvent>()
        every { publisher.publish(capture(captured)) } just Runs

        adapter.onFrame(KisOrderbookWsAdapter.TR_ID, "005930", cols)

        verify(exactly = 1) { publisher.publish(any()) }
        assert(captured.captured.asks[0].price == BigDecimal.ZERO)
    }
}
