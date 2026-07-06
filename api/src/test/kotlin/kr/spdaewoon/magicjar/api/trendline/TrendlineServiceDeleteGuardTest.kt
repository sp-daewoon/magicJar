package kr.spdaewoon.magicjar.api.trendline

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.ReferencingStrategyView
import kr.spdaewoon.magicjar.api.persistence.trendline.TrendlineJpaEntity
import kr.spdaewoon.magicjar.api.persistence.trendline.TrendlineJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.api.service.strategy.StrategyTrendlineReferenceService
import kr.spdaewoon.magicjar.api.service.trendline.TrendlineService
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlineAnchorCacheInvalidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * [TrendlineService.delete] 참조 가드·강제 삭제 (ADR 0140 §3-3, spec R2).
 */
class TrendlineServiceDeleteGuardTest {
    private val trendlineRepo = mockk<TrendlineJpaRepository>(relaxed = true)
    private val candleRepo = mockk<CandleJpaRepository>(relaxed = true)
    private val instrumentRepo = mockk<InstrumentJpaRepository>(relaxed = true)
    private val userRepo = mockk<UserAccountJpaRepository>()
    private val refService = mockk<StrategyTrendlineReferenceService>(relaxed = true)
    private val strategyService = mockk<StrategyService>(relaxed = true)
    private val cacheInvalidator = mockk<TrendlineAnchorCacheInvalidator>(relaxed = true)
    private val eventLogger = mockk<SystemEventLogger>(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    private val loggerProvider = mockk<ObjectProvider<SystemEventLogger>>()

    private val service =
        TrendlineService(
            trendlineJpaRepository = trendlineRepo,
            candleJpaRepository = candleRepo,
            instrumentJpaRepository = instrumentRepo,
            userAccountJpaRepository = userRepo,
            trendlineReferenceService = refService,
            strategyService = strategyService,
            trendlineCacheInvalidator = cacheInvalidator,
            systemEventLoggerProvider = loggerProvider,
        )

    private val code = "005930"

    private fun stubOwnership() {
        val user = mockk<UserAccountJpaEntity> { every { userId } returns "u1" }
        every { userRepo.findAll(any<Sort>()) } returns listOf(user)
        every { trendlineRepo.findByIdAndUserIdAndCode(1L, "u1", code) } returns mockk<TrendlineJpaEntity>(relaxed = true)
        every { loggerProvider.getIfAvailable() } returns eventLogger
    }

    private fun refView(id: Long) =
        mockk<ReferencingStrategyView> {
            every { strategyId } returns id
            every { name } returns "추세돌파"
            every { status } returns "ACTIVE"
        }

    @Test
    fun `참조 없으면 정상 삭제 + 캐시 무효화`() {
        stubOwnership()
        every { refService.findLiveReferencing(1L) } returns emptyList()
        every { trendlineRepo.deleteByIdAndUserIdAndCode(1L, "u1", code) } returns 1

        service.delete(code, 1L, force = false)

        verify(exactly = 1) { trendlineRepo.deleteByIdAndUserIdAndCode(1L, "u1", code) }
        verify(exactly = 1) { cacheInvalidator.invalidate(1L) }
        verify(exactly = 0) { strategyService.forcePauseForTrendlineRemoval(any()) }
    }

    @Test
    fun `참조 있고 force 아니면 409 — 삭제·PAUSE 미실행`() {
        stubOwnership()
        every { refService.findLiveReferencing(1L) } returns listOf(refView(7L))

        val ex = assertThrows(ResponseStatusException::class.java) { service.delete(code, 1L, force = false) }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)

        verify(exactly = 0) { trendlineRepo.deleteByIdAndUserIdAndCode(any(), any(), any()) }
        verify(exactly = 0) { strategyService.forcePauseForTrendlineRemoval(any()) }
    }

    @Test
    fun `참조 있고 force면 참조 전략 PAUSED + P2 알림 + 참조 정리 + 삭제`() {
        stubOwnership()
        every { refService.findLiveReferencing(1L) } returns listOf(refView(7L), refView(8L))
        every { trendlineRepo.deleteByIdAndUserIdAndCode(1L, "u1", code) } returns 1

        service.delete(code, 1L, force = true)

        verify(exactly = 1) { strategyService.forcePauseForTrendlineRemoval(7L) }
        verify(exactly = 1) { strategyService.forcePauseForTrendlineRemoval(8L) }
        verify(exactly = 1) { eventLogger.emit(any()) }
        verify(exactly = 1) { refService.clearByTrendlineId(1L) }
        verify(exactly = 1) { trendlineRepo.deleteByIdAndUserIdAndCode(1L, "u1", code) }
        verify(exactly = 1) { cacheInvalidator.invalidate(1L) }
    }

    @Test
    fun `존재하지 않는 선은 404`() {
        val user = mockk<UserAccountJpaEntity> { every { userId } returns "u1" }
        every { userRepo.findAll(any<Sort>()) } returns listOf(user)
        every { trendlineRepo.findByIdAndUserIdAndCode(2L, "u1", code) } returns null

        val ex = assertThrows(ResponseStatusException::class.java) { service.delete(code, 2L, force = false) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
