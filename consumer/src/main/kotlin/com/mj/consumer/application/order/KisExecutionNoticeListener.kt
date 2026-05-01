package com.mj.consumer.application.order

import com.mj.consumer.infrastructure.kis.KisExecutionNoticeRaw
import com.mj.consumer.messaging.producer.OrderExecutedPublisher
import com.mj.domain.order.OrderEventRecord
import com.mj.domain.order.OrderEventSource
import com.mj.domain.order.OrderEventType
import com.mj.domain.order.OrderRecord
import com.mj.domain.order.event.OrderExecutedEvent
import com.mj.domain.order.port.OrderEventRepository
import com.mj.domain.order.port.OrderRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * KIS H0STCNI0/H0STCNI9 체결통보 WS push 처리. spec §5.5, §7.2 (D3 WS Primary).
 *
 * subscribe·재접속·복호는 KisWsSession이 담당. 이 클래스는 KisWsSession.subscribe의 callback에서
 * 받은 KisExecutionNoticeRaw를 도메인 흐름으로 처리:
 *   - CNTG_YN=2(체결통보) → orders 매칭 → order_events.insertIdempotent(EXECUTED)
 *     → 통과 시 orders.updateStatus + order.executed.v1 publish
 *   - CNTG_YN=1(접수통보) → order_events.insertIdempotent(RECEIPT_ACCEPTED) — 무동작 audit
 *
 * 멱등 dedup 키: (kis_mode, kis_order_no, EXECUTED, cumulative_qty)
 *  - WS push와 OrderExecutionSweep(REST)이 같은 체결을 중복 ingest해도 한 번만 통과.
 *
 * KisWsSession 자체 부팅·subscribe는 KisExecutionNoticeBootstrap(별도) 또는 본 클래스에서 처리.
 * 1차에는 KisWsSession Bean 등록 + 본 클래스가 callback로 받는 구조 — 부팅 wiring은 host docker 환경에서.
 */
class KisExecutionNoticeListener(
    private val orders: OrderRepository,
    private val events: OrderEventRepository,
    private val projection: OrderProjectionUpdater,
    private val publisher: OrderExecutedPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun onFrame(raw: KisExecutionNoticeRaw) {
        val orderRow =
            orders.findByKisOrderNo("MOCK_KIS", raw.orderNo)
                ?: orders.findByKisOrderNo("REAL_KIS", raw.orderNo)
                ?: run {
                    log.debug("[KIS_WS] no matching order for kis_order_no={}", raw.orderNo)
                    return
                }

        when (raw.cntgYn) {
            "1" -> handleReceiptAccepted(orderRow, raw)
            "2" -> handleExecuted(orderRow, raw)
            else -> log.warn("[KIS_WS] unknown CNTG_YN={} for order={}", raw.cntgYn, raw.orderNo)
        }
    }

    private fun handleReceiptAccepted(
        orderRow: OrderRecord,
        raw: KisExecutionNoticeRaw,
    ) {
        val now = OffsetDateTime.now()
        events.insertIdempotent(
            OrderEventRecord(
                eventId = UUID.randomUUID(),
                orderId = orderRow.orderId,
                userId = orderRow.userId,
                accountNo = orderRow.accountNo,
                kisMode = orderRow.kisMode,
                kisOrderNo = orderRow.kisOrderNo,
                eventType = OrderEventType.RECEIPT_ACCEPTED,
                cumulativeQty = null,
                partialQty = null,
                execPrice = null,
                source = OrderEventSource.WS,
                payload = null,
                occurredAt = now,
                ingestedAt = now,
            ),
        )
    }

    private fun handleExecuted(
        orderRow: OrderRecord,
        raw: KisExecutionNoticeRaw,
    ) {
        val now = OffsetDateTime.now()
        // KIS H0STCNI 페이로드는 누적 체결 수량이 아닌 "이번 체결 수량"이라 누적은 별도 추적 필요.
        // 1차 구현 — 기존 events에서 EXECUTED 누적 SUM(partialQty) + 현 partial.
        val priorEvents =
            events.findByOrder(orderRow.orderId).filter { it.eventType == OrderEventType.EXECUTED }
        val priorCumulative = priorEvents.maxOfOrNull { it.cumulativeQty ?: 0 } ?: 0
        val newCumulative = priorCumulative + raw.executedQty

        val inserted =
            events.insertIdempotent(
                OrderEventRecord(
                    eventId = UUID.randomUUID(),
                    orderId = orderRow.orderId,
                    userId = orderRow.userId,
                    accountNo = orderRow.accountNo,
                    kisMode = orderRow.kisMode,
                    kisOrderNo = orderRow.kisOrderNo,
                    eventType = OrderEventType.EXECUTED,
                    cumulativeQty = newCumulative,
                    partialQty = raw.executedQty,
                    execPrice = raw.executedUnitPrice,
                    source = OrderEventSource.WS,
                    payload = null,
                    occurredAt = now,
                    ingestedAt = now,
                ),
            )
        if (!inserted) {
            log.debug("[KIS_WS] dedup hit for kis_order_no={} cum={}", raw.orderNo, newCumulative)
            return
        }

        // 누적 평균 단가는 단순 평균 (KIS는 자체 평균을 응답 안 함 — 보수적 추정)
        val avgPrice =
            if (newCumulative > 0) {
                computeWeightedAverage(priorEvents + listOf(currentEventValue(raw, now)), orderRow)
            } else {
                BigDecimal.ZERO
            }
        projection.updateOnExecution(orderRow, newCumulative, avgPrice)

        val status = projection.computeStatus(orderRow.qty, newCumulative)
        publisher.publish(
            OrderExecutedEvent(
                orderId = orderRow.orderId,
                intentId = orderRow.intentId,
                code = orderRow.code,
                side = orderRow.side,
                executedQty = newCumulative,
                executedPrice = avgPrice,
                status = status,
                executedAt = Instant.now(),
                mode = orderRow.kisMode,
            ),
        )
        log.info(
            "[KIS_WS] executed kis_order_no={} status={} cum_qty={}",
            raw.orderNo,
            status,
            newCumulative,
        )
    }

    /** WS 페이로드 partialQty + execPrice를 단순 가상 OrderEventRecord로 변환 (단가 계산 헬퍼용). */
    private fun currentEventValue(
        raw: KisExecutionNoticeRaw,
        now: OffsetDateTime,
    ): OrderEventRecord =
        OrderEventRecord(
            eventId = UUID.randomUUID(),
            orderId = UUID.randomUUID(), // 헬퍼 — 미사용
            userId = UUID.randomUUID(), // 헬퍼 — 미사용
            accountNo = "",
            kisMode = "",
            kisOrderNo = "",
            eventType = OrderEventType.EXECUTED,
            cumulativeQty = null,
            partialQty = raw.executedQty,
            execPrice = raw.executedUnitPrice,
            source = null,
            payload = null,
            occurredAt = now,
            ingestedAt = now,
        )

    private fun computeWeightedAverage(
        priorAndCurrent: List<OrderEventRecord>,
        orderRow: OrderRecord,
    ): BigDecimal {
        val totalQty = priorAndCurrent.sumOf { it.partialQty ?: 0 }
        if (totalQty <= 0) return BigDecimal.ZERO
        val weightedSum =
            priorAndCurrent.fold(BigDecimal.ZERO) { acc, e ->
                acc.add((e.execPrice ?: BigDecimal.ZERO).multiply(BigDecimal.valueOf((e.partialQty ?: 0).toLong())))
            }
        return weightedSum.divide(BigDecimal.valueOf(totalQty.toLong()), 2, java.math.RoundingMode.HALF_UP)
    }
}
