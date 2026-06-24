package kr.spdaewoon.magicjar.api.scheduler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarJpaEntity
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarJpaRepository
import kr.spdaewoon.magicjar.api.service.autopilot.AutopilotUniverseRefreshService
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional

/**
 * [AutopilotUniverseRefreshWorker] 단위 테스트 — ADR 0131.
 *
 * 검증: 게이트 off no-op · 거래일 게이트(휴장 skip) · 1일1회 멱등(같은 거래일 재발사 0) · 정상 발사 1회.
 * (worker 는 LocalDate.now(KST) 내부 사용 — calendar mock 으로 거래일 여부를 결정해 주말 무관 결정성 확보.)
 */
class AutopilotUniverseRefreshWorkerTest {
    private fun calendar(businessDay: Boolean): MarketCalendarJpaRepository {
        val repo = mockk<MarketCalendarJpaRepository>()
        every { repo.findById(any()) } answers {
            Optional.of(
                MarketCalendarJpaEntity(
                    date = firstArg(),
                    isBusinessDay = businessDay,
                    source = "MANUAL",
                ),
            )
        }
        return repo
    }

    @Test
    fun `게이트 off 면 worker no-op — refresh 호출 0`() {
        val service = mockk<AutopilotUniverseRefreshService>(relaxed = true)
        val worker =
            AutopilotUniverseRefreshWorker(
                refreshService = service,
                marketCalendarJpaRepository = calendar(businessDay = true),
                enabled = false,
            )

        worker.refreshTick()

        verify(exactly = 0) { service.refresh(any()) }
    }

    @Test
    fun `휴장일이면 skip — refresh 호출 0`() {
        val service = mockk<AutopilotUniverseRefreshService>(relaxed = true)
        val worker =
            AutopilotUniverseRefreshWorker(
                refreshService = service,
                marketCalendarJpaRepository = calendar(businessDay = false),
                enabled = true,
            )

        worker.refreshTick()

        verify(exactly = 0) { service.refresh(any()) }
    }

    @Test
    fun `게이트 on + 거래일 → refresh 1회 발사`() {
        val service = mockk<AutopilotUniverseRefreshService>()
        every { service.refresh(any()) } returns
            AutopilotUniverseRefreshService.RefreshResult(AutopilotUniverseRefreshService.RefreshStatus.UPDATED)
        val worker =
            AutopilotUniverseRefreshWorker(
                refreshService = service,
                marketCalendarJpaRepository = calendar(businessDay = true),
                enabled = true,
            )

        worker.refreshTick()

        verify(exactly = 1) { service.refresh(LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))) }
    }

    @Test
    fun `1일1회 멱등 — 같은 거래일 재발사해도 refresh 1회뿐`() {
        val service = mockk<AutopilotUniverseRefreshService>()
        every { service.refresh(any()) } returns
            AutopilotUniverseRefreshService.RefreshResult(AutopilotUniverseRefreshService.RefreshStatus.UPDATED)
        val worker =
            AutopilotUniverseRefreshWorker(
                refreshService = service,
                marketCalendarJpaRepository = calendar(businessDay = true),
                enabled = true,
            )

        worker.refreshTick()
        worker.refreshTick()
        worker.refreshTick()

        verify(exactly = 1) { service.refresh(any()) }
    }

    @Test
    fun `refresh 실패 시 당일 마킹 해제 — 다음 tick 재시도 가능`() {
        val service = mockk<AutopilotUniverseRefreshService>()
        every { service.refresh(any()) } throws RuntimeException("boom") andThen
            AutopilotUniverseRefreshService.RefreshResult(AutopilotUniverseRefreshService.RefreshStatus.UPDATED)
        val worker =
            AutopilotUniverseRefreshWorker(
                refreshService = service,
                marketCalendarJpaRepository = calendar(businessDay = true),
                enabled = true,
            )

        worker.refreshTick() // 실패 → 마킹 해제
        worker.refreshTick() // 재시도 → 성공

        verify(exactly = 2) { service.refresh(any()) }
    }
}
