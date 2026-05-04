package com.mj.consumer.infrastructure.kis

import com.mj.consumer.messaging.producer.IndexPublisher
import com.mj.domain.market.event.IndexTickEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * KisIndexWsAdapter 단위 테스트 — plan T2-R2-A2 §T12.
 */
class KisIndexWsAdapterTest {
    private val publisher = mockk<IndexPublisher>(relaxed = true)
    private val adapter = KisIndexWsAdapter(publisher)

    private fun makeColumns(
        code: String = IndexTickEvent.KOSPI,
        time: String = "143015",
        value: String = "2585.43",
        change: String = "12.50",
        volume: String = "120000000",
        turnover: String = "8500000000000",
        rate: String = "0.49",
    ): List<String> {
        // idx 0..9 — 추후 컬럼 추가에도 호환되도록 30개 채움
        val cols = MutableList(30) { "" }
        cols[0] = code
        cols[1] = time
        cols[2] = value
        cols[3] = "" // 부호 등 (미사용)
        cols[4] = change
        cols[5] = volume
        cols[6] = turnover
        cols[7] = ""
        cols[8] = ""
        cols[9] = rate
        return cols
    }

    @Test
    fun `정상 30 columns — IndexTickEvent publish`() {
        val captured = slot<IndexTickEvent>()
        every { publisher.publish(capture(captured)) } just Runs

        adapter.onFrame(KisIndexWsAdapter.TR_ID, IndexTickEvent.KOSPI, makeColumns())

        verify(exactly = 1) { publisher.publish(any()) }
        val ev = captured.captured
        assert(ev.code == IndexTickEvent.KOSPI)
        assert(ev.value == BigDecimal("2585.43"))
        assert(ev.change == BigDecimal("12.50"))
        assert(ev.changeRate == BigDecimal("0.49"))
        assert(ev.volume == 120000000L)
        assert(ev.turnover == 8500000000000L)
        assert(ev.source == "KIS_WS")
        assert(!ev.stale)
    }

    @Test
    fun `다른 trId — publish skip`() {
        adapter.onFrame("H0UNCNT0", IndexTickEvent.KOSPI, makeColumns())
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `MIN_COLUMNS 미만 — warn + skip`() {
        adapter.onFrame(KisIndexWsAdapter.TR_ID, IndexTickEvent.KOSPI, listOf("0001", "143015"))
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `value=0 — skip (지수 0은 무의미)`() {
        adapter.onFrame(KisIndexWsAdapter.TR_ID, IndexTickEvent.KOSPI, makeColumns(value = "0"))
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `value 파싱 실패 — skip`() {
        adapter.onFrame(KisIndexWsAdapter.TR_ID, IndexTickEvent.KOSPI, makeColumns(value = "abc"))
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `change 파싱 실패 — 0으로 fallback + publish 진행`() {
        val captured = slot<IndexTickEvent>()
        every { publisher.publish(capture(captured)) } just Runs

        adapter.onFrame(KisIndexWsAdapter.TR_ID, IndexTickEvent.KOSDAQ, makeColumns(code = "1001", change = "INVALID"))

        verify(exactly = 1) { publisher.publish(any()) }
        assert(captured.captured.change == BigDecimal.ZERO)
        assert(captured.captured.code == IndexTickEvent.KOSDAQ)
    }
}
