package kr.spdaewoon.magicjar.consumer.ws

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * KIS WebSocket 수신 메시지 파서 — PIPE(|)·캐럿(^) 구분자.
 *
 * KIS-first 출처: open-trading-api examples_user/kis_auth.py
 * 수신 raw 메시지 형식:
 *   {타입}|{TR_ID}|{N}|{데이터}
 *   - 타입: "0" = 실시간 데이터, "1" = 구독 응답, "PINGPONG" = heartbeat
 *   - TR_ID: H0STCNT0(체결가), H0STASP0(호가), H0STCNI0(체결통보)
 *   - N: 데이터 건수
 *   - 데이터: ^(캐럿) 구분 필드열
 *
 * H0STCNT0 필드 인덱스 (open-trading-api domestic_stock_functions_ws.py cols 배열):
 * KIS-first raw 출처:
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   ccnl_krx() columns 배열 (인덱스 0~45):
 *   [0]  MKSC_SHRN_ISCD      — 종목코드
 *   [1]  STCK_CNTG_HOUR      — 체결시각 (HHMMSS)
 *   [2]  STCK_PRPR           — 현재가(체결가)
 *   [3]  PRDY_VRSS_SIGN      — 전일대비부호 (1=상한/2=상승/3=보합/4=하한/5=하락)
 *   [4]  PRDY_VRSS           — 전일대비
 *   [5]  PRDY_CTRT           — 전일대비율 (%)
 *  [12]  CNTG_VOL            — 체결거래량
 *  [13]  ACML_VOL            — 누적거래량
 *  [33]  BSOP_DATE           — 영업일자 (YYYYMMDD)
 *
 * 전체 46개 필드 (인덱스 0~45) — 자세한 내용은 KIS open-trading-api 레포 domestic_stock/domestic_stock_functions_ws.py 참조.
 */
object KisWsMessageParser {
    private val log = LoggerFactory.getLogger(KisWsMessageParser::class.java)

    private val kst = TimeZone.of("Asia/Seoul")

    // H0STCNT0 필드 인덱스 (KIS-first raw 출처)
    // https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
    // ccnl_krx() columns 배열 기반
    private const val IDX_CODE = 0
    private const val IDX_TIME = 1
    private const val IDX_PRICE = 2
    private const val IDX_PRDY_VRSS_SIGN = 3 // 전일대비부호
    private const val IDX_PRDY_VRSS = 4 // 전일대비
    private const val IDX_PRDY_CTRT = 5 // 전일대비율(%)
    private const val IDX_CNTG_VOL = 12
    private const val IDX_BSOP_DATE = 33

    /**
     * raw 메시지 타입 판별.
     * - PINGPONG: 서버 heartbeat응답
     * - DATA: 실시간 데이터 (타입 "0")
     * - RESPONSE: 구독 응답 (타입 "1")
     * - UNKNOWN: 알 수 없음
     */
    sealed class ParsedMessage {
        object Pingpong : ParsedMessage()

        data class RealtimeData(
            val trId: String,
            val rawData: String,
        ) : ParsedMessage()

        data class SubscriptionResponse(
            val trId: String,
            val rawJson: String,
        ) : ParsedMessage()

        data class Unknown(
            val raw: String,
        ) : ParsedMessage()
    }

    fun parse(raw: String): ParsedMessage {
        if (raw.trim() == "PINGPONG") return ParsedMessage.Pingpong

        val parts = raw.split("|")
        if (parts.size < 4) {
            // JSON 응답 (구독 성공/실패 등)
            return ParsedMessage.SubscriptionResponse(trId = "", rawJson = raw)
        }

        return when (val type = parts[0]) {
            "0" -> ParsedMessage.RealtimeData(trId = parts[1], rawData = parts[3])
            "1" -> ParsedMessage.SubscriptionResponse(trId = parts[1], rawJson = raw)
            else -> {
                log.debug("KIS WS 알 수 없는 메시지 타입: type={} raw={}", type, raw.take(100))
                ParsedMessage.Unknown(raw)
            }
        }
    }

