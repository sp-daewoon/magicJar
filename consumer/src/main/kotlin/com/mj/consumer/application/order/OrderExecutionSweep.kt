package com.mj.consumer.application.order

import com.mj.consumer.messaging.producer.OrderExecutedPublisher
import com.mj.domain.order.OrderEventRecord
import com.mj.domain.order.OrderEventSource
import com.mj.domain.order.OrderEventType
import com.mj.domain.order.event.OrderExecutedEvent
import com.mj.domain.order.port.KisOrderPort
import com.mj.domain.order.port.OrderEventRepository
import com.mj.domain.order.port.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * REST 보강 sweep. spec §3 D3, §7.2.
 *
 * KisExecutionNoticeListener(WS) primary가 끊김·누락 시 보강.
 * 30초 주기로 KIS REST inquire-daily-ccld → 미체결 주문에 대해 누적 체결 수량 조회 →
 * order_events.insertIdempotent(EXECUTED, source=REST_SWEEP) → 멱등 통과 시 projection + publish.
 *
 * 멱등 dedup: WS와 같은 (kis_mode, kis_order_no, EXECUTED, cumulative_qty) 키 → 중복 통과 0.
 *
 * mock profile에선 비활성 (KisOrderPort Bean 부재).
 */
@Component
@Profile("dev | prd")
class OrderExecutionSweep(
    private val kisOrderPort: KisOrderPort,
    private val orders: OrderRepository,
    private val events: OrderEventRepository,
    private val projection: OrderProjectionUpdater,
    private val publisher: OrderExecutedPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("Asia/Seoul")

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    fun run() {
        val openOrders = orders.findOpenByUser(KisOrderSender.DEFAULT_USER_ID)
        if (openOrders.isEmpty()) return

        val today = LocalDate.now(zone)
        val lines =
            try {
                kisOrderPort.fetchExecutions(KisOrderSender.DEFAULT_USER_ID, today)
            } catch (e: Exception) {
                log.warn("[SWEEP] fetchExecutions failed: {}", e.message)
                return
            }

        for (line in lines) {
            val match = openOrders.firstOrNull { it.kisOrderNo == line.kisOrderNo } ?: continue
            if (line.cumulativeExecutedQty <= match.filledQty) continue

            val now = OffsetDateTime.now()
            val partialQty = line.cumulativeExecutedQty - match.filledQty
            val avgPrice =
                if (line.cumulativeExecutedQty > 0) {
                    line.cumulativeExecutedAmount.divide(
                        BigDecimal.valueOf(line.cumulativeExecutedQty.toLong()),
                        2,
                        java.math.RoundingMode.HALF_UP,
                    )
                } else {
                    BigDecimal.ZERO
                }

            val inserted =
                events.insertIdempotent(
                    OrderEventRecord(
                        eventId = UUID.randomUUID(),
                        orderId = match.orderId,
                        userId = match.userId,
                        accountNo = match.accountNo,
                        kisMode = match.kisMode,
                        kisOrderNo = match.kisOrderNo,
                        eventType = OrderEventType.EXECUTED,
                        cumulativeQty = line.cumulativeExecutedQty,
                        partialQty = partialQty,
                        execPrice = avgPrice,
                        source = OrderEventSource.REST_SWEEP,
                        payload = null,
                        occurredAt = now,
                        ingestedAt = now,
                    ),
                )
            if (!inserted) continue
            projection.updateOnExecution(match, line.cumulativeExecutedQty, avgPrice)
            val status = projection.computeStatus(match.qty, line.cumulativeExecutedQty)
            publisher.publish(
                OrderExecutedEvent(
                    orderId = match.orderId,
                    intentId = match.intentId,
                    code = match.code,
                    side = match.side,
                    executedQty = line.cumulativeExecutedQty,
                    executedPrice = avgPrice,
                    status = status,
                    executedAt = Instant.now(),
                    mode = match.kisMode,
                ),
            )
            log.info(
                "[SWEEP] backfilled kis_order_no={} cum_qty={} status={}",
                match.kisOrderNo,
                line.cumulativeExecutedQty,
                status,
            )
        }
    }

    companion object {
        const val SWEEP_INTERVAL_MS = 30_000L
    }
}
