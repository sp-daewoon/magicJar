package kr.spdaewoon.magicjar.api.order

import kr.spdaewoon.magicjar.api.controller.order.OrderQueryController
import kr.spdaewoon.magicjar.api.service.order.OrderQueryService
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.data.domain.PageImpl
import java.time.Instant

/**
 * OrderQueryControllerTest — 결함 1 fix evidence (rejectReason DTO mapping).
 *
 * 사용자 dogfooding evidence (2026-05-18):
 *   `/api/orders` 응답에 rejectReason 필드 부재 → frontend OrdersPage 빈 화면.
 *
 * 본 테스트는 controller toListItem 매핑이 OrderRequest.rejectReason 을
 * OrderListItemDto.rejectReason 으로 정합 노출하는지 evidence 보존.
 *
 * 케이스:
 *   (1) REJECTED + rejectReason="REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND" → DTO 노출
 *   (2) REQUESTED + rejectReason=null → DTO null
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
class OrderQueryControllerTest {
    private val orderQueryService: OrderQueryService =
        Mockito.mock(OrderQueryService::class.java)

    private val sut = OrderQueryController(orderQueryService)

    private fun makeRequest(
        id: Long,
        userId: String,
        status: OrderStatus,
        rejectReason: String?,
    ) = OrderRequest(
        id = id,
        userId = userId,
        signalId = 1L,
        strategyId = 0L,
        code = "005930",
        side = OrderSide.BUY,
        priceType = OrderPriceType.MARKET,
        quantity = 1,
        limitPrice = null,
        status = status,
        mode = TradingMode.MOCK,
        idempotencyKey = "key-$id",
        rejectReason = rejectReason,
        requestedAt = Instant.parse("2026-05-18T03:35:52.174379Z"),
    )

    @Test
    fun `(1) REJECTED 주문 — rejectReason DTO 노출 (결함 1 fix evidence)`() {
        val userId = "74683223"
        val req =
            makeRequest(
                id = 2L,
                userId = userId,
                status = OrderStatus.REJECTED,
                rejectReason = "REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND",
            )
        val page = PageImpl(listOf(req))

        Mockito.doReturn(page).`when`(orderQueryService).listByUser(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
            any(),
        )

        val response = sut.list(userId, null, null, null, 0, 20)

        assertEquals(1, response.content.size)
        val item = response.content[0]
        assertEquals(2L, item.id)
        assertEquals("REJECTED", item.status)
        assertEquals("REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND", item.rejectReason)
    }

    @Test
    fun `(2) REQUESTED 주문 — rejectReason null mapping`() {
        val userId = "user1"
        val req =
            makeRequest(
                id = 1L,
                userId = userId,
                status = OrderStatus.REQUESTED,
                rejectReason = null,
            )
        val page = PageImpl(listOf(req))

        Mockito.doReturn(page).`when`(orderQueryService).listByUser(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
            any(),
        )

        val response = sut.list(userId, null, null, null, 0, 20)

        assertEquals(1, response.content.size)
        val item = response.content[0]
        assertEquals("REQUESTED", item.status)
        assertNull(item.rejectReason)
    }
}
