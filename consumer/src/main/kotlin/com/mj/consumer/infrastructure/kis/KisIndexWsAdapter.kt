package com.mj.consumer.infrastructure.kis

import com.mj.consumer.messaging.producer.IndexPublisher
import com.mj.domain.market.event.IndexTickEvent
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS H0UPCNT0 (국내지수 실시간체결, 평문 30 columns) → IndexTickEvent → Kafka market.index.v1.
 *
 * 컬럼 매핑 (plan §2-A 검증):
 *  idx 0  bstp_cls_code      → code (`0001`=KOSPI, `1001`=KOSDAQ)
 *  idx 1  bsop_hour          → at (KST HHmmss)
 *  idx 2  prpr_nmix          → value (현재 지수값)
 *  idx 4  bstp_nmix_prdy_vrss → change (전일 대비, 부호 포함 가능)
 *  idx 5  acml_vol           → volume (누적 거래량)
 *  idx 6  acml_tr_pbmn       → turnover (누적 거래대금)
 *  idx 9  prdy_ctrt          → changeRate (등락률 %)
 *
 * 잘못된 trId 무시 (fail-safe). 컬럼 수 부족 시 warn + skip.
 *
 * Refs: plan T2-R2-A2 §T12.
 */
@Component
@Profile("dev | prd")
class KisIndexWsAdapter(
    private val publisher: IndexPublisher,
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
            log.warn("[KIS_INDEX_WS] columns.size={} < {} for trId={}", columns.size, MIN_COLUMNS, trId)
            return
        }

        val code = columns[0].trim()
        val timeStr = columns[1].trim()
        val valueStr = columns[2].trim()
        val changeStr = columns[4].trim()
        val volumeStr = columns[5].trim()
        val turnoverStr = columns[6].trim()
        val rateStr = columns[9].trim()

        val value =
            valueStr.toBigDecimalOrNull() ?: run {
                log.warn("[KIS_INDEX_WS] invalid value '{}' for code={}", valueStr, code)
                return
            }
        if (value <= BigDecimal.ZERO) {
            log.debug("[KIS_INDEX_WS] zero/negative value for code={} — skip", code)
            return
        }

        val change = changeStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val changeRate = rateStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val volume = volumeStr.toLongOrNull() ?: 0L
        val turnover = turnoverStr.toLongOrNull() ?: 0L
        val at = parseKstInstant(timeStr)

        val event =
            IndexTickEvent(
                code = code,
                at = at,
                value = value,
                change = change,
                changeRate = changeRate,
                volume = volume,
                turnover = turnover,
                source = "KIS_WS",
                stale = false,
                asOf = null,
            )
        publisher.publish(event)
        log.debug(
            "[KIS_INDEX_WS] published code={} value={} changeRate={}%",
            code,
            value,
            changeRate,
        )
    }

    private fun parseKstInstant(timeStr: String): java.time.Instant =
        try {
            val today = LocalDate.now(kst)
            val time = LocalTime.parse(timeStr, hhmmss)
            today.atTime(time).atZone(kst).toInstant()
        } catch (e: Exception) {
            log.debug("[KIS_INDEX_WS] time parse failed '{}' — use now", timeStr)
            java.time.Instant.now()
        }

    companion object {
        const val TR_ID: String = "H0UPCNT0"

        /** idx 9까지 사용하므로 최소 10 columns 필요. */
        const val MIN_COLUMNS: Int = 10
    }
}
