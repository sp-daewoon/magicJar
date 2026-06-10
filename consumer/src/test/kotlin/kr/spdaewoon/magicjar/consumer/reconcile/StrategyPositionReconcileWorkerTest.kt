package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [StrategyPositionReconcileWorker] 단위 테스트 — ADR 0115 §P1-1 amend (2026-06-10).
 *
 * KIS 권위 보유 종목 enumeration → 종목별 strategy_position drift 정합 호출 + 종목 실패 격리 검증.
 */
class StrategyPositionReconcileWorkerTest {
    private val positionRepo: PositionJpaRepository = mock()
    private val adapter: ConsumerExecutionStrategyPositionAdapter = mock()
    private val worker = StrategyPositionReconcileWorker(positionRepo, adapter)

    @Test
    fun `KIS 보유 2종목 → 각 종목 reconcile 호출`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan("user-A", "MOCK", 0))
            .thenReturn(
                listOf(
                    PositionJpaEntity(userId = "user-A", code = "005930", quantity = 3),
                    PositionJpaEntity(userId = "user-A", code = "000660", quantity = 5),
                ),
            )

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(2)
        verify(adapter).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")
        verify(adapter).reconcileStrategyPositionDrift("user-A", "000660", "MOCK")
    }

    @Test
    fun `보유 0종목 → 호출 0`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan(any(), any(), any()))
            .thenReturn(emptyList())

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(0)
        verify(adapter, never()).reconcileStrategyPositionDrift(any(), any(), any())
    }

    @Test
    fun `한 종목 실패해도 나머지 종목 정합 진행(격리)`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan("user-A", "MOCK", 0))
            .thenReturn(
                listOf(
                    PositionJpaEntity(userId = "user-A", code = "005930", quantity = 3),
                    PositionJpaEntity(userId = "user-A", code = "000660", quantity = 5),
                ),
            )
        doThrow(RuntimeException("boom"))
            .whenever(adapter)
            .reconcileStrategyPositionDrift(eq("user-A"), eq("005930"), eq("MOCK"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        // 005930 실패해도 000660 은 정합.
        assertThat(attempted).isEqualTo(1)
        verify(adapter, times(1)).reconcileStrategyPositionDrift("user-A", "000660", "MOCK")
    }

    @Test
    fun `position 조회 자체 실패 → skip(0 반환, 예외 전파 없음)`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan(any(), any(), any()))
            .thenThrow(RuntimeException("db down"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(0)
        verify(adapter, never()).reconcileStrategyPositionDrift(any(), any(), any())
    }
}
