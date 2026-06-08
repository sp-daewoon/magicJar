package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant as JInstant

/**
 * ADR 0115 §P1-1 R2 배선 테스트 — reconcileDrift 가 R1 [PositionDriftCorrection] 결과를 strategy_position DB 에 반영.
 *
 * incident(7 vs 5) 회귀 가드: 초과 drift → 권위(position) 정렬 UPDATE, realized_pnl 불변. 부족 → 보정 안 함(WARN 유지).
 * SELL 체결 후 호출되는 reconcileDrift 의 최종 upsert(보정)를 capture 해 단언한다.
 */
class ConsumerExecutionStrategyPositionDriftReconcileTest {
    private val port = ReconcilingStrategyPositionPort()
    private val jpaRepo: StrategyPositionJpaRepository = mock()
    private val positionRepo: PositionJpaRepository = mock()
    private val signalRepo: ConsumerSignalJpaRepository = mock()

    private val systemEventProvider: ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger> =
        mock<ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger>>().also {
            whenever(it.ifAvailable).thenReturn(null)
        }

    private fun adapter(enabled: Boolean) =
        ConsumerExecutionStrategyPositionAdapter(
            strategyPositionPort = port,
            strategyPositionJpaRepository = jpaRepo,
            positionJpaRepository = positionRepo,
            signalJpaRepository = signalRepo,
            systemEventLoggerProvider = systemEventProvider,
            driftAutoCorrectEnabled = enabled,
            clock = Clock.System,
        )

