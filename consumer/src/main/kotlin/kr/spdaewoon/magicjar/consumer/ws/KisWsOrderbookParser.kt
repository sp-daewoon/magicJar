package kr.spdaewoon.magicjar.consumer.ws

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kr.spdaewoon.magicjar.domain.market.OrderbookSnapshot
import kr.spdaewoon.magicjar.domain.market.PriceLevel
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * KIS WebSocket H0STASP0 호가 메시지 파서.
 *
 * KIS-first 출처: open-trading-api examples_llm/domestic_stock/asking_price_krx/asking_price_krx.py
 *
 * H0STASP0 PIPE+캐럿 메시지 형식:
 *   0|H0STASP0|001|{데이터}
 *   데이터는 캐럿(^) 구분 57개 필드.
 *
 * 필드 인덱스 (asking_price_krx.py cols 배열 기반):
 *   [0]  MKSC_SHRN_ISCD  — 종목코드
 *   [1]  BSOP_HOUR       — 영업시간 (HHMMSS)
 *   [2]  HOUR_CLS_CODE   — 시간구분코드
 *   [3]  ASKP1           — 매도호가1
 *   [4]  ASKP2           — 매도호가2
 *   ...
 *   [12] ASKP10          — 매도호가10
 *   [13] BIDP1           — 매수호가1
 *   [14] BIDP2           — 매수호가2
 *   ...
 *   [22] BIDP10          — 매수호가10
 *   [23] ASKP_RSQN1      — 매도호가잔량1
 *   ...
 *   [32] ASKP_RSQN10     — 매도호가잔량10
 *   [33] BIDP_RSQN1      — 매수호가잔량1
 *   ...
 *   [42] BIDP_RSQN10     — 매수호가잔량10
 *   [43] TOTAL_ASKP_RSQN — 총 매도호가잔량
 *   [44] TOTAL_BIDP_RSQN — 총 매수호가잔량
 *   (이후 예상체결가·거래량·전일대비 등)
 */
object KisWsOrderbookParser {
    private val log = LoggerFactory.getLogger(KisWsOrderbookParser::class.java)
    private val kst = TimeZone.of("Asia/Seoul")

    // H0STASP0 필드 인덱스 (open-trading-api asking_price_krx.py 기반)
    private const val IDX_CODE = 0
    private const val IDX_BSOP_HOUR = 1 // HHMMSS
    private const val IDX_ASKP_START = 3 // 매도호가1~10 (index 3~12)
    private const val IDX_BIDP_START = 13 // 매수호가1~10 (index 13~22)
    private const val IDX_ASKP_RSQN_START = 23 // 매도호가잔량1~10 (index 23~32)
    private const val IDX_BIDP_RSQN_START = 33 // 매수호가잔량1~10 (index 33~42)
    private const val LEVELS = 10
    private const val MIN_FIELDS = 43 // 최소 필요 필드 수 (TOTAL_ASKP_RSQN 이전까지)

    /**
     * H0STASP0 rawData(캐럿 구분 필드열) → OrderbookSnapshot.
     * rawData는 KisWsMessageParser.parse()의 RealtimeData.rawData 값.
     *
     * KIS-first 출처: asking_price_krx.py 필드 인덱스 직접 인용.
     */
    fun parseH0Stasp0(rawData: String): OrderbookSnapshot? {
        return try {
            val fields = rawData.split("^")
            if (fields.size < MIN_FIELDS) {
                log.warn("H0STASP0 필드 수 부족: expected >={} got={}", MIN_FIELDS, fields.size)
                return null
            }

            val code = fields[IDX_CODE].trim()
            val timeStr = fields[IDX_BSOP_HOUR].trim()

            val ts = if (timeStr.length == 6) {
                try {
                    val now = kotlinx.datetime.Clock.System.now().toLocalDateTime(kst)
                    val hour = timeStr.substring(0, 2).toInt()
                    val minute = timeStr.substring(2, 4).toInt()
                    val second = timeStr.substring(4, 6).toInt()
                    kotlinx.datetime.LocalDateTime(now.year, now.monthNumber, now.dayOfMonth, hour, minute, second)
                        .toInstant(kst)
                } catch (e: Exception) {
                    log.debug("H0STASP0 시각 파싱 실패 — Clock.now() 사용: time={}", timeStr)
                    Clock.System.now()
                }
            } else {
                Clock.System.now()
            }

            // 매도호가 10단계 (ASKP1~ASKP10, index 3~12) + 매도호가잔량 (ASKP_RSQN1~10, index 23~32)
            val asks = (0 until LEVELS).mapNotNull { i ->
                val priceStr = fields.getOrNull(IDX_ASKP_START + i)?.trim() ?: return@mapNotNull null
                val qtyStr = fields.getOrNull(IDX_ASKP_RSQN_START + i)?.trim() ?: "0"
                val price = priceStr.ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val qty = qtyStr.ifBlank { "0" }.toLongOrNull() ?: 0L
                if (price == BigDecimal.ZERO) null else PriceLevel(price = price, quantity = qty)
            }

            // 매수호가 10단계 (BIDP1~BIDP10, index 13~22) + 매수호가잔량 (BIDP_RSQN1~10, index 33~42)
            val bids = (0 until LEVELS).mapNotNull { i ->
                val priceStr = fields.getOrNull(IDX_BIDP_START + i)?.trim() ?: return@mapNotNull null
                val qtyStr = fields.getOrNull(IDX_BIDP_RSQN_START + i)?.trim() ?: "0"
                val price = priceStr.ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val qty = qtyStr.ifBlank { "0" }.toLongOrNull() ?: 0L
                if (price == BigDecimal.ZERO) null else PriceLevel(price = price, quantity = qty)
            }

            OrderbookSnapshot(
                code = code,
                ts = ts,
                bids = bids,
                asks = asks,
            )
        } catch (e: Exception) {
            log.error("H0STASP0 파싱 실패: rawData={} error={}", rawData.take(100), e.message)
            null
        }
    }
}
