package kr.spdaewoon.magicjar.api.persistence.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant

/**
 * StrategyFavorite 어댑터 멱등 검증 — spec 2026-06-18 §5.2 / §6.2.
 *
 * - add: 이미 존재하면 insert 안 함(존재 체크 멱등).
 * - add: 미존재면 insert 1회.
 * - add: 동시 race로 UNIQUE 충돌(DataIntegrityViolationException) 시 흡수(no-op·전파 안 함).
 * - remove: deleteByUserIdAndStrategyId 위임.
 * - strategyIdsOf: findAllByUserId 결과의 strategyId 추출.
 */
class StrategyFavoriteJpaAdapterTest {
    private val repository: StrategyFavoriteJpaRepository = mock()
    private val adapter = StrategyFavoriteJpaAdapter(repository)

    @Test
    fun `add 는 이미 즐겨찾기면 insert 하지 않는다 (멱등)`() {
        whenever(repository.existsByUserIdAndStrategyId(1L, 5L)).thenReturn(true)

        adapter.add(1L, 5L)

        verify(repository, never()).save(org.mockito.kotlin.any())
    }

    @Test
    fun `add 는 미존재면 insert 1회`() {
        whenever(repository.existsByUserIdAndStrategyId(1L, 5L)).thenReturn(false)
        whenever(repository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0] }

        adapter.add(1L, 5L)

        verify(repository, times(1)).save(org.mockito.kotlin.any())
    }

    @Test
    fun `add 는 동시 race UNIQUE 충돌을 멱등 흡수한다`() {
        whenever(repository.existsByUserIdAndStrategyId(1L, 5L)).thenReturn(false)
        whenever(repository.save(org.mockito.kotlin.any())).thenThrow(DataIntegrityViolationException("ux violation"))

        // 예외 전파 없이 정상 종료해야 한다.
        adapter.add(1L, 5L)

        verify(repository, times(1)).save(org.mockito.kotlin.any())
    }

    @Test
    fun `remove 는 deleteByUserIdAndStrategyId 에 위임`() {
        whenever(repository.deleteByUserIdAndStrategyId(1L, 5L)).thenReturn(1)

        adapter.remove(1L, 5L)

        verify(repository, times(1)).deleteByUserIdAndStrategyId(1L, 5L)
    }

    @Test
    fun `strategyIdsOf 는 findAllByUserId 결과의 strategyId 를 추출`() {
        whenever(repository.findAllByUserId(1L)).thenReturn(
            listOf(
                StrategyFavoriteJpaEntity(id = 1L, userId = 1L, strategyId = 5L, createdAt = Instant.now()),
                StrategyFavoriteJpaEntity(id = 2L, userId = 1L, strategyId = 8L, createdAt = Instant.now()),
            ),
        )

        assertEquals(listOf(5L, 8L), adapter.strategyIdsOf(1L))
    }
}
