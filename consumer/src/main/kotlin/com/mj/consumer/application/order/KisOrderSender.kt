package com.mj.consumer.application.order

import com.mj.consumer.application.guard.GuardChain
import com.mj.consumer.application.guard.GuardDecision
import com.mj.consumer.infrastructure.kis.KisApiException
import com.mj.domain.common.Order
import com.mj.domain.common.OrderResult
import com.mj.domain.common.OrderStatus
import com.mj.domain.order.OrderEventRecord
import com.mj.domain.order.OrderEventSource
import com.mj.domain.order.OrderEventType
import com.mj.domain.order.OrderRecord
import com.mj.domain.order.port.KisOrderPort
import com.mj.domain.order.port.OrderEventRepository
import com.mj.domain.order.port.OrderRepository
import com.mj.domain.order.port.OrderSender
import com.mj.domain.user.port.KisCredentialProvider
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

/**
 * dev/prd profile용 OrderSender. spec §3 D6, §6.2, §7.
 *
 * 흐름:
 * 1. Guard chain 통과 검증 (KillSwitch · DailyOrderLimit · PriceSanity)
 * 2. KisOrderPort.placeOrder (KIS REST 호출)
 * 3. orders + order_events(PLACED) 영속
 * 4. OrderResult.PENDING 반환 (체결은 별도 흐름 — KisExecutionNoticeListener·OrderExecutionSweep)
 *
 * Bean 등록은 OrderSenderProfileConfig에서 @Profile 분기.
 */
class KisOrderSender(
    private val kisOrderPort: KisOrderPort,
    private val guard: GuardChain,
    private val orders: OrderRepository,
    private val orderEvents: OrderEventRepository,
    private val credentials: KisCredentialProvider,
) : OrderSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(order: Order): OrderResult {
        val userId = currentUserId()
        val cred = credentials.forUser(userId)
        val accountNo = cred.accountNo
        val kisMode = cred.tradingMode.name

        when (val d = guard.check(userId, accountNo, order)) {
            is GuardDecision.Reject -> {
                log.warn("[KIS_SENDER] guard reject — order_id={} reason={}", order.id, d.reason)
                return OrderResult(
                    order = order,
                    resultStatus = OrderStatus.REJECTED,
                    filledQty = 0,
                    avgFillPrice = null,
                    filledAt = null,
                    rejectReason = d.reason,
                )
            }
            GuardDecision.Pass -> {}
        }

        val kisResult =
            try {
                kisOrderPort.placeOrder(userId, order)
            } catch (e: KisApiException) {
                val reason = "KIS_${e.category}: ${e.msgCd} ${e.msgRaw}"
                log.warn(
                    "[KIS_SENDER] KIS API exception — order_id={} category={} msg_cd={} msg={}",
                    order.id,
                    e.category,
                    e.msgCd,
                    e.msgRaw,
                )
                return OrderResult(
                    order = order,
                    resultStatus = OrderStatus.REJECTED,
                    filledQty = 0,
                    avgFillPrice = null,
                    filledAt = null,
                    rejectReason = reason,
                )
            }

        val now = OffsetDateTime.now()
        val record =
            OrderRecord(
                orderId = order.id,
                userId = userId,
                accountNo = accountNo,
                kisMode = kisMode,
                intentId = order.id,
                kisOrderNo = kisResult.kisOrderNo,
                krxFwdgOrdOrgno = kisResult.krxFwdgOrdOrgno,
                code = order.code,
                side = order.side,
                qty = order.qty,
                price = order.price,
                orderType = order.type,
                status = OrderStatus.PENDING,
                filledQty = 0,
                avgFillPrice = null,
                rejectReason = null,
                createdAt = now,
                updatedAt = now,
            )
        orders.save(record)
        orderEvents.insertIdempotent(
            OrderEventRecord(
                eventId = UUID.randomUUID(),
                orderId = record.orderId,
                userId = userId,
                accountNo = accountNo,
                kisMode = kisMode,
                kisOrderNo = record.kisOrderNo,
                eventType = OrderEventType.PLACED,
                cumulativeQty = null,
                partialQty = null,
                execPrice = null,
                source = OrderEventSource.INTERNAL,
                payload = null,
                occurredAt = now,
                ingestedAt = now,
            ),
        )

        log.info(
            "[KIS_SENDER] PLACED — order_id={} kis_order_no={} mode={} kis_time={}",
            order.id,
            record.kisOrderNo,
            kisMode,
            kisResult.orderTime,
        )

        return OrderResult(
            order = order,
            resultStatus = OrderStatus.PENDING,
            filledQty = 0,
            avgFillPrice = null,
            filledAt = null,
            rejectReason = null,
        )
    }

    private fun currentUserId(): UUID = DEFAULT_USER_ID

    companion object {
        // 인증 도입 시 SecurityContext에서 가져오도록 변경. 1차는 시드 사용자 고정.
        val DEFAULT_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
