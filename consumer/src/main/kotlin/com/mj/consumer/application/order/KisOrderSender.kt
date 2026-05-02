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
import com.mj.domain.order.RejectReason
import com.mj.domain.order.port.KisOrderPort
import com.mj.domain.order.port.OrderEventRepository
import com.mj.domain.order.port.OrderRepository
import com.mj.domain.order.port.OrderSender
import com.mj.domain.user.port.KisCredentialProvider
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

/**
 * dev/prd profile용 OrderSender. spec §3 D6, §6.2, §7, ADR 0034.
 *
 * 흐름:
 * 1. Guard chain 통과 검증 (KillSwitch · MarketSession · DailyOrderLimit · PriceSanity)
 * 2. KisOrderPort.placeOrder (KIS REST 호출)
 * 3. orders + order_events(PLACED) 영속
 * 4. OrderResult.PENDING 반환 (체결은 별도 흐름 — KisExecutionNoticeListener·OrderExecutionSweep)
 *
 * V11 (ADR 0034) REJECTED audit trail:
 * - Guard reject 시 orders(REJECTED) + order_events(REJECTED) INSERT
 * - KisApiException 시 orders(REJECTED) + order_events(REJECTED) INSERT
 *
 * Bean 등록은 OrderSenderConfig에서 @Profile 분기.
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

        // 1. Guard chain
        when (val d = guard.check(userId, accountNo, order)) {
            is GuardDecision.Reject -> {
                val rejectReason = mapGuardReason(d.reason)
                log.warn(
                    "[KIS_SENDER] guard reject — order_id={} reason_code={} detail={}",
                    order.id,
                    rejectReason.code,
                    d.reason,
                )
                recordRejected(order, userId, accountNo, kisMode, rejectReason, "GUARD")
                return OrderResult(
                    order = order,
                    resultStatus = OrderStatus.REJECTED,
                    filledQty = 0,
                    avgFillPrice = null,
                    filledAt = null,
                    rejectReason = rejectReason.code,
                )
            }
            GuardDecision.Pass -> {}
        }

        // 2. KIS REST 호출
        val kisResult =
            try {
                kisOrderPort.placeOrder(userId, order)
            } catch (e: KisApiException) {
                val rejectReason = RejectReason.KisApiError(rtCd = e.msgCd ?: "UNKNOWN", msg = e.msgRaw ?: "")
                log.warn(
                    "[KIS_SENDER] KIS API exception — order_id={} category={} msg_cd={} msg={}",
                    order.id,
                    e.category,
                    e.msgCd,
                    e.msgRaw,
                )
                recordRejected(order, userId, accountNo, kisMode, rejectReason, "KIS_API")
                return OrderResult(
                    order = order,
                    resultStatus = OrderStatus.REJECTED,
                    filledQty = 0,
                    avgFillPrice = null,
                    filledAt = null,
                    rejectReason = rejectReason.code,
                )
            }

        // 3. PLACED 영속
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
                rejectReasonCode = null,
                rejectDetail = null,
                rejectSource = null,
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

    /**
     * REJECTED 주문 audit trail 기록 (ADR 0034).
     * orders INSERT + order_events INSERT (kis_order_no=null, V11 NULLABLE).
     */
    private fun recordRejected(
        order: Order,
        userId: UUID,
        accountNo: String,
        kisMode: String,
        reason: RejectReason,
        source: String,
    ) {
        val now = OffsetDateTime.now()
        val orderId = order.id
        val record =
            OrderRecord(
                orderId = orderId,
                userId = userId,
                accountNo = accountNo,
                kisMode = kisMode,
                intentId = orderId,
                kisOrderNo = null,
                krxFwdgOrdOrgno = null,
                code = order.code,
                side = order.side,
                qty = order.qty,
                price = order.price,
                orderType = order.type,
                status = OrderStatus.REJECTED,
                filledQty = 0,
                avgFillPrice = null,
                rejectReason = reason.code,
                rejectReasonCode = reason.code,
                rejectDetail = reason.toDetail(),
                rejectSource = source,
                createdAt = now,
                updatedAt = now,
            )
        try {
            orders.save(record)
            orderEvents.insertIdempotent(
                OrderEventRecord(
                    eventId = UUID.randomUUID(),
                    orderId = orderId,
                    userId = userId,
                    accountNo = accountNo,
                    kisMode = kisMode,
                    kisOrderNo = null,
                    eventType = OrderEventType.REJECTED,
                    cumulativeQty = null,
                    partialQty = null,
                    execPrice = null,
                    source = OrderEventSource.INTERNAL,
                    payload = """{"rejectReasonCode":"${reason.code}","rejectDetail":"${reason.toDetail().replace(
                        "\"",
                        "'",
                    )}","rejectSource":"$source"}""",
                    occurredAt = now,
                    ingestedAt = now,
                ),
            )
            log.info(
                "[KIS_SENDER] REJECTED audit recorded — order_id={} reason_code={} source={}",
                orderId,
                reason.code,
                source,
            )
        } catch (e: Exception) {
            log.error("[KIS_SENDER] REJECTED audit INSERT failed — order_id={} error={}", orderId, e.message, e)
        }
    }

    /**
     * GuardDecision.Reject.reason String → RejectReason sealed class 매핑.
     * 기존 Guard들이 String reason을 반환하므로 prefix 기반 파싱.
     */
    private fun mapGuardReason(reason: String): RejectReason =
        when {
            reason.startsWith("KILL_SWITCH") -> RejectReason.KillSwitchActive
            reason.startsWith("SESSION_CLOSED") -> RejectReason.MarketSessionClosed
            reason.startsWith("NOT_BUSINESS_DAY") -> RejectReason.MarketNotBusinessDay
            reason.startsWith("MARKET_CLOSED") -> RejectReason.MarketSessionClosed
            reason.startsWith("DAILY_LIMIT") -> RejectReason.DailyOrderLimitExceeded
            reason.startsWith("POSITION_LIMIT") -> RejectReason.PositionLimitExceeded
            reason.startsWith("DRAWDOWN_STOP") -> RejectReason.DrawdownStopTriggered
            reason.startsWith("PRICE_") || reason.startsWith("PRICE_OUT") -> RejectReason.PriceSanityFailed
            else -> RejectReason.Internal(reason)
        }

    private fun currentUserId(): UUID = DEFAULT_USER_ID

    companion object {
        // 인증 도입 시 SecurityContext에서 가져오도록 변경. 1차는 시드 사용자 고정.
        val DEFAULT_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