    /**
     * H0STCNT0 실시간 체결가 데이터 파싱 → TickEvent.
     * 캐럿(^) 구분 46개 필드.
     *
     * KIS-first: open-trading-api domestic_stock/domestic_stock_functions_ws.py ccnl_krx() columns 배열 기반.
     * raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *
     * 등락률 필드 추출 (dogfooding 결함 1 — 싱크 불일치 해소):
     *   [3] PRDY_VRSS_SIGN — 전일대비부호
     *   [4] PRDY_VRSS      — 전일대비
     *   [5] PRDY_CTRT      — 전일대비율(%)
     */
    fun parseH0Stcnt0(
        rawData: String,
        expectedCode: String? = null,
    ): TickEvent? {
        return try {
            val fields = rawData.split("^")
            if (fields.size < 34) {
                log.warn("H0STCNT0 필드 수 부족: expected ≥34 got={}", fields.size)
                return null
            }

            val code = fields[IDX_CODE].trim()
            if (expectedCode != null && code != expectedCode) {
                log.debug("H0STCNT0 code 불일치: expected={} got={}", expectedCode, code)
                return null
            }

            val priceStr = fields[IDX_PRICE].trim()
            val price = BigDecimal(priceStr.ifBlank { "0" })

            val volumeStr = fields[IDX_CNTG_VOL].trim()
            val volume = volumeStr.ifBlank { "0" }.toLongOrNull() ?: 0L

            // 등락 필드 파싱 — KIS H0STCNT0 [3][4][5]
            // KIS-first raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
            val changeSign = fields.getOrNull(IDX_PRDY_VRSS_SIGN)?.trim()?.ifBlank { null }
            val priceChange =
                fields.getOrNull(IDX_PRDY_VRSS)?.trim()?.let { s ->
                    s.ifBlank { null }?.toBigDecimalOrNull()
                }
            val changeRate =
                fields.getOrNull(IDX_PRDY_CTRT)?.trim()?.let { s ->
                    s.ifBlank { null }?.toBigDecimalOrNull()
                }

            // 시각 파싱: BSOP_DATE(YYYYMMDD) + STCK_CNTG_HOUR(HHMMSS) → KST Instant
            val dateStr = fields[IDX_BSOP_DATE].trim()
            val timeStr = fields[IDX_TIME].trim()

            val ts =
                if (dateStr.length == 8 && timeStr.length == 6) {
                    try {
                        val year = dateStr.substring(0, 4).toInt()
                        val month = dateStr.substring(4, 6).toInt()
                        val day = dateStr.substring(6, 8).toInt()
                        val hour = timeStr.substring(0, 2).toInt()
                        val minute = timeStr.substring(2, 4).toInt()
                        val second = timeStr.substring(4, 6).toInt()
                        kotlinx.datetime
                            .LocalDateTime(year, month, day, hour, minute, second)
                            .toInstant(kst)
                    } catch (e: Exception) {
                        log.debug("H0STCNT0 시각 파싱 실패 — Clock.now() 사용: date={} time={}", dateStr, timeStr)
                        Clock.System.now()
                    }
                } else {
                    Clock.System.now()
                }

            TickEvent(
                code = code,
                price = price,
                volume = volume,
                ts = ts,
                source = "live",
                stale = false,
                priceChange = priceChange,
                changeRate = changeRate,
                changeSign = changeSign,
            )
        } catch (e: Exception) {
            log.error("H0STCNT0 파싱 실패: rawData={} error={}", rawData.take(100), e.message)
            null
        }
    }

    /**
     * 구독/해제 응답 JSON에서 rt_cd(응답코드) 확인.
     * "0" = 성공, 그 외 = 실패.
     */
    fun isSubscriptionSuccess(rawJson: String): Boolean {
        // JSON 간단 파싱 — Jackson 미사용 (circular dep 방지)
        return try {
            rawJson.contains("\"rt_cd\":\"0\"") || rawJson.contains("\"rt_cd\": \"0\"")
        } catch (e: Exception) {
            false
        }
    }
}
