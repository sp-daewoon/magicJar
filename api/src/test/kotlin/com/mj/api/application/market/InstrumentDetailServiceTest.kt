package com.mj.api.application.market

import com.mj.api.infrastructure.dart.DartFinancialAdapter
import com.mj.api.infrastructure.kis.KisInstrumentInfoAdapter
import com.mj.domain.market.instrument.InstrumentDetail
import com.mj.domain.market.instrument.port.InstrumentDetailRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * InstrumentDetailService 단위 테스트 (T20-T4).
 */
class InstrumentDetailServiceTest {
    private val detailRepository: InstrumentDetailRepository = mockk(relaxed = true)
    private val kisAdapter: KisInstrumentInfoAdapter = mockk()
    private val dartAdapter: DartFinancialAdapter = mockk()

    private val service =
        InstrumentDetailService(
            detailRepository = detailRepository,
            kisAdapter = kisAdapter,
            dartAdapter = dartAdapter,
        )

    @Test
    fun `getDetail returns cached result on cache hit`() {
        val cached =
            InstrumentDetail(
                code = "005930",
                name = "삼성전자",
                per = BigDecimal("12.34"),
            )
        every { detailRepository.find("005930") } returns cached

        val result = service.getDetail("005930", "삼성전자")

        assertEquals(cached, result)
        // KIS·DART 어댑터 호출 없어야 함
        verify(exactly = 0) { kisAdapter.fetchInstrumentInfo(any()) }
        verify(exactly = 0) { dartAdapter.fetchFinancials(any()) }
    }

    @Test
    fun `getDetail merges KIS and DART results on cache miss`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.fetchInstrumentInfo("005930") } returns
            InstrumentDetail(
                code = "005930",
                name = "삼성전자",
                market = "KOSPI",
                per = BigDecimal("12.34"),
            )
        every { dartAdapter.fetchFinancials("005930") } returns
            DartFinancialAdapter.DartFinancials(
                revenue = BigDecimal("302202000000000"),
                operatingIncome = BigDecimal("6566900000000"),
                netIncome = BigDecimal("15484700000000"),
            )

        val result = service.getDetail("005930", "삼성전자")

        assertEquals("005930", result.code)
        assertEquals("KOSPI", result.market)
        assertEquals(BigDecimal("12.34"), result.per)
        assertEquals(BigDecimal("302202000000000"), result.revenue)
        assertEquals(BigDecimal("6566900000000"), result.operatingIncome)
        // 저장 검증
        verify(exactly = 1) { detailRepository.save(any()) }
    }

    @Test
    fun `getDetail returns minimal detail when KIS key not set`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.fetchInstrumentInfo("005930") } returns null
        every { dartAdapter.fetchFinancials("005930") } returns null

        val result = service.getDetail("005930", "삼성전자")

        assertEquals("005930", result.code)
        assertEquals("삼성전자", result.name)
        assertNull(result.market)
        assertNull(result.per)
        verify(exactly = 1) { detailRepository.save(any()) }
    }

    @Test
    fun `getDetail fills revenue from DART even when KIS key not set`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.fetchInstrumentInfo("005930") } returns null
        every { dartAdapter.fetchFinancials("005930") } returns
            DartFinancialAdapter.DartFinancials(
                revenue = BigDecimal("302202000000000"),
            )

        val result = service.getDetail("005930", "삼성전자")

        assertEquals(BigDecimal("302202000000000"), result.revenue)
        assertNull(result.per)
    }
}
