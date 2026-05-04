package com.mj.consumer.infrastructure.kis

import com.mj.consumer.messaging.producer.OrderbookPublisher
import com.mj.domain.market.event.OrderbookEvent
import com.mj.domain.market.event.OrderbookLevel
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS H0UNASP0 (국내주식 실시간호가, 평문) → OrderbookEvent → Kafka market.orderbook.v1.
 *
 * 컬럼 매핑 (KIS GitHub example domestic_stock/asking_price_total 검증):
 *  idx 0       MKSC_SHRN_ISCD          → code
 *  idx 1       BSOP_HOUR               → at (KST HHmmss)
 *  idx 3..12   ASKP1~ASKP10            → asks[0..9].price
 *  idx 13..22  BIDP1~BIDP10            → bids[0..9].price
 *  idx 23..32  ASKP_RSQN1~ASKP_RSQN10  → asks[0..9].quantity
 *  idx 33..42  BIDP_RSQN1~BIDP_RSQN10  → bids[0..9].quantity
 *  idx 43      TOTAL_ASKP_RSQN         → totalAskQty
 *  idx 44      TOTAL_BIDP_RSQN         → totalBidQty
 *
 * 잘못된 trId 무시 (fail-safe). 컬럼 수 부족 시 warn + skip.
 *
 * Refs: plan T2-R2-A2 §T11.
 */
@Component
@Profile("dev | prd")
class KisOrderbookWsAdapter(
    private val publisher: OrderbookPublisher,
) : KisWsFrameListener {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")
    private val hhmmss: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    override fun onFrame(
        trId: String,
        trKey: String,
        columns: List<String>,
    ) {
        if (trId != TR_ID) return

        if (columns.size < MIN_COLUMNS) {
            log.warn("[KIS_ORDERBOOK_WS] columns.size={} < {} for trId={}", columns.size, MIN_COLUMNS, trId)
            return
        }

        val code = columns[0].trim()
        val timeStr = columns[1].trim()

        val askPrices = (ASKP_START..ASKP_START + LEVELS - 1).map { columns[it].toDecimal() }
        val bidPrices = (BIDP_START..BIDP_START + LEVELS - 1).map { columns[it].toDecimal() }
        val askQtys = (ASKP_RSQN_START..ASKP_RSQN_START + LEVELS - 1).map { columns[it].toLongSafe() }
        val bidQtys = (BIDP_RSQN_START..BIDP_RSQN_START + LEVELS - 1).map { columns[it].toLongSafe() }

        val totalAskQty = columns[TOTAL_ASKP_RSQN_IDX].toLongSafe()
        val totalBidQty = columns[TOTAL_BIDP_RSQN_IDX].toLongSafe()

        val asks = askPrices.zip(askQtys).map { (p, q) -> OrderbookLevel(p, q) }
        val bids = bidPrices.zip(bidQtys).map { (p, q) -> OrderbookLevel(p, q) }

        val event =
            OrderbookEvent(
                code = code,
                at = parseKstInstant(timeStr),
                asks = asks,
                bids = bids,
                totalAskQty = totalAskQty,
                totalBidQty = totalBidQty,
                source = "KIS_WS",
                stale = false,
                asOf = null,
            )

        publisher.publish(event)
        log.debug(
            "[KIS_ORDERBOOK_WS] published code={} ask1={} bid1={}",
            code,
            asks.firstOrNull()?.price,
            bids.firstOrNull()?.price,
        )
    }

    private fun String.toDecimal(): BigDecimal = trim().takeIf { it.isNotBlank() }?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun String.toLongSafe(): Long = trim().toLongOrNull() ?: 0L

    private fun parseKstInstant(timeStr: String): java.time.Instant =
        try {
            val today = LocalDate.now(kst)
            val time = LocalTime.parse(timeStr, hhmmss)
            today.atTime(time).atZone(kst).toInstant()
        } catch (e: Exception) {
            log.debug("[KIS_ORDERBOOK_WS] time parse failed '{}' — use now", timeStr)
            java.time.Instant.now()
        }

    companion object {
        const val TR_ID: String = "H0UNASP0"

        /** 호가 단계 수 (1~10). */
        const val LEVELS: Int = 10

        /** ASKP1 시작 인덱스. */
        const val ASKP_START: Int = 3

        /** BIDP1 시작 인덱스. */
        const val BIDP_START: Int = 13

        /** ASKP_RSQN1 시작 인덱스. */
        const val ASKP_RSQN_START: Int = 23

        /** BIDP_RSQN1 시작 인덱스. */
        const val BIDP_RSQN_START: Int = 33

        /** TOTAL_ASKP_RSQN 인덱스. */
        const val TOTAL_ASKP_RSQN_IDX: Int = 43

        /** TOTAL_BIDP_RSQN 인덱스. */
        const val TOTAL_BIDP_RSQN_IDX: Int = 44

        /** idx 44까지 사용하므로 최소 45 columns 필요. */
        const val MIN_COLUMNS: Int = 45
    }
}
