package com.mj.api.application.market

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * EvictionScheduler 단위 테스트 (T19-T7).
 *
 * - runEviction 호출 시 CacheEvictionService.evictIfNeeded() 1회 호출 확인
 * - 서비스에서 예외 발생 시 삼켜서 스케줄러 중단 방지 확인
 */
class EvictionSchedulerTest {
    private val cacheEvictionService: CacheEvictionService = mockk()

    private val sut = EvictionScheduler(cacheEvictionService)

    @Test
    fun `runEviction 호출 시 evictIfNeeded 1회 호출`() {
        every { cacheEvictionService.evictIfNeeded() } returns
            CacheEvictionService.EvictionResult(
                evictedCount = 0,
                reclaimedBytes = 0L,
            )

        sut.runEviction()

        verify(exactly = 1) { cacheEvictionService.evictIfNeeded() }
    }

    @Test
    fun `evictIfNeeded 예외 발생 시 스케줄러 예외 전파 없이 정상 완료`() {
        every { cacheEvictionService.evictIfNeeded() } throws RuntimeException("DB 연결 실패")

        // 예외가 전파되지 않아야 함 (스케줄러 중단 방지)
        sut.runEviction()

        verify(exactly = 1) { cacheEvictionService.evictIfNeeded() }
    }
}
