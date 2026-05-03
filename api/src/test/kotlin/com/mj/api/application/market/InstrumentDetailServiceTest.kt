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
 * InstrumentDetailService 단위 테스트 (T20-T4, inquire-price 통합 버전).
 *
 * 변경 이력:
 * - v1: getDetail → InstrumentDetail 반환
 * - v2: getDetail → DetailResult(detail, warnings) 반환
 * - v3: inquire-price (fetchPriceMetrics) 병합 추가
 *       PER·PBR·EPS·BPS·marketCap·industry·foreignRatio 검증
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

    /** 캐시 hit 시 KIS/DART 어댑터 호출 없이 즉시 반환 */
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
        assertTrue(result.warnings.isEmpty(), "캐시 hit: warnings 빈 목록")
        verify(exactly = 0) { kisAdapter.fetchInstrumentInfo(any()) }
        verify(exactly = 0) { kisAdapter.fetchPriceMetrics(any()) }
        verify(exactly = 0) { dartAdapter.fetchFinancials(any()) }
    }

    /** KIS + inquire-price + DART 3-way 병합 정상 케이스 */
    @Test
    fun `getDetail merges KIS search-stock-info and inquire-price and DART on cache miss`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns true
        every { dartAdapter.isKeyConfigured() } returns true
        every { kisAdapter.fetchInstrumentInfo("005930") } returns
            InstrumentDetail(
                code = "005930",
                name = "삼성전자",
                market = "KOSPI",
                sharesOutstanding = 5_846_278_608L,
            )
        every { kisAdapter.fetchPriceMetrics("005930") } returns
            KisInstrumentInfoAdapter.PriceMetrics(
                per = BigDecimal("33.59"),
                pbr = BigDecimal("3.45"),
                eps = BigDecimal("6564.00"),
                bps = BigDecimal("63997.00"),
                marketCap = BigDecimal("1289104400000000"), // 12891044억 * 1억
                industry = "전기·전자",
                foreignRatio = BigDecimal("49.24"),
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
        // inquire-price 필드 검증
        assertEquals(BigDecimal("33.59"), result.detail.per, "PER")
        assertEquals(BigDecimal("3.45"), result.detail.pbr, "PBR")
        assertEquals(BigDecimal("6564.00"), result.detail.eps, "EPS")
        assertEquals(BigDecimal("63997.00"), result.detail.bps, "BPS")
        assertEquals(BigDecimal("1289104400000000"), result.detail.marketCap, "시총(원)")
        assertEquals("전기·전자", result.detail.industry, "업종 한글명")
        assertEquals(BigDecimal("49.24"), result.detail.foreignRatio, "외국인 보유율")
        // DART 필드 검증
        assertEquals(BigDecimal("302202000000000"), result.detail.revenue)
        assertEquals(BigDecimal("6566900000000"), result.detail.operatingIncome)
        // 모두 정상 → warnings 없음
        assertTrue(result.warnings.isEmpty())
        verify(exactly = 1) { detailRepository.save(any()) }
    }

    /** KIS 키 미설정 시 KIS_KEY_NOT_SET + DART_KEY_NOT_SET 경고 */
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

    /** KIS 키 없어도 DART 재무정보는 정상 반환 */
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
        assertNull(result.detail.per, "KIS 키 없으면 PER null")
        assertTrue(result.warnings.contains("KIS_KEY_NOT_SET"))
        assertTrue(result.warnings.none { it.startsWith("DART_") })
    }

    /** KIS 키 설정됐으나 search-stock-info 실패 시 KIS_API_FAILED */
    @Test
    fun `getDetail returns KIS_API_FAILED warning when KIS key set but search-stock-info fails`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns true
        every { dartAdapter.isKeyConfigured() } returns true
        every { kisAdapter.fetchInstrumentInfo("005930") } returns null
        every { kisAdapter.fetchPriceMetrics("005930") } returns null
        every { dartAdapter.fetchFinancials("005930") } returns
            DartFinancialAdapter.DartFinancials(
                revenue = BigDecimal("302202000000000"),
            )

        val result = service.getDetail("005930", "삼성전자")

        assertTrue(result.warnings.contains("KIS_API_FAILED"), "KIS_API_FAILED 경고 있어야 함")
        assertEquals(BigDecimal("302202000000000"), result.detail.revenue)
    }

    /**
     * search-stock-info 정상 + inquire-price 실패 시
     * KIS_PRICE_METRICS_FAILED 경고 추가, 기본정보는 유지
     */
    @Test
    fun `getDetail adds KIS_PRICE_METRICS_FAILED warning when inquire-price fails`() {
        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns true
        every { dartAdapter.isKeyConfigured() } returns true
        every { kisAdapter.fetchInstrumentInfo("005930") } returns
            InstrumentDetail(
                code = "005930",
                name = "삼성전자",
                market = "KOSPI",
            )
        every { kisAdapter.fetchPriceMetrics("005930") } returns null
        every { dartAdapter.fetchFinancials("005930") } returns null

        val result = service.getDetail("005930", "삼성전자")

        assertEquals("KOSPI", result.detail.market, "기본정보 유지")
        assertNull(result.detail.per, "inquire-price 실패 → PER null")
        assertNull(result.detail.marketCap, "inquire-price 실패 → marketCap null")
        assertTrue(result.warnings.contains("KIS_PRICE_METRICS_FAILED"), "KIS_PRICE_METRICS_FAILED 경고")
    }

    /**
     * inquire-price에서 시가총액(hts_avls 억원→원) 변환 검증.
     * hts_avls=12891044 → marketCap=1,289,104,400,000,000원
     */
    @Test
    fun `getDetail maps marketCap from inquire-price hts_avls`() {
        val expectedMarketCap = BigDecimal("1289104400000000")

        every { detailRepository.find("005930") } returns null
        every { kisAdapter.isKeyConfigured() } returns true
        every { dartAdapter.isKeyConfigured() } returns false
        every { kisAdapter.fetchInstrumentInfo("005930") } returns
            InstrumentDetail(code = "005930", name = "삼성전자")
        every { kisAdapter.fetchPriceMetrics("005930") } returns
            KisInstrumentInfoAdapter.PriceMetrics(
                per = null,
                pbr = null,
                eps = null,
                bps = null,
                marketCap = expectedMarketCap,
                industry = "전기·전자",
                foreignRatio = null,
            )
        every { dartAdapter.fetchFinancials("005930") } returns null

        val result = service.getDetail("005930", "삼성전자")

        assertEquals(expectedMarketCap, result.detail.marketCap, "시총 억원→원 변환")
        assertEquals("전기·전자", result.detail.industry, "업종 한글명")
    }
}
