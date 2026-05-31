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
 * TradingView식 KRX 정규장(09:00~15:30 KST) boundary 시각 매트릭스 검증:
 *   1. 3m·5m·15m·30m boundary — 15:30 final close 포함
 *   2. 90m boundary — 10:30·12:00·13:30·15:00·15:30 close
 *   3. 1h·4h·12h boundary — 세션 끝 partial bar close 포함
 *   4. 정규장 밖 → false
 *   5. boundaryStartTs — 시작 ts 계산 정합
 */
class BoundaryDetectorTest {
    private val kst = TimeZone.of("Asia/Seoul")

    private fun kstInstant(
        h: Int,
        m: Int,
        s: Int = 0,
    ): Instant = LocalDateTime(2026, 5, 12, h, m, s).toInstant(kst)

    private fun kstMidnightEpochSec(): Long = LocalDateTime(2026, 5, 12, 0, 0, 0).toInstant(kst).epochSeconds

    @Test
    fun `3m boundary close minutes include regular open and session close`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 2), CandleInterval.THREE_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 5), CandleInterval.THREE_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.THREE_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 1), CandleInterval.THREE_MIN) shouldBe false
    }

    @Test
    fun `5m boundary close minutes include regular open and session close`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 4), CandleInterval.FIVE_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 29), CandleInterval.FIVE_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 24), CandleInterval.FIVE_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.FIVE_MIN) shouldBe true
    }

    @Test
    fun `5m non-boundary and regular open minute are not closed`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 0), CandleInterval.FIVE_MIN) shouldBe false
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 3), CandleInterval.FIVE_MIN) shouldBe false
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 5), CandleInterval.FIVE_MIN) shouldBe false
    }

    @Test
    fun `15m and 30m boundary close minutes include 1530 final close`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 14), CandleInterval.FIFTEEN_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.FIFTEEN_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 29), CandleInterval.THIRTY_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.THIRTY_MIN) shouldBe true
    }

    @Test
    fun `90m boundary close matrix matches KRX regular session`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(10, 29), CandleInterval.NINETY_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(11, 59), CandleInterval.NINETY_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(13, 29), CandleInterval.NINETY_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(14, 59), CandleInterval.NINETY_MIN) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.NINETY_MIN) shouldBe true
    }

    @Test
    fun `90m non-boundary minutes are not closed`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 29), CandleInterval.NINETY_MIN) shouldBe false
        BoundaryDetector.isBoundaryClosed(kstInstant(10, 30), CandleInterval.NINETY_MIN) shouldBe false
        BoundaryDetector.isBoundaryClosed(kstInstant(12, 0), CandleInterval.NINETY_MIN) shouldBe false
    }

    @Test
    fun `1h boundary includes final 1530 partial bar close`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(9, 59), CandleInterval.ONE_HOUR) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(14, 59), CandleInterval.ONE_HOUR) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.ONE_HOUR) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 28), CandleInterval.ONE_HOUR) shouldBe false
    }

    @Test
    fun `4h boundary uses 0900 anchor with final partial close`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(12, 59), CandleInterval.FOUR_HOUR) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.FOUR_HOUR) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(11, 59), CandleInterval.FOUR_HOUR) shouldBe false
    }

    @Test
    fun `12h boundary is regular-session day close`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 29), CandleInterval.TWELVE_HOUR) shouldBe true
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 28), CandleInterval.TWELVE_HOUR) shouldBe false
        BoundaryDetector.isBoundaryClosed(kstInstant(19, 59), CandleInterval.TWELVE_HOUR) shouldBe false
    }

    @Test
    fun `outside KRX regular session returns false`() {
        BoundaryDetector.isBoundaryClosed(kstInstant(8, 59), CandleInterval.FIVE_MIN) shouldBe false
        BoundaryDetector.isBoundaryClosed(kstInstant(15, 30), CandleInterval.FIVE_MIN) shouldBe false
        BoundaryDetector.isBoundaryClosed(kstInstant(20, 0), CandleInterval.NINETY_MIN) shouldBe false
    }

    @Test
    fun `parseHhmmssToInstant truncates seconds to minute`() {
        val midnight = kstMidnightEpochSec()
        val result1 = BoundaryDetector.parseHhmmssToInstant("093015", midnight)
        val result2 = BoundaryDetector.parseHhmmssToInstant("093059", midnight)
        result1.epochSeconds shouldBe midnight + 9 * 3600L + 30 * 60L
        result1.epochSeconds shouldBe result2.epochSeconds
    }

    @Test
    fun `boundaryStartTs returns regular-session starts`() {
        BoundaryDetector.boundaryStartTs(kstInstant(9, 5), CandleInterval.THREE_MIN) shouldBe kstInstant(9, 3)
        BoundaryDetector.boundaryStartTs(kstInstant(9, 4), CandleInterval.FIVE_MIN) shouldBe kstInstant(9, 0)
        BoundaryDetector.boundaryStartTs(kstInstant(10, 29), CandleInterval.NINETY_MIN) shouldBe kstInstant(9, 0)
        BoundaryDetector.boundaryStartTs(kstInstant(10, 30), CandleInterval.NINETY_MIN) shouldBe kstInstant(10, 30)
        BoundaryDetector.boundaryStartTs(kstInstant(15, 29), CandleInterval.ONE_HOUR) shouldBe kstInstant(15, 0)
        BoundaryDetector.boundaryStartTs(kstInstant(15, 29), CandleInterval.FOUR_HOUR) shouldBe kstInstant(13, 0)
        BoundaryDetector.boundaryStartTs(kstInstant(15, 29), CandleInterval.TWELVE_HOUR) shouldBe kstInstant(9, 0)
    }
}
