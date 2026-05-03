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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * InstrumentDetailService 단위 테스트 (T20-T4).
 *
 * v2 변경: getDetail → DetailResult(detail, warnings) 반환.
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

        assertEquals(cached, result.detail)
        // 캐시 hit: warnings 빈 목록
        assertTrue(result.warnings.isEmpty())
        // KIS·DART 어댑터 호출 없어야 함
        verify(exactly = 0) { kisAdapter.fetchInstrumentInfo(any()) }
        verify(exactly = 0) { dartAdapter.fetchFinancials(any()) }
    }

    @Test
    fun `getDetail merges KIS and DART results on cache miss`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns true
        every { dartAdapter.isKeyConfigured() } returns true
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

        assertEquals("005930", result.detail.code)
        assertEquals("KOSPI", result.detail.market)
        assertEquals(BigDecimal("12.34"), result.detail.per)
        assertEquals(BigDecimal("302202000000000"), result.detail.revenue)
        assertEquals(BigDecimal("6566900000000"), result.detail.operatingIncome)
        // KIS·DART 모두 정상 → warnings 없음
        assertTrue(result.warnings.isEmpty())
        // 저장 검증
        verify(exactly = 1) { detailRepository.save(any()) }
    }

    @Test
    fun `getDetail returns KIS_KEY_NOT_SET warning when KIS key not set`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns false
        every { dartAdapter.isKeyConfigured() } returns false
        every { kisAdapter.fetchInstrumentInfo("005930") } returns null
        every { dartAdapter.fetchFinancials("005930") } returns null

        val result = service.getDetail("005930", "삼성전자")

        assertEquals("005930", result.detail.code)
        assertEquals("삼성전자", result.detail.name)
        assertNull(result.detail.market)
        assertNull(result.detail.per)
        assertTrue(result.warnings.contains("KIS_KEY_NOT_SET"), "KIS_KEY_NOT_SET 경고 있어야 함")
        assertTrue(result.warnings.contains("DART_KEY_NOT_SET"), "DART_KEY_NOT_SET 경고 있어야 함")
        verify(exactly = 1) { detailRepository.save(any()) }
    }

    @Test
    fun `getDetail fills revenue from DART even when KIS key not set`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns false
        every { dartAdapter.isKeyConfigured() } returns true
        every { kisAdapter.fetchInstrumentInfo("005930") } returns null
        every { dartAdapter.fetchFinancials("005930") } returns
            DartFinancialAdapter.DartFinancials(
                revenue = BigDecimal("302202000000000"),
            )

        val result = service.getDetail("005930", "삼성전자")

        assertEquals(BigDecimal("302202000000000"), result.detail.revenue)
        assertNull(result.detail.per)
        // KIS 키 없으므로 KIS_KEY_NOT_SET, DART는 성공이므로 DART 경고 없음
        assertTrue(result.warnings.contains("KIS_KEY_NOT_SET"))
        assertTrue(result.warnings.none { it.startsWith("DART_") })
    }

    @Test
    fun `getDetail returns KIS_API_FAILED warning when KIS key set but API fails`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns true
        every { dartAdapter.isKeyConfigured() } returns true
        every { kisAdapter.fetchInstrumentInfo("005930") } returns null
        every { dartAdapter.fetchFinancials("005930") } returns
            DartFinancialAdapter.DartFinancials(
                revenue = BigDecimal("302202000000000"),
            )

        val result = service.getDetail("005930", "삼성전자")

        assertTrue(result.warnings.contains("KIS_API_FAILED"), "KIS_API_FAILED 경고 있어야 함")
        assertEquals(BigDecimal("302202000000000"), result.detail.revenue)
    }
}
