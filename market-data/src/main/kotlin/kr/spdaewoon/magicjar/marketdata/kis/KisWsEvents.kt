package kr.spdaewoon.magicjar.marketdata.kis

import kr.spdaewoon.magicjar.core.domain.Quote
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS WebSocket 메시지 파서.
 *
 * 실시간 체결가(H0STCNT0)는 `^` 로 구분된 raw text:
 *   `0|H0STCNT0|001|005930^095932^72500^2^...`
 *
 * 여기서는 stck_cntg_hour(2번째 필드)·stck_prpr(3번째)·체결량(누적이 아닌 그 시점 체결수량) 등을 추출한다.
 * 호가(H0STASP0)는 별도 파서가 필요하지만 MVP 범위에서는 체결가만 다룬다.
 */
internal object KisWsEvents {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    fun parseConclusion(raw: String): Quote? {
        val parts = raw.split("|")
        if (parts.size < 4 || parts[1] != "H0STCNT0") return null
        val payload = parts[3].split("^")
        if (payload.size < 12) return null

        val code = payload[0]
        val time = payload[1]
        val price = payload[2]
        val volume = payload[12].toLongOrNull() ?: 0L

        val instant = todayWith(time)
        return Quote(
            code = code,
            at = instant,
            price = BigDecimal(price),
            volume = volume,
        )
    }

    private fun todayWith(hhmmss: String): Instant {
        val today = java.time.LocalDate.now(KST)
        val time = LocalTime.parse(hhmmss, HHMMSS)
        return today.atTime(time).atZone(KST).toInstant()
    }
}
