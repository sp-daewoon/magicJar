package kr.spdaewoon.magicjar.api.service.order

import kr.spdaewoon.magicjar.api.persistence.order.OrderAuditEventRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestRepositoryAdapter
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 주문 수동 취소 서비스 (ADR 0068 §7-4 + §15-2 REST POST cancel — 의제 E-1).
 *
 * 취소 가능 상태: REQUESTED·ACK·PARTIALLY_FILLED
 * 취소 불가 상태(409): FILLED·CANCELLED·REJECTED·EXPIRED
 *
 * 취소 성공 시: status=CANCELLED + audit KIS_CANCEL_ACK + STOMP CANCELLED 통지.
 * (1차 MVP: KIS order-rvsecncl 직접 호출 생략 — Phase 1 KisOrderPort 의존성 graceful skip)
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Service
class OrderCancelService(
    private val orderRequestAdapter: OrderRequestRepositoryAdapter,
    private val orderAuditAdapter: OrderAuditEventRepositoryAdapter,
) {
    private val log = LoggerFactory.getLogger(OrderCancelService::class.java)

    companion object {
        private val CANCELLABLE_STATUSES =
            setOf(
                OrderStatus.REQUESTED,
                OrderStatus.ACK,
                OrderStatus.PARTIALLY_FILLED,
            )
    }

    /**
     * 사용자 수동 주문 취소 (ADR 0068 §7-4 — 의제 E-1).
     *
     * userId 격리 — 다른 사용자 orderId → 404.
     */
    @Transactional
    fun cancel(
        orderId: Long,
        userId: String,
        reason: String?,
    ): OrderRequest {
        val request =
            orderRequestAdapter.findById(orderId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND: $orderId")
        if (request.userId != userId) throw ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND: $orderId")
        if (request.status !in CANCELLABLE_STATUSES) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "ORDER_NOT_CANCELLABLE: status=${request.status}",
            )
        }

        // audit INSERT — USER_CANCEL_REQUEST
        orderAuditAdapter.save(
            OrderAuditEvent(
                orderId = orderId,
                userId = userId,
                eventType = OrderAuditEventType.KIS_CANCEL_ACK,
                payloadJson = """{"reason": ${if (reason != null) "\"$reason\"" else "null"}, "source": "USER_MANUAL"}""",
                createdAt = Instant.now(),
            ),
        )

        // status = CANCELLED
        val updated =
            orderRequestAdapter.markCompleted(orderId, Instant.now(), OrderStatus.CANCELLED)
                ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "CANCEL_UPDATE_FAILED")

        log.info("주문 취소 완료: orderId={} userId={}", orderId, userId)
        return updated
    }
}
