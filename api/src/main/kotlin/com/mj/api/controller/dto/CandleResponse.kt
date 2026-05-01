package com.mj.api.controller.dto

import com.mj.domain.common.Candle
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Schema(description = "차트용 캔들 응답 DTO. Lightweight Charts(LWC) Time 포맷과 1:1 호환.")
data class CandleResponse(
    @field:Schema(
        description =
            "봉 시작 시각. " +
                "일봉(D1) → 'YYYY-MM-DD' 문자열(LWC BusinessDay 호환). " +
                "분봉(M1·M5·M15·M30·H1) → unix epoch(초) Long(LWC UTCTimestamp 호환). " +
                "Jackson이 String/Long을 각각 JSON string/number로 자동 직렬화.",
        example = "2026-04-30",
        oneOf = [String::class, Long::class],
    )
    val time: Any,
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
    // LWC Time 타입 호환:
    //   D1 → 'YYYY-MM-DD' 문자열 (BusinessDay) → JSON string
    //   분봉 → epoch second Long (UTCTimestamp) → JSON number
    // 분봉을 string으로 보내면 LWC가 BusinessDay로 파싱 시도 → 'Invalid date string' 에러.
    val time: Any =
        when (interval) {
            com.mj.domain.common.Interval.D1 ->
                openTime.atZone(KST).toLocalDate().format(DAILY_FORMATTER)
            else -> openTime.epochSecond
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
