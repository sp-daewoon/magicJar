package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId

/**
 * 1분봉 → N분봉 어플리케이션 레벨 합성 (ADR 0048, candle-data-policy-v2.md §1·§3).
 *
 * 알고리즘:
 *   - KST 09:00 기준 N분 lock-step bucket grouping
 *   - bucket 내: open=첫 봉 open, high=max, low=min, close=마지막 봉 close, volume=sum
 *   - 미완성 마지막 버킷 포함 (진행 중인 봉 차트에서 표시)
 *   - 빈 입력 → 빈 목록 반환 (예외 없음)
 *
 * 합성 봉의 interval 필드는 M1로 설정된다 (영속 interval enum과 동일).
 * 프론트엔드는 interval string이 아닌 실제 봉 단위 간격을 epoch second 차이로 인식하므로 무관.
 *
 * 일봉(D1) → W/MO/Y 합성은 [aggregateDailyToWeekly] 참조.
 */
@Component
class CandleAggregator {
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * M1 → N분봉 합성.
     *
     * @param m1Candles          1분봉 목록 (openTime ASC 정렬 권장)
     * @param targetMinutes      합성 단위 (분). 예: 5 → 5분봉, 60 → 60분봉
     * @param sessionStartHour   장 시작 시각 (KST 기준 시). default 9 (09:00)
     * @return 합성된 N분봉 목록 (openTime ASC 정렬)
     * @throws IllegalArgumentException targetMinutes ≤ 0 또는 > 1440(1일) 시
     */
    fun aggregate(
        m1Candles: List<Candle>,
        targetMinutes: Int,
        sessionStartHour: Int = 9,
    ): List<Candle> {
        require(targetMinutes > 0) { "targetMinutes must be positive, got $targetMinutes" }
        require(targetMinutes <= 1440) { "targetMinutes must be <= 1440 (1 day), got $targetMinutes" }

        if (m1Candles.isEmpty()) return emptyList()

        // openTime ASC 정렬 보장
        val sorted = m1Candles.sortedBy { it.openTime }

        // 첫 봉의 code를 사용 (전체 목록이 같은 종목이라 가정)
        val code = sorted.first().code

        // KST 09:00 기준 lock-step bucket 인덱스 계산
        // bucket index = (KST 분 offset from sessionStart) / targetMinutes
        val buckets =
            sorted.groupBy { candle ->
                val kstDateTime = candle.openTime.atZone(kst)
                val kstDate = kstDateTime.toLocalDate()
                val minutesSinceSessionStart =
                    kstDateTime.hour * 60 + kstDateTime.minute - sessionStartHour * 60
                // 날짜 + bucket index를 키로 사용 (날짜 구분 포함)
                val bucketIndex =
                    if (minutesSinceSessionStart >= 0) {
                        minutesSinceSessionStart / targetMinutes
                    } else {
                        // 장 시작 이전 데이터 (일반적으로 없음, 방어 처리)
                        minutesSinceSessionStart / targetMinutes - 1
                    }
                kstDate to bucketIndex
            }

        return buckets.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .mapNotNull { (_, barsInBucket) ->
                if (barsInBucket.isEmpty()) return@mapNotNull null
                val sortedBars = barsInBucket.sortedBy { it.openTime }
                buildAggregatedCandle(code, sortedBars)
            }
    }

