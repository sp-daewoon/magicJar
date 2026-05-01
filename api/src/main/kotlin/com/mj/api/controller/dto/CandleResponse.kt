package com.mj.api.controller.dto

import com.mj.domain.common.Candle
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Schema(description = "차트용 캔들 응답 DTO. Lightweight Charts(LWC) time/OHLCV 포맷과 1:1 호환.")
data class CandleResponse(
    @field:Schema(description = "봉 시작 시각. 일봉은 'YYYY-MM-DD', 분봉은 unix epoch(초).", example = "2026-04-30")
    val time: String,
    @field:Schema(description = "시가(원)", example = "60000")
    val open: Long,
    @field:Schema(description = "고가(원)", example = "60500")
    val high: Long,
    @field:Schema(description = "저가(원)", example = "59500")
    val low: Long,
    @field:Schema(description = "종가(원)", example = "60200")
    val close: Long,
    @field:Schema(description = "거래량", example = "12345678")
    val volume: Long,
)

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val DAILY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun Candle.toResponse(): CandleResponse {
    val time =
        when (interval) {
            com.mj.domain.common.Interval.D1 ->
                openTime.atZone(KST).toLocalDate().format(DAILY_FORMATTER)
            else -> openTime.epochSecond.toString()
        }
    return CandleResponse(
        time = time,
        open = open.toLong(),
        high = high.toLong(),
        low = low.toLong(),
        close = close.toLong(),
        volume = volume,
    )
}
