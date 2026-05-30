package kr.spdaewoon.magicjar.domain.market.candle

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test

/**
 * BoundaryDetector 단위 테스트.
 *
 * ADR 0064 §4-2 boundary 시각 매트릭스 검증 (NXT cover 08:00~20:00 KST):
 *   1. 5m boundary — 144 bar/일 매트릭스 샘플 (08:04, 08:09, 09:29, 19:54, 19:59)
 *   2. 15m boundary — 48 bar/일 매트릭스 샘플 (08:14, 09:29, 19:44, 19:59)
 *   3. 30m boundary — 24 bar/일 매트릭스 샘플 (08:29, 09:29, 19:29, 19:59)
 *   4. 90m boundary — 8 anchor (09:29·10:59·12:29·13:59·15:29·16:59·18:29·19:59)
 *   5. 1h boundary — 12 bar/일 매트릭스 샘플 (08:59, 09:59, 18:59, 19:59)
 *   6. NXT 범위 밖 → false (07:59, 20:00)
 *   7. boundary 시작 분이 isBoundaryClosed=false (08:00, 08:05, 09:30)
 *   8. parseHhmmssToInstant — HHMMSS → Instant 분 단위 정합
 *   9. boundaryStartTs — 시작 ts 계산 정합
 */
class BoundaryDetectorTest {
    private val kst = TimeZone.of("Asia/Seoul")

    /** KST 일시 → Instant 헬퍼. */
    private fun kstInstant(
        h: Int,
        m: Int,
        s: Int = 0,
    ): Instant = LocalDateTime(2026, 5, 12, h, m, s).toInstant(kst)

    /** KST 자정 epochSecond 헬퍼. */
    private fun kstMidnightEpochSec(): Long = LocalDateTime(2026, 5, 12, 0, 0, 0).toInstant(kst).epochSeconds

