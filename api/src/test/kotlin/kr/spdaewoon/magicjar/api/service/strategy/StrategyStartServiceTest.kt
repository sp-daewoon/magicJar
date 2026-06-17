package kr.spdaewoon.magicjar.api.service.strategy

import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl

/**
 * 전략 시작 화면 조회 서비스 합성 검증 — spec 2026-06-18 §6.1.
 *
 * - templates: owner='SYSTEM' 조회 결과.
 * - myStrategies: owner='USER' DELETED 제외 조회 결과 + favorite 플래그 정확.
 * - favoriteIds: 즐겨찾기 strategyId 목록.
 * - SYSTEM 템플릿은 findAllByOwner("SYSTEM")로만 조회(myStrategies 경로와 분리).
 */
class StrategyStartServiceTest {
    private val strategyJpaRepository: StrategyJpaRepository = mock()
    private val favoritePort: StrategyFavoritePort = mock()
    private val service = StrategyStartService(strategyJpaRepository, favoritePort)

    private fun entity(
        id: Long,
        userId: Long?,
        owner: String,
        name: String,
    ) = StrategyJpaEntity(
        id = id,
        userId = userId,
        owner = owner,
        name = name,
        nameKo = name,
        dslEntry = "close > ma(20)",
        dslExit = null,
    )

    @Test
    fun `시작 화면은 SYSTEM 템플릿 + 내 전략 + 즐겨찾기를 합성하고 favorite 플래그가 정확하다`() {
        whenever(favoritePort.strategyIdsOf(1L)).thenReturn(listOf(10L, 99L))
        whenever(strategyJpaRepository.findAllByOwner("SYSTEM")).thenReturn(
            listOf(
                entity(99L, null, "SYSTEM", "fvg-bullish-reclaim"),
                entity(100L, null, "SYSTEM", "minervini-trend-template"),
            ),
        )
        whenever(
            strategyJpaRepository.findAllByUserIdAndStatusNot(eq(1L), eq("DELETED"), any()),
        ).thenReturn(
            PageImpl(
                listOf(
                    entity(10L, 1L, "USER", "my-strategy-a"),
                    entity(11L, 1L, "USER", "my-strategy-b"),
                ),
            ),
        )

        val res = service.load(1L)

        // 템플릿 2건, 그 중 id=99 는 즐겨찾기 true
        assertEquals(2, res.templates.size)
        assertTrue(res.templates.first { it.id == 99L }.favorite)
        assertFalse(res.templates.first { it.id == 100L }.favorite)

        // 내 전략 2건, id=10 즐겨찾기 true / id=11 false
        assertEquals(2, res.myStrategies.size)
        assertTrue(res.myStrategies.first { it.id == 10L }.favorite)
        assertFalse(res.myStrategies.first { it.id == 11L }.favorite)

        // favoriteIds 정렬 반환
        assertEquals(listOf(10L, 99L), res.favoriteIds)

        // SYSTEM 템플릿은 owner 조회 경로로만 가져온다(내 전략 경로와 분리)
        verify(strategyJpaRepository).findAllByOwner("SYSTEM")
    }

    @Test
    fun `재료 0건이면 빈 배열 (mock 합성 0)`() {
        whenever(favoritePort.strategyIdsOf(1L)).thenReturn(emptyList())
        whenever(strategyJpaRepository.findAllByOwner("SYSTEM")).thenReturn(emptyList())
        whenever(strategyJpaRepository.findAllByUserIdAndStatusNot(eq(1L), eq("DELETED"), any()))
            .thenReturn(PageImpl(emptyList()))

        val res = service.load(1L)

        assertTrue(res.templates.isEmpty())
        assertTrue(res.myStrategies.isEmpty())
        assertTrue(res.favoriteIds.isEmpty())
    }
}
