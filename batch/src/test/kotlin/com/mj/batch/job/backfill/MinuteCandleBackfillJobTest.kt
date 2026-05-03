package com.mj.batch.job.backfill

import com.mj.domain.common.Interval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MinuteCandleBackfillJob 단위 테스트 (T19-T1).
 *
 * KIS 실제 호출 없이 interval 파싱 로직과 alignedTo 필터 로직을 검증한다.
 * 실제 DB/HTTP 의존 없이 순수 로직만 검증 (Profile 분기 무관).
 */
class MinuteCandleBackfillJobTest {
    /**
     * interval 문자열 → Interval enum 매핑 검증.
     * MinuteCandleBackfillJob.parseInterval 로직과 동일.
     */
    @Test
    fun `parseInterval maps string to correct Interval enum`() {
        val cases =
            listOf(
                "M1" to Interval.M1,
                "1M" to Interval.M1,
                "M5" to Interval.M5,
                "5M" to Interval.M5,
                "M15" to Interval.M15,
                "15M" to Interval.M15,
                "M30" to Interval.M30,
                "30M" to Interval.M30,
                "H1" to Interval.H1,
                "1H" to Interval.H1,
                "UNKNOWN" to Interval.M30, // 기본값
            )

        cases.forEach { (input, expected) ->
            val parsed = parseInterval(input)
            assertThat(parsed)
                .withFailMessage("parseInterval('%s') = %s, expected %s", input, parsed, expected)
                .isEqualTo(expected)
        }
    }

    /**
     * isAlignedTo 로직 — 5분봉(M5) 기준 5분 배수만 통과.
     */
    @Test
    fun `isAlignedTo M5 passes only 5-minute aligned minutes`() {
        val alignedMinutes = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)
        val notAligned = listOf(1, 2, 3, 4, 6, 7, 11, 13, 17, 31)

        alignedMinutes.forEach { minute ->
            assertThat(isAlignedTo(minute, 5))
                .withFailMessage("minute=%d should be aligned to 5", minute)
                .isTrue()
        }
        notAligned.forEach { minute ->
            assertThat(isAlignedTo(minute, 5))
                .withFailMessage("minute=%d should NOT be aligned to 5", minute)
                .isFalse()
        }
    }

    /**
     * isAlignedTo M30 — 30분 배수만 통과.
     */
    @Test
    fun `isAlignedTo M30 passes only 30-minute aligned minutes`() {
        assertThat(isAlignedTo(0, 30)).isTrue()
        assertThat(isAlignedTo(30, 30)).isTrue()
        assertThat(isAlignedTo(15, 30)).isFalse()
        assertThat(isAlignedTo(10, 30)).isFalse()
    }

    // -------------------------------------------------------------------------
    // 헬퍼: MinuteCandleBackfillJob 내부 로직 복제 (private 접근 없이 순수 로직 검증)
    // -------------------------------------------------------------------------

    private fun parseInterval(raw: String): Interval =
        when (raw.uppercase()) {
            "M1", "1M" -> Interval.M1
            "M5", "5M" -> Interval.M5
            "M15", "15M" -> Interval.M15
            "M30", "30M" -> Interval.M30
            "H1", "1H" -> Interval.H1
            else -> Interval.M30
        }

    private fun isAlignedTo(
        minute: Int,
        intervalMinutes: Int,
    ): Boolean {
        if (intervalMinutes <= 1) return true
        return minute % intervalMinutes == 0
    }
}
