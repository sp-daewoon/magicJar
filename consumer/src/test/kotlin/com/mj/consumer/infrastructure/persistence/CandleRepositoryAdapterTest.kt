package com.mj.consumer.infrastructure.persistence

import com.mj.consumer.infrastructure.persistence.market.CandleJpa
import com.mj.consumer.infrastructure.persistence.market.CandleJpaRepository
import com.mj.consumer.infrastructure.persistence.market.CandleRepositoryAdapter
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CandleRepositoryAdapterTest {
    private val jpa = mockk<CandleJpaRepository>()
    private val adapter = CandleRepositoryAdapter(jpa)

    @Test
    fun `save persists candles via jpa`() {
        val candles =
            listOf(
                Candle(
                    code = "005930",
                    interval = Interval.M1,
                    openTime = Instant.now(),
                    open = BigDecimal("70000"),
                    high = BigDecimal("70500"),
                    low = BigDecimal("69900"),
                    close = BigDecimal("70200"),
                    volume = 5000L,
                    tradeCount = 200L,
                ),
            )
        every { jpa.saveAll(any<List<CandleJpa>>()) } returns emptyList()

        adapter.save(candles)

        verify(exactly = 1) { jpa.saveAll(any<List<CandleJpa>>()) }
    }

    @Test
    fun `save with empty list is no-op`() {
        adapter.save(emptyList())
        verify(exactly = 0) { jpa.saveAll(any<List<CandleJpa>>()) }
    }
}
