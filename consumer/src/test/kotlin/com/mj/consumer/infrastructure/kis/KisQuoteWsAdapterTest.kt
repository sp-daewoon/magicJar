package com.mj.consumer.infrastructure.kis

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.event.TickEvent
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * KisQuoteWsAdapter 단위 테스트.
 *
 * plan §9-1 T4 (2026-05-04-track2-r2-quote-ws.md).
 */
class KisQuoteWsAdapterTest {
    private val publisher = mockk<TickPublisher>(relaxed = true)
    private lateinit var adapter: KisQuoteWsAdapter

    @BeforeEach
    fun setUp() {
        adapter = KisQuoteWsAdapter(publisher)
    }

    /** 정상 컬럼 입력 → TickPublisher.publish 1회 호출 + 매핑 정확성 검증 */
    @Test
    fun `H0UNCNT0 frame 정상 처리 — TickPublisher 호출`() {
        val columns =
            buildColumns(
                code = "005930",
                timeHHmmss = "093045",
                price = "75000",
                volume = "500",
            )

        adapter.onFrame("H0UNCNT0", "005930", columns)

        val captured = slot<TickEvent>()
        verify(exactly = 1) { publisher.publish(capture(captured)) }

        captured.captured.code shouldBe "005930"
        captured.captured.price shouldBe BigDecimal("75000")
        captured.captured.volume shouldBe 500L
        captured.captured.stale shouldBe false
        captured.captured.source shouldBe "KIS_WS"
    }

    /** 잘못된 trId — 무시 */
    @Test
    fun `H0UNASP0 trId 는 무시`() {
        val columns = buildColumns("005930", "093045", "75000", "100")
        adapter.onFrame("H0UNASP0", "005930", columns)
        verify(exactly = 0) { publisher.publish(any()) }
    }

    /** 컬럼 수 부족 — warn + skip */
    @Test
    fun `컬럼 수 부족 시 publish 안 함`() {
        val shortColumns = listOf("005930", "093045", "75000") // 3개만
        adapter.onFrame("H0UNCNT0", "005930", shortColumns)
        verify(exactly = 0) { publisher.publish(any()) }
    }

    /** 가격 0 — skip */
    @Test
    fun `가격 0 이면 publish 안 함`() {
        val columns = buildColumns("005930", "093045", "0", "100")
        adapter.onFrame("H0UNCNT0", "005930", columns)
        verify(exactly = 0) { publisher.publish(any()) }
    }

    /** 가격 파싱 실패 — skip */
    @Test
    fun `가격 파싱 실패 시 publish 안 함`() {
        val columns = buildColumns("005930", "093045", "NOT_A_NUMBER", "100")
        adapter.onFrame("H0UNCNT0", "005930", columns)
        verify(exactly = 0) { publisher.publish(any()) }
    }

    /** 거래량 파싱 실패 → 0 으로 처리 후 publish */
    @Test
    fun `거래량 파싱 실패 시 volume=0 으로 처리`() {
        val columns = buildColumns("005930", "093045", "75000", "INVALID_VOL")
        adapter.onFrame("H0UNCNT0", "005930", columns)

        val captured = slot<TickEvent>()
        verify(exactly = 1) { publisher.publish(capture(captured)) }
        captured.captured.volume shouldBe 0L
    }

    /** at 시각 — KST 오늘 HHmmss 기반으로 생성 (부초 정도만 검증) */
    @Test
    fun `at 시각 파싱 — KST HHmmss`() {
        val columns = buildColumns("005930", "150000", "75000", "100")
        adapter.onFrame("H0UNCNT0", "005930", columns)

        val captured = slot<TickEvent>()
        verify(exactly = 1) { publisher.publish(capture(captured)) }
        // at 은 non-null Instant (TickEvent 필드). 값이 존재하고 합리적인 범위인지만 확인.
        val at = captured.captured.at
        assert(at.epochSecond > 0) { "at epoch must be positive" }
    }

    private fun buildColumns(
        code: String,
        timeHHmmss: String,
        price: String,
        volume: String,
    ): List<String> {
        // H0UNCNT0 47 컬럼 — idx 0,1,2 필수, idx 12=체결량, idx 13=누적
        val cols = MutableList(47) { "" }
        cols[0] = code
        cols[1] = timeHHmmss
        cols[2] = price
        cols[12] = volume
        cols[13] = "9999999"
        return cols
    }
}
