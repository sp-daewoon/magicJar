package com.mj.consumer.infrastructure.kis

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS H0UNCNT0 (국내주식 실시간체결가 통합) → TickEvent → Kafka market.tick.v1.
 *
 * 컬럼 매핑 (plan §2-A, 47 columns, `^` 분리):
 *  idx 0  MKSC_SHRN_ISCD  → code
 *  idx 1  STCK_CNTG_HOUR  → at (KST 오늘 HHmmss)
 *  idx 2  STCK_PRPR       → price (현재가)
 *  idx 12 CNTG_VOL        → volume (체결량)
 *  idx 13 ACML_VOL        → cumulativeVolume (누적 거래량 — TickEvent 미포함, 향후)
 *
 * 잘못된 trId 무시 (fail-safe). 컬럼 수 부족 시 warn + skip.
 *
 * plan §3 T4 (2026-05-04-track2-r2-quote-ws.md).
 */
@Component
@Profile("dev | prd")
class KisQuoteWsAdapter(
    private val publisher: TickPublisher,
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
            log.warn("[KIS_QUOTE_WS] columns.size={} < {} for trId={}", columns.size, MIN_COLUMNS, trId)
            return
        }

        val code = columns[0].trim()
        val timeStr = columns[1].trim()
        val priceStr = columns[2].trim()
        val volumeStr = columns[12].trim()

        val price =
            priceStr.toBigDecimalOrNull() ?: run {
                log.warn("[KIS_QUOTE_WS] invalid price '{}' for code={}", priceStr, code)
                return
            }
        if (price <= BigDecimal.ZERO) {
            log.debug("[KIS_QUOTE_WS] zero/negative price for code={} — skip", code)
            return
        }

        val volume = volumeStr.toLongOrNull() ?: 0L
        val at = parseKstInstant(timeStr)

        val event =
            TickEvent(
                code = code,
                at = at,
                price = price,
                volume = volume,
                source = "KIS_WS",
                stale = false,
                asOf = null,
            )
        publisher.publish(event)
        log.debug("[KIS_QUOTE_WS] published code={} price={} vol={}", code, price, volume)
    }

    private fun parseKstInstant(timeStr: String): java.time.Instant =
        try {
            val today = LocalDate.now(kst)
            val time = LocalTime.parse(timeStr, hhmmss)
            today.atTime(time).atZone(kst).toInstant()
        } catch (e: Exception) {
            log.debug("[KIS_QUOTE_WS] time parse failed '{}' — use now", timeStr)
            java.time.Instant.now()
        }

    companion object {
        const val TR_ID = "H0UNCNT0"
        const val MIN_COLUMNS = 14 // idx 13(ACML_VOL)까지 필요
    }
}