    // ─────────────────────────────────────────────────────────────────────────
    // 1. 5m boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `5m boundary 마감 분 정합 - 08h04m`() {
        // 08:04 → nextMin=08:05=485, 485%5==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 4), CandleInterval.FIVE_MIN) shouldBe true
    }

    @Test
    fun `5m boundary 마감 분 정합 - 09h29m`() {
        // 09:29 → nextMin=09:30=570, 570%5==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 29), CandleInterval.FIVE_MIN) shouldBe true
    }

    @Test
    fun `5m boundary 마감 분 정합 - 19h54m`() {
        // 19:54 → nextMin=19:55=1195, 1195%5==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 54), CandleInterval.FIVE_MIN) shouldBe true
    }

    @Test
    fun `5m boundary 마감 분 정합 - 19h59m`() {
        // 19:59 → nextMin=20:00=1200, 1200%5==0 → true (NXT 마감 bar)
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 59), CandleInterval.FIVE_MIN) shouldBe true
    }

    @Test
    fun `5m boundary 비마감 분 - 08h03m`() {
        // 08:03 → nextMin=484, 484%5!=0 → false
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 3), CandleInterval.FIVE_MIN) shouldBe false
    }

    @Test
    fun `5m boundary 비마감 분 - 08h05m (bar 시작 분)`() {
        // 08:05 → nextMin=486, 486%5!=0 → false
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 5), CandleInterval.FIVE_MIN) shouldBe false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. 15m boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `15m boundary 마감 분 정합 - 08h14m`() {
        // 08:14 → nextMin=495, 495%15==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 14), CandleInterval.FIFTEEN_MIN) shouldBe true
    }

    @Test
    fun `15m boundary 마감 분 정합 - 09h14m`() {
        // 09:14 → nextMin=555, 555%15==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 14), CandleInterval.FIFTEEN_MIN) shouldBe true
    }

    @Test
    fun `15m boundary 마감 분 정합 - 19h44m`() {
        // 19:44 → nextMin=1185, 1185%15==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 44), CandleInterval.FIFTEEN_MIN) shouldBe true
    }

    @Test
    fun `15m boundary 마감 분 정합 - 19h59m`() {
        // 19:59 → nextMin=1200, 1200%15==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 59), CandleInterval.FIFTEEN_MIN) shouldBe true
    }

    @Test
    fun `15m boundary 비마감 분 - 08h05m`() {
        // 08:05 → nextMin=486, 486%15!=0 → false
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 5), CandleInterval.FIFTEEN_MIN) shouldBe false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. 30m boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `30m boundary 마감 분 정합 - 08h29m`() {
        // 08:29 → nextMin=510, 510%30==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 29), CandleInterval.THIRTY_MIN) shouldBe true
    }

    @Test
    fun `30m boundary 마감 분 정합 - 09h29m`() {
        // 09:29 → nextMin=570, 570%30==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 29), CandleInterval.THIRTY_MIN) shouldBe true
    }

    @Test
    fun `30m boundary 마감 분 정합 - 19h29m`() {
        // 19:29 → nextMin=1170, 1170%30==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 29), CandleInterval.THIRTY_MIN) shouldBe true
    }

    @Test
    fun `30m boundary 마감 분 정합 - 19h59m`() {
        // 19:59 → nextMin=1200, 1200%30==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 59), CandleInterval.THIRTY_MIN) shouldBe true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. 90m boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `90m boundary 마감 분 정합 - 09h29m (anchor 09h30m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 29), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 마감 분 정합 - 10h59m (anchor 11h00m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(10, 59), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 마감 분 정합 - 12h29m (anchor 12h30m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(12, 29), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 마감 분 정합 - 13h59m (anchor 14h00m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(13, 59), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 마감 분 정합 - 15h29m (anchor 15h30m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 마감 분 정합 - 16h59m (anchor 17h00m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(16, 59), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 마감 분 정합 - 18h29m (anchor 18h30m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(18, 29), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 마감 분 정합 - 19h59m (anchor 20h00m)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 59), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary 비마감 분 - 09h28m`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 28), CandleInterval.NINETY_MIN) shouldBe false
    }

    @Test
    fun `90m boundary 비마감 분 - 09h30m (bar 시작 분)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 30), CandleInterval.NINETY_MIN) shouldBe false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. 1h boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `1h boundary 마감 분 정합 - 08h59m`() {
        // 08:59 → nextMin=540, 540%60==0 → true
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 59), CandleInterval.ONE_HOUR) shouldBe true
    }

    @Test
    fun `1h boundary 마감 분 정합 - 09h59m`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 59), CandleInterval.ONE_HOUR) shouldBe true
    }

    @Test
    fun `1h boundary 마감 분 정합 - 18h59m`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(18, 59), CandleInterval.ONE_HOUR) shouldBe true
    }

    @Test
    fun `1h boundary 마감 분 정합 - 19h59m`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 59), CandleInterval.ONE_HOUR) shouldBe true
    }

    @Test
    fun `1h boundary 비마감 분 - 09h00m (bar 시작 분)`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 0), CandleInterval.ONE_HOUR) shouldBe false
    }

    @Test
    fun `4h boundary 마감 분 정합 - 11h59m`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(11, 59), CandleInterval.FOUR_HOUR) shouldBe true
    }

    @Test
    fun `4h boundary 마감 분 정합 - 15h59m`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 59), CandleInterval.FOUR_HOUR) shouldBe true
    }

    @Test
    fun `12h boundary는 실제 마지막 봉 기반이라 실시간 boundary detector에서 닫지 않는다`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 59), CandleInterval.TWELVE_HOUR) shouldBe false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. NXT 범위 밖 → false
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `NXT 범위 밖 07h59m - 5m false`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(7, 59), CandleInterval.FIVE_MIN) shouldBe false
    }

    @Test
    fun `NXT 범위 밖 20h00m - 5m false`() {
        // 20:00 minOfDay=1200 >= NXT_CLOSE_MIN=1200 → false
        BoundaryDetector.isBoundaryClosed(kstInstant(20, 0), CandleInterval.FIVE_MIN) shouldBe false
    }

    @Test
    fun `NXT 범위 밖 00h00m - 90m false`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(0, 0), CandleInterval.NINETY_MIN) shouldBe false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. parseHhmmssToInstant
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `parseHhmmssToInstant 정합 - 093015`() {
        val midnight = kstMidnightEpochSec()
        val result = BoundaryDetector.parseHhmmssToInstant("093015", midnight)
        // 09:30 KST = 09h*3600 + 30m*60 = 34200초 오프셋
        result.epochSeconds shouldBe midnight + 9 * 3600L + 30 * 60L
    }

    @Test
    fun `parseHhmmssToInstant 정합 - 080000`() {
        val midnight = kstMidnightEpochSec()
        val result = BoundaryDetector.parseHhmmssToInstant("080000", midnight)
        result.epochSeconds shouldBe midnight + 8 * 3600L
    }

    @Test
    fun `parseHhmmssToInstant 정합 - 분 단위 절사 (초 버림)`() {
        val midnight = kstMidnightEpochSec()
        val result1 = BoundaryDetector.parseHhmmssToInstant("093015", midnight)
        val result2 = BoundaryDetector.parseHhmmssToInstant("093059", midnight)
        // 초가 달라도 분 단위 절사로 동일
        result1.epochSeconds shouldBe result2.epochSeconds
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. boundaryStartTs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `boundaryStartTs 5m - 09h04m → 09h00m`() {
        val ts = kstInstant(9, 4)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.FIVE_MIN)
        result shouldBe kstInstant(9, 0)
    }

    @Test
    fun `boundaryStartTs 5m - 08h09m → 08h05m`() {
        val ts = kstInstant(8, 9)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.FIVE_MIN)
        result shouldBe kstInstant(8, 5)
    }

    @Test
    fun `boundaryStartTs 15m - 09h14m → 09h00m`() {
        val ts = kstInstant(9, 14)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.FIFTEEN_MIN)
        result shouldBe kstInstant(9, 0)
    }

    @Test
    fun `boundaryStartTs 30m - 09h29m → 09h00m`() {
        val ts = kstInstant(9, 29)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.THIRTY_MIN)
        result shouldBe kstInstant(9, 0)
    }

    @Test
    fun `boundaryStartTs 90m - 09h29m → 08h00m (첫 anchor)`() {
        val ts = kstInstant(9, 29)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.NINETY_MIN)
        result shouldBe kstInstant(8, 0)
    }

    @Test
    fun `boundaryStartTs 90m - 11h00m → 11h00m (anchor 자신)`() {
        val ts = kstInstant(11, 0)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.NINETY_MIN)
        result shouldBe kstInstant(11, 0)
    }

    @Test
    fun `boundaryStartTs 1h - 09h59m → 09h00m`() {
        val ts = kstInstant(9, 59)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.ONE_HOUR)
        result shouldBe kstInstant(9, 0)
    }

    @Test
    fun `boundaryStartTs 1h - 19h59m → 19h00m`() {
        val ts = kstInstant(19, 59)
        val result = BoundaryDetector.boundaryStartTs(ts, CandleInterval.ONE_HOUR)
        result shouldBe kstInstant(19, 0)
    }
}