    @Test
    fun `초과 drift 7 vs position 5 — strategy_position 5로 보정 UPDATE + realized_pnl 불변`() {
        // strategy_position 7주(체결 직후 leg 합) vs KIS position 5주 권위 → 2주 초과 내림.
        port.legs =
            listOf(
                leg(strategyId = 100L, quantity = 7, realizedPnl = "30000", avgPrice = "10000"),
            )
        whenever(positionRepo.findByUserIdAndCodeAndSource(eq("user-A"), eq("005930"), any()))
            .thenReturn(positionEntity(quantity = 5))

        // SELL 체결(1주) — applySell 이 1차 upsert, 그 후 reconcileDrift 가 보정 2차 upsert.
        port.preset = port.legs.first()
        adapter(enabled = true).applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L),
            execution(quantity = 1, price = "10000"),
        )

        val corrected = port.lastUpsert!!
        assertThat(corrected.quantity).isEqualTo(5)
        // realized_pnl 은 보정으로 건드리지 않는다(보정 직전 applySell 이 산정한 값 그대로 — 수량만 정렬).
        assertThat(corrected.realizedPnl).isEqualByComparingTo(BigDecimal("30000"))
        assertThat(corrected.avgPrice).isEqualByComparingTo(BigDecimal("10000"))
    }

    @Test
    fun `초과분 전량 보정으로 0 도달 — avgPrice·filledLots·executedExitStages reset`() {
        port.legs =
            listOf(leg(strategyId = 100L, quantity = 3, realizedPnl = "5000", avgPrice = "10000", filledLots = 2, stages = setOf(0)))
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionEntity(quantity = 0))

        port.preset = port.legs.first()
        adapter(enabled = true).applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L),
            execution(quantity = 1, price = "10000"),
        )

        val corrected = port.lastUpsert!!
        assertThat(corrected.quantity).isEqualTo(0)
        assertThat(corrected.avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(corrected.filledLots).isEqualTo(0)
        assertThat(corrected.executedExitStages).isEmpty()
        // realized_pnl 은 reset 대상 아님(보정은 수량 정렬).
        assertThat(corrected.realizedPnl).isEqualByComparingTo(BigDecimal("5000"))
    }

    @Test
    fun `부족 drift — 보정하지 않음(WarnUnderflow 경로, 마지막 upsert가 보정 아님)`() {
        // strategy_position 2주 < position 5주 → 외부 매수 등 — 보정 안 함(없는 물량 인식 회피).
        port.legs = listOf(leg(strategyId = 100L, quantity = 2, realizedPnl = "0", avgPrice = "10000"))
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionEntity(quantity = 5))

        port.preset = port.legs.first()
        port.upsertCount = 0
        adapter(enabled = true).applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L),
            execution(quantity = 1, price = "10000"),
        )

        // applySell upsert 1회만 — 부족 방향은 reconcile 보정 upsert 0(WARN만).
        assertThat(port.upsertCount).isEqualTo(1)
    }

    @Test
    fun `보정 비활성(ENV off) — 초과여도 보정 upsert 0(현행 WARN-only fallback)`() {
        port.legs = listOf(leg(strategyId = 100L, quantity = 7, realizedPnl = "0", avgPrice = "10000"))
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionEntity(quantity = 5))
        whenever(jpaRepo.sumQuantityByUserAndCode(any(), any())).thenReturn(7)

        port.preset = port.legs.first()
        port.upsertCount = 0
        adapter(enabled = false).applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L),
            execution(quantity = 1, price = "10000"),
        )

        // applySell upsert 1회만 — Disabled 분기는 보정 upsert 0.
        assertThat(port.upsertCount).isEqualTo(1)
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun leg(
        strategyId: Long,
        quantity: Int,
        realizedPnl: String,
        avgPrice: String,
        filledLots: Int = 1,
        stages: Set<Int> = emptySet(),
    ): StrategyPosition =
        StrategyPosition(
            id = strategyId,
            userId = "user-A",
            strategyId = strategyId,
            code = "005930",
            quantity = quantity,
            avgPrice = BigDecimal(avgPrice),
            filledLots = filledLots,
            realizedPnl = BigDecimal(realizedPnl),
            executedExitStages = stages,
            openedAt = NOW,
            updatedAt = NOW,
        )

    private fun positionEntity(quantity: Int): PositionJpaEntity =
        PositionJpaEntity(
            userId = "user-A",
            code = "005930",
            source = "MOCK",
            quantity = quantity,
            avgPrice = BigDecimal("10000"),
            updatedAt = JInstant.EPOCH,
        )

    private fun request(
        side: OrderSide,
        strategyId: Long,
    ): OrderRequest =
        OrderRequest(
            id = 7L,
            userId = "user-A",
            signalId = 0L,
            strategyId = strategyId,
            code = "005930",
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = 1,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            idempotencyKey = "k-$side-$strategyId",
            requestedAt = JInstant.EPOCH,
        )

    private fun execution(
        quantity: Int,
        price: String,
    ): OrderExecution =
        OrderExecution(
            id = 1L,
            orderId = 7L,
            userId = "user-A",
            kisOrderNo = "ODNO1",
            executedQuantity = quantity,
            executedPrice = BigDecimal(price),
            executedAt = JInstant.EPOCH,
            createdAt = JInstant.EPOCH,
        )

    private companion object {
        val NOW: kotlinx.datetime.Instant = kotlinx.datetime.Instant.fromEpochSeconds(1_736_640_000L)
    }

    /** applySell 1차 + reconcileDrift 보정 2차 upsert 를 모두 capture. findByUserCode 는 leg 목록 반환. */
    private class ReconcilingStrategyPositionPort : StrategyPositionPort {
        var preset: StrategyPosition? = null
        var legs: List<StrategyPosition> = emptyList()
        var lastUpsert: StrategyPosition? = null
        var upsertCount: Int = 0

        override fun findByUserStrategyCode(
            userId: String,
            strategyId: Long,
            code: String,
        ): StrategyPosition? = preset

        override fun findByUserCode(
            userId: String,
            code: String,
        ): List<StrategyPosition> = legs

        override fun upsert(strategyPosition: StrategyPosition): StrategyPosition {
            lastUpsert = strategyPosition
            upsertCount++
            return strategyPosition
        }
    }
}
