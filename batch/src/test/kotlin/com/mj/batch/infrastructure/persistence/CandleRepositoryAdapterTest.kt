package com.mj.batch.infrastructure.persistence

import com.mj.batch.infrastructure.persistence.market.CandleJpa
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.batch.infrastructure.persistence.market.CandleRepositoryAdapter
import com.mj.domain.common.Interval
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CandleRepositoryAdapterTest {
    private val jpa = mockk<CandleJpaRepository>()
    private val adapter = CandleRepositoryAdapter(jpa)

    @Test
    fun `find returns candles for given range`() {
        val from = Instant.parse("2024-01-01T00:00:00Z")
        val to = Instant.parse("2024-01-02T00:00:00Z")
        val openTime = Instant.parse("2024-01-01T09:00:00Z")

        every { jpa.findByCodeAndIntervalAndOpenTimeBetween("005930", Interval.D1, from, to) } returns
            listOf(
                CandleJpa(
                    code = "005930",
                    interval = Interval.D1,
                    openTime = openTime,
                    open = BigDecimal("70000"),
                    high = BigDecimal("71000"),
                    low = BigDecimal("69500"),
                    close = BigDecimal("70500"),
                    volume = 100000L,
                    tradeCount = 5000L,
                ),
            )

        val result = adapter.find("005930", Interval.D1, from, to)

        assertEquals(1, result.size)
        assertEquals("005930", result[0].code)
        assertEquals(BigDecimal("70000"), result[0].open)
    }

    @Test
    fun `save is no-op in batch (read-only)`() {
        // batch는 캔들 쓰기를 하지 않음 — consumer 담당
        adapter.save(emptyList())
        // no exception = pass
    }
}