    /**
     * D1 → W/MO/Y 합성 (frontend dailyAggregator 대응).
     *
     * @param d1Candles    일봉 목록 (openTime ASC 정렬 권장)
     * @param targetPeriod "W"(주봉), "MO"(월봉), "Y"(연봉)
     * @return 합성된 봉 목록 (openTime ASC 정렬)
     */
    fun aggregateDailyToWeekly(
        d1Candles: List<Candle>,
        targetPeriod: String,
    ): List<Candle> {
        if (d1Candles.isEmpty()) return emptyList()

        val sorted = d1Candles.sortedBy { it.openTime }
        val code = sorted.first().code

        val buckets =
            sorted.groupBy { candle ->
                val kstDate = candle.openTime.atZone(kst).toLocalDate()
                when (targetPeriod.uppercase()) {
                    "W" -> {
                        // ISO week: 월요일 기준
                        val weekFields = java.time.temporal.WeekFields.ISO
                        "${kstDate.year}-W${kstDate.get(weekFields.weekOfWeekBasedYear())}"
                    }
                    "MO", "MONTH" -> "${kstDate.year}-${kstDate.monthValue}"
                    "Y", "YEAR" -> "${kstDate.year}"
                    else -> throw IllegalArgumentException("Unsupported period: $targetPeriod")
                }
            }

        return buckets.entries
            .sortedBy { it.key }
            .mapNotNull { (_, barsInBucket) ->
                if (barsInBucket.isEmpty()) return@mapNotNull null
                val sortedBars = barsInBucket.sortedBy { it.openTime }
                buildAggregatedCandle(code, sortedBars, targetInterval = Interval.D1)
            }
    }

    /**
     * bucket 내 봉들을 단일 합성 봉으로 축약.
     */
    private fun buildAggregatedCandle(
        code: String,
        sortedBars: List<Candle>,
        targetInterval: Interval = Interval.M1,
    ): Candle {
        val open = sortedBars.first().open
        val close = sortedBars.last().close
        val high = sortedBars.maxOf { it.high }
        val low = sortedBars.minOf { it.low }
        val volume = sortedBars.sumOf { it.volume }
        val tradeCount = sortedBars.sumOf { it.tradeCount }
        val openTime: Instant = sortedBars.first().openTime

        return Candle(
            code = code,
            interval = targetInterval,
            openTime = openTime,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            tradeCount = tradeCount,
        )
    }

    companion object {
        /**
         * interval 문자열을 분 단위 정수로 파싱.
         *
         * 지원 형식:
         *   - M1, M5, M15, M30, M60, M90, M120, M2, M3, M10 등 "M<n>"
         *   - 1m, 5m, 15m, 30m, 60m, 90m, 120m 등 "<n>m" (소문자 m)
         *   - H1, H2 등 "H<n>" (시간 단위 → ×60)
         *   - 1h, 2h 등 "<n>h" (시간 단위 → ×60)
         * @return 분 단위 정수. 변환 불가 시 null.
         */
        fun parseToMinutesOrNull(interval: String): Int? {
            val upper = interval.trim().uppercase()
            // M<n> 형식
            Regex("^M(\\d+)$").matchEntire(upper)?.let {
                return it.groupValues[1].toIntOrNull()
            }
            // H<n> 형식 (시간 → 분)
            Regex("^H(\\d+)$").matchEntire(upper)?.let {
                return it.groupValues[1].toIntOrNull()?.let { h -> h * 60 }
            }
            // <n>M 형식 (소문자 m 포함)
            Regex("^(\\d+)[Mm]$").matchEntire(upper)?.let {
                return it.groupValues[1].toIntOrNull()
            }
            // <n>H 형식 (소문자 h 포함)
            Regex("^(\\d+)[Hh]$").matchEntire(upper)?.let {
                return it.groupValues[1].toIntOrNull()?.let { h -> h * 60 }
            }
            return null
        }

        /**
         * interval 문자열이 합성 interval인지 판별.
         * 합성 interval: M1·D1·DAY 이외의 분봉 또는 시간봉 (W/MO/Y는 일봉 합성이므로 별도 분기).
         */
        fun isSyntheticMinute(interval: String): Boolean {
            val upper = interval.trim().uppercase()
            return when (upper) {
                "D1", "DAY", "D", "1D", "M1", "1M" -> false
                "W", "MO", "MONTH", "Y", "YEAR" -> false
                else -> parseToMinutesOrNull(interval) != null
            }
        }

        /**
         * interval 문자열이 D1/DAY alias인지 판별.
         */
        fun isDailyPeriod(interval: String): Boolean =
            when (interval.trim().uppercase()) {
                "W", "WK", "MO", "MONTH", "Y", "YEAR" -> true
                else -> false
            }
    }
}
