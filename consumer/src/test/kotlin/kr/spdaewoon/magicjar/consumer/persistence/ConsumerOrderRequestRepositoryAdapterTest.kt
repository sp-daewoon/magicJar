package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * ConsumerOrderRequestRepositoryAdapter 단위 테스트 (backlog §61 fix evidence).
 *
 * ### 케이스 매트릭스
 *  (1) save — REQUESTED 도메인 → JPA entity 변환 검증 (자본 path 시작점)
 *  (2) updateKisAck — id 존재 + ACK 상태 전이 검증 (자본 path KIS ACK)
 *  (3) updateStatus REJECTED — rejectReason 변환 검증 (REAL_GATE_DENIED 등)
 *  (4) findById null 분기 검증
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class ConsumerOrderRequestRepositoryAdapterTest {
    private val jpa = mockk<ConsumerOrderRequestJpaRepository>()
    private val adapter = ConsumerOrderRequestRepositoryAdapter(jpa)

    @Test
    fun `(1) save - REQUESTED 도메인을 JPA entity로 변환 + id 채워서 반환`() {
        val now = Instant.parse("2026-05-18T03:02:55Z")
        val domain =
            OrderRequest(
                userId = "74683223",
                signalId = 99001L,
                strategyId = 1L,
                code = "005930",
                side = OrderSide.BUY,
                priceType = OrderPriceType.MARKET,
                quantity = 1,
                limitPrice = null,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.MOCK,
                idempotencyKey = "99001:74683223",
                requestedAt = now,
            )
        val entitySlot = slot<ConsumerOrderRequestJpaEntity>()
        every { jpa.save(capture(entitySlot)) } answers {
            val captured = entitySlot.captured
            ConsumerOrderRequestJpaEntity(
                id = 42L,
                signalId = captured.signalId,
                userId = captured.userId,
                code = captured.code,
                side = captured.side,
                qty = captured.qty,
                priceType = captured.priceType,
                limitPrice = captured.limitPrice,
                status = captured.status,
                mode = captured.mode,
                idempotencyKey = captured.idempotencyKey,
                requestedAt = captured.requestedAt,
            )
        }

        val result = adapter.save(domain)

        verify(exactly = 1) { jpa.save(any()) }
        assertThat(entitySlot.captured.userId).isEqualTo("74683223")
        assertThat(entitySlot.captured.signalId).isEqualTo(99001L)
        assertThat(entitySlot.captured.code).isEqualTo("005930")
        assertThat(entitySlot.captured.side).isEqualTo(OrderSide.BUY)
        assertThat(entitySlot.captured.qty).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(entitySlot.captured.status).isEqualTo(OrderStatus.REQUESTED)
        assertThat(entitySlot.captured.mode).isEqualTo(TradingMode.MOCK)
        assertThat(entitySlot.captured.idempotencyKey).isEqualTo("99001:74683223")
        assertThat(result.id).isEqualTo(42L)
        assertThat(result.status).isEqualTo(OrderStatus.REQUESTED)
    }

    @Test
    fun `(2) updateKisAck - 기존 entity 발견 시 ODNO·FWDG·ACK 상태 전이`() {
        val now = Instant.parse("2026-05-18T03:02:56Z")
        val existing =
            ConsumerOrderRequestJpaEntity(
                id = 42L,
                signalId = 99001L,
                userId = "74683223",
                code = "005930",
                side = OrderSide.BUY,
                qty = BigDecimal.ONE,
                priceType = OrderPriceType.MARKET,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.MOCK,
                idempotencyKey = "99001:74683223",
                requestedAt = now.minusSeconds(1),
            )
        every { jpa.findById(42L) } returns Optional.of(existing)
        every { jpa.save(any()) } answers { firstArg() }

        val result = adapter.updateKisAck(42L, "ODNO123", "ORG001", now)

        assertThat(result).isNotNull
        assertThat(result?.kisOrderNo).isEqualTo("ODNO123")
        assertThat(result?.kisFwdgOrgno).isEqualTo("ORG001")
        assertThat(result?.status).isEqualTo(OrderStatus.ACK)
        assertThat(result?.acknowledgedAt).isEqualTo(now)
    }

    @Test
    fun `(3) updateStatus REJECTED - rejectReason 영속`() {
        val existing =
            ConsumerOrderRequestJpaEntity(
                id = 99L,
                signalId = 99001L,
                userId = "74683223",
                code = "005930",
                side = OrderSide.BUY,
                qty = BigDecimal.ONE,
                priceType = OrderPriceType.MARKET,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.REAL,
                idempotencyKey = "k1",
                requestedAt = Instant.now(),
            )
        every { jpa.findById(99L) } returns Optional.of(existing)
        every { jpa.save(any()) } answers { firstArg() }

        val result = adapter.updateStatus(99L, OrderStatus.REJECTED, "CREDENTIAL_NOT_FOUND")

        assertThat(result?.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(result?.rejectReason).isEqualTo("CREDENTIAL_NOT_FOUND")
    }

    @Test
    fun `(4) findById null 분기 - JPA empty Optional → null 반환`() {
        every { jpa.findById(404L) } returns Optional.empty()

        val result = adapter.findById(404L)

        assertThat(result).isNull()
    }
}
