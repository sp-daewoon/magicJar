package kr.spdaewoon.magicjar.consumer.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import org.junit.jupiter.api.Test

/**
 * F11 — [ActiveStrategyDemandRegistrar]가 전략 ACTIVE 시 체결가 demand와 **대칭으로 호가 구독**도
 * 보강하는지(라이브 14:48 `SIGNAL_SKIPPED_NO_QUOTE` 근본 수선) 검증.
 *
 * 체결가 경로([RealtimeSubscriptionRouter])는 기존 테스트가 다루므로, 여기선 신규 배선인
 * [OrderbookSubscriptionRouter] enter/exit 호출 정합만 본다.
 */
class ActiveStrategyDemandRegistrarOrderbookTest {
    private val realtimeRouter = mockk<RealtimeSubscriptionRouter>(relaxed = true)
    private val orderbookRouter = mockk<OrderbookSubscriptionRouter>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private fun registrar(repo: ConsumerStrategyJpaRepository) =
        ActiveStrategyDemandRegistrar(
            strategyJpaRepository = repo,
            realtimeSubscriptionRouter = realtimeRouter,
            orderbookSubscriptionRouter = orderbookRouter,
            objectMapper = objectMapper,
            enabled = true,
        )

    private fun activeStrategy(
        id: Long,
        codesJson: String,
    ): ConsumerStrategyJpaEntity =
        mockk {
            every { this@mockk.id } returns id
            every { codes } returns codesJson
        }

    @Test
    fun `전략 ACTIVE 종목은 호가 구독(onStrategyActivated)도 함께 요청한다`() {
        val repo =
            mockk<ConsumerStrategyJpaRepository> {
                every { findActiveWithExplicitCodes() } returns listOf(activeStrategy(86, """["093370"]"""))
            }

        registrar(repo).refreshActiveStrategyDemands()

        verify { orderbookRouter.onStrategyActivated(code = "093370", ownerId = "strategy:86") }
    }

    @Test
    fun `전략 대상 종목 제외 시 호가 구독도 대칭 release(onStrategyDeactivated)한다`() {
        val repo = mockk<ConsumerStrategyJpaRepository>()
        val reg = registrar(repo)

        // 1차: 093370 보유
        every { repo.findActiveWithExplicitCodes() } returns listOf(activeStrategy(86, """["093370"]"""))
        reg.refreshActiveStrategyDemands()

        // 2차: 전략에서 093370 제외(빈 codes는 null 처리되므로 다른 종목으로 교체)
        every { repo.findActiveWithExplicitCodes() } returns listOf(activeStrategy(86, """["005930"]"""))
        reg.refreshActiveStrategyDemands()

        verify { orderbookRouter.onStrategyDeactivated(code = "093370", ownerId = "strategy:86") }
        verify { orderbookRouter.onStrategyActivated(code = "005930", ownerId = "strategy:86") }
    }
}
