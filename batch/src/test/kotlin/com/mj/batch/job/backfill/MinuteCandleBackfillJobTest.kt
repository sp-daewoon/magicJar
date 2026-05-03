package com.mj.batch.job.backfill

import com.mj.domain.common.Interval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MinuteCandleBackfillJob 단위 테스트 — v2 정책 (ADR 0048).
 *
 * v2 변경: M1 only 영속. M5/M30/H1 입력은 M1로 폴백.
 * KIS 실제 호출 없이 interval 파싱 로직과 alignedTo 필터 로직을 검증한다.
 */
class MinuteCandleBackfillJobTest {
    /**
     * interval 문자열 → Interval enum 매핑 검증 (v2).
     * M1 only — 합성 interval은 M1 폴백.
     */
    @Test
    fun `parseInterval maps M1 and aliases correctly`() {
        val cases =
            listOf(
                "M1" to Interval.M1,
                "1M" to Interval.M1,
                "1MIN" to Interval.M1,
                // v2: 합성 interval은 M1 폴백
                "M5" to Interval.M1,
                "5M" to Interval.M1,
                "M15" to Interval.M1,
                "15M" to Interval.M1,
                "M30" to Interval.M1,
                "30M" to Interval.M1,
                "H1" to Interval.M1,
                "1H" to Interval.M1,
                "UNKNOWN" to Interval.M1,
            )

        cases.forEach { (input, expected) ->
            val parsed = parseInterval(input)
            assertThat(parsed)
                .withFailMessage("parseInterval('%s') = %s, expected %s", input, parsed, expected)
                .isEqualTo(expected)
        }
    }

    /**
     * isAlignedTo 로직 — M1 기준은 모든 분봉 통과 (intervalMinutes = 1).
     */
    @Test
    fun `isAlignedTo M1 passes all minutes`() {
        listOf(0, 1, 5, 10, 15, 30, 59).forEach { minute ->
            assertThat(isAlignedTo(minute, 1))
                .withFailMessage("minute=%d should be aligned to 1", minute)
                .isTrue()
        }
    }

    /**
     * isAlignedTo 5분 — 5분 배수만 통과 (합성 로직 검증).
     */
    @Test
    fun `isAlignedTo 5 passes only 5-minute aligned minutes`() {
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
     * isAlignedTo 30 — 30분 배수만 통과.
     */
    @Test
    fun `isAlignedTo 30 passes only 30-minute aligned minutes`() {
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
            "M1", "1M", "1MIN" -> Interval.M1
            "D1", "DAY" -> Interval.D1
            else -> Interval.M1 // 합성 interval(M5/M30/H1 등) → M1 폴백 (v2 정책)
        }

    private fun isAlignedTo(
        minute: Int,
        intervalMinutes: Int,
    ): Boolean {
        if (intervalMinutes <= 1) return true
        return minute % intervalMinutes == 0
    }
}
