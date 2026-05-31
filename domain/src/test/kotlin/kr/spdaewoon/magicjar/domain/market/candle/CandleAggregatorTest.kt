package kr.spdaewoon.magicjar.domain.market.candle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * CandleAggregator 단위 테스트.
 *
 * 검증 시나리오 (TradingView + KRX 정규장 boundary 정합):
 *   1. 3m·5m·15m·30m·1h·90m 합산 — 각 interval boundary 정합
 *   2. OHLCV 정합 — open=first, close=last, high=max, low=min, vol=sum
 *   3. 빈 입력 / 잘못된 target / 비-1m 섞임 → IllegalArgumentException
 *   4. 다중 boundary 분산
 *   5. 거래정지 시뮬 (boundary 내 1m 0건 → bar 생략, mock 0 정합)
 *   6. ts 무정렬 입력 정렬·합산
 *   7. KRX 정규장 boundary sample (09:00 / 09:05 / 15:00 / 15:25)
 */
class CandleAggregatorTest {
    private val kst = TimeZone.of("Asia/Seoul")
    private val code = "005930"
    private val src = "KIS"

    /** KST 일시 -> Instant 헬퍼. */
    private fun kstTs(
        y: Int,
        mo: Int,
        d: Int,
        h: Int,
        mi: Int,
    ): Instant = LocalDateTime(y, mo, d, h, mi).toInstant(kst)

    /** 1m candle 생성 헬퍼. */
    private fun oneMin(
        ts: Instant,
        o: Long,
        h: Long,
        l: Long,
        c: Long,
        v: Long,
    ): Candle =
        Candle(
            id = null,
            code = code,
            interval = CandleInterval.ONE_MIN,
            ts = ts,
            openRaw = BigDecimal(o),
            highRaw = BigDecimal(h),
            lowRaw = BigDecimal(l),
            closeRaw = BigDecimal(c),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = v,
            source = src,
        )

    @Test
    fun `3m aggregate from KRX regular open boundary 0900 to 0902`() {
        val candles =
            (0 until 3).map { i ->
                oneMin(
                    ts = kstTs(2026, 5, 11, 9, i),
                    o = 100L + i,
                    h = 110L + i,
                    l = 90L + i,
                    c = 105L + i,
                    v = 1000L + i,
                )
            }

        val result = CandleAggregator.aggregate(candles, CandleInterval.THREE_MIN)

        result shouldHaveSize 1
        val bar = result[0]
        bar.interval shouldBe CandleInterval.THREE_MIN
        bar.ts shouldBe kstTs(2026, 5, 11, 9, 0)
        bar.openRaw shouldBe BigDecimal(100)
        bar.highRaw shouldBe BigDecimal(112)
        bar.lowRaw shouldBe BigDecimal(90)
        bar.closeRaw shouldBe BigDecimal(107)
        bar.volume shouldBe 3003L
        bar.code shouldBe code
        bar.source shouldBe src
    }

    @Test
    fun `5m aggregate from KRX regular open boundary 0900 to 0904`() {
        val candles =
            (0 until 5).map { i ->
                oneMin(
                    ts = kstTs(2026, 5, 11, 9, i),
                    o = 100L + i,
                    h = 110L + i,
                    l = 90L + i,
                    c = 105L + i,
                    v = 1000L + i,
                )
            }

        val result = CandleAggregator.aggregate(candles, CandleInterval.FIVE_MIN)

        result shouldHaveSize 1
        val bar = result[0]
        bar.interval shouldBe CandleInterval.FIVE_MIN
        bar.ts shouldBe kstTs(2026, 5, 11, 9, 0)
        bar.openRaw shouldBe BigDecimal(100)
        bar.highRaw shouldBe BigDecimal(114)
        bar.lowRaw shouldBe BigDecimal(90)
        bar.closeRaw shouldBe BigDecimal(109)
        bar.volume shouldBe 5010L
        bar.code shouldBe code
        bar.source shouldBe src
    }

    @Test
    fun `15m aggregate 0900 to 0914 fifteen ones`() {
        val candles =
            (0 until 15).map { i ->
                oneMin(kstTs(2026, 5, 11, 9, i), 100L, 100L, 100L, 100L, 10L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.FIFTEEN_MIN)
        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[0].volume shouldBe 150L
    }

    @Test
    fun `30m aggregate 0900 to 0929 thirty ones`() {
        val candles =
            (0 until 30).map { i ->
                oneMin(kstTs(2026, 5, 11, 9, i), 100L, 100L, 100L, 100L, 1L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.THIRTY_MIN)
        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[0].volume shouldBe 30L
    }

    @Test
    fun `1h aggregate 0900 to 0959 sixty ones`() {
        val candles =
            (0 until 60).map { i ->
                oneMin(kstTs(2026, 5, 11, 9, i), 100L, 100L, 100L, 100L, 1L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.ONE_HOUR)
        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[0].volume shouldBe 60L
    }

    @Test
    fun `4h aggregate uses 0900 anchor and allows final session partial`() {
        val candles =
            (0 until 30).map { i ->
                oneMin(kstTs(2026, 5, 11, 15, i), 100L, 105L, 95L, 101L, 1L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.FOUR_HOUR)
        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 13, 0)
        result[0].volume shouldBe 30L
    }

    @Test
    fun `12h aggregate uses fixed 0900 regular-session anchor like TradingView style time bucket`() {
        val candles =
            (0 until 30).map { i ->
                val totalMin = 9 * 60 + 30 + i
                oneMin(kstTs(2026, 5, 11, totalMin / 60, totalMin % 60), 100L, 110L, 90L, 100L + i, 1L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.TWELVE_HOUR)
        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[0].closeRaw shouldBe BigDecimal(129)
        result[0].volume shouldBe 30L
    }

    @Test
    fun `90m aggregate 0900 anchor ninety ones`() {
        // 09:00 anchor 그룹 = 09:00 ~ 10:29 (90 분).
        val candles =
            (0 until 90).map { i ->
                val totalMin = 9 * 60 + i
                val hh = totalMin / 60
                val mm = totalMin % 60
                oneMin(kstTs(2026, 5, 11, hh, mm), 100L, 100L, 100L, 100L, 1L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.NINETY_MIN)
        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[0].volume shouldBe 90L
    }

    @Test
    fun `OHLCV semantics open first close last high max low min vol sum`() {
        val candles =
            listOf(
                oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 120L, 95L, 110L, 100L),
                oneMin(kstTs(2026, 5, 11, 9, 1), 110L, 130L, 100L, 115L, 200L),
                oneMin(kstTs(2026, 5, 11, 9, 2), 115L, 125L, 90L, 105L, 150L),
                oneMin(kstTs(2026, 5, 11, 9, 3), 105L, 140L, 100L, 135L, 80L),
                oneMin(kstTs(2026, 5, 11, 9, 4), 135L, 145L, 130L, 140L, 50L),
            )
        val result = CandleAggregator.aggregate(candles, CandleInterval.FIVE_MIN)
        result shouldHaveSize 1
        val bar = result[0]
        bar.openRaw shouldBe BigDecimal(100)
        bar.closeRaw shouldBe BigDecimal(140)
        bar.highRaw shouldBe BigDecimal(145)
        bar.lowRaw shouldBe BigDecimal(90)
        bar.volume shouldBe 580L
    }

    @Test
    fun `empty input returns empty output`() {
        CandleAggregator.aggregate(emptyList(), CandleInterval.FIVE_MIN) shouldBe emptyList()
    }

    @Test
    fun `target ONE_MIN throws IllegalArgumentException`() {
        val candles =
            listOf(oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 100L, 100L, 100L, 1L))
        shouldThrow<IllegalArgumentException> {
            CandleAggregator.aggregate(candles, CandleInterval.ONE_MIN)
        }
    }

    @Test
    fun `target ONE_DAY unsupported throws IllegalArgumentException`() {
        val candles =
            listOf(oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 100L, 100L, 100L, 1L))
        shouldThrow<IllegalArgumentException> {
            CandleAggregator.aggregate(candles, CandleInterval.ONE_DAY)
        }
    }

    @Test
    fun `target TWO_MIN unsupported throws IllegalArgumentException`() {
        val candles =
            listOf(oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 100L, 100L, 100L, 1L))
        shouldThrow<IllegalArgumentException> {
            CandleAggregator.aggregate(candles, CandleInterval.TWO_MIN)
        }
    }

    @Test
    fun `non-1m candle in input throws IllegalArgumentException`() {
        val candles =
            listOf(
                oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 100L, 100L, 100L, 1L),
                Candle(
                    id = null,
                    code = code,
                    interval = CandleInterval.FIVE_MIN,
                    ts = kstTs(2026, 5, 11, 9, 5),
                    openRaw = BigDecimal(100),
                    highRaw = BigDecimal(100),
                    lowRaw = BigDecimal(100),
                    closeRaw = BigDecimal(100),
                    openAdj = null,
                    highAdj = null,
                    lowAdj = null,
                    closeAdj = null,
                    volume = 1L,
                    source = src,
                ),
            )
        shouldThrow<IllegalArgumentException> {
            CandleAggregator.aggregate(candles, CandleInterval.FIVE_MIN)
        }
    }

    @Test
    fun `multi boundary distribution 15m 30 ones to 2 bars`() {
        val candles =
            (0 until 30).map { i ->
                oneMin(kstTs(2026, 5, 11, 9, i), 100L, 100L, 100L, 100L, 1L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.FIFTEEN_MIN)
        result shouldHaveSize 2
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[0].volume shouldBe 15L
        result[1].ts shouldBe kstTs(2026, 5, 11, 9, 15)
        result[1].volume shouldBe 15L
    }

    @Test
    fun `trading halt sim - boundary with zero 1m bars is skipped (no mock)`() {
        // 0900~0904 (첫 5m), [0905~0909 skip], 0910~0914 (세 번째 5m).
        val firstGroup =
            (0..4).map { i ->
                oneMin(kstTs(2026, 5, 11, 9, i), 100L, 100L, 100L, 100L, 1L)
            }
        val thirdGroup =
            (10..14).map { i ->
                oneMin(kstTs(2026, 5, 11, 9, i), 100L, 100L, 100L, 100L, 1L)
            }
        val result =
            CandleAggregator.aggregate(firstGroup + thirdGroup, CandleInterval.FIVE_MIN)
        result shouldHaveSize 2
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[1].ts shouldBe kstTs(2026, 5, 11, 9, 10)
    }

    @Test
    fun `unsorted input is sorted and aggregated correctly`() {
        val candles =
            listOf(
                oneMin(kstTs(2026, 5, 11, 9, 4), 140L, 145L, 130L, 140L, 50L),
                oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 120L, 95L, 110L, 100L),
                oneMin(kstTs(2026, 5, 11, 9, 3), 105L, 140L, 100L, 135L, 80L),
                oneMin(kstTs(2026, 5, 11, 9, 1), 110L, 130L, 100L, 115L, 200L),
                oneMin(kstTs(2026, 5, 11, 9, 2), 115L, 125L, 90L, 105L, 150L),
            )
        val result = CandleAggregator.aggregate(candles, CandleInterval.FIVE_MIN)
        result shouldHaveSize 1
        val bar = result[0]
        bar.openRaw shouldBe BigDecimal(100)
        bar.closeRaw shouldBe BigDecimal(140)
        bar.highRaw shouldBe BigDecimal(145)
        bar.lowRaw shouldBe BigDecimal(90)
        bar.volume shouldBe 580L
    }

    @Test
    fun `KRX regular boundary 0900 epoch alignment`() {
        val expectedEpoch = kstTs(2026, 5, 11, 9, 0).epochSeconds
        val candles =
            listOf(oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 100L, 100L, 100L, 1L))
        val result = CandleAggregator.aggregate(candles, CandleInterval.FIVE_MIN)
        result[0].ts.epochSeconds shouldBe expectedEpoch
    }

    @Test
    fun `KRX regular boundary samples 0905 1500 5m and 90m`() {
        // 5m 09:05 (입력 09:07 -> 09:05 boundary)
        val r5 =
            CandleAggregator.aggregate(
                listOf(oneMin(kstTs(2026, 5, 11, 9, 7), 100L, 100L, 100L, 100L, 1L)),
                CandleInterval.FIVE_MIN,
            )
        r5[0].ts shouldBe kstTs(2026, 5, 11, 9, 5)

        // 30m 15:00 (입력 15:15 -> 15:00 boundary)
        val r30 =
            CandleAggregator.aggregate(
                listOf(oneMin(kstTs(2026, 5, 11, 15, 15), 100L, 100L, 100L, 100L, 1L)),
                CandleInterval.THIRTY_MIN,
            )
        r30[0].ts shouldBe kstTs(2026, 5, 11, 15, 0)

        // 1h 15:00 (입력 15:20 -> 15:00 boundary)
        val r1h =
            CandleAggregator.aggregate(
                listOf(oneMin(kstTs(2026, 5, 11, 15, 20), 100L, 100L, 100L, 100L, 1L)),
                CandleInterval.ONE_HOUR,
            )
        r1h[0].ts shouldBe kstTs(2026, 5, 11, 15, 0)

        // 90m 15:00 anchor (입력 15:20 -> 15:00 anchor)
        val r90 =
            CandleAggregator.aggregate(
                listOf(oneMin(kstTs(2026, 5, 11, 15, 20), 100L, 100L, 100L, 100L, 1L)),
                CandleInterval.NINETY_MIN,
            )
        r90[0].ts shouldBe kstTs(2026, 5, 11, 15, 0)
    }

    @Test
    fun `KRX regular last boundary 1525 five ones`() {
        // KRX 정규장 마감 15:30. 15:25~15:29 1m 5건이 마지막 5m bar 형성.
        val candles =
            (0 until 5).map { i ->
                oneMin(kstTs(2026, 5, 11, 15, 25 + i), 100L, 100L, 100L, 100L, 1L)
            }
        val result = CandleAggregator.aggregate(candles, CandleInterval.FIVE_MIN)
        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 15, 25)
        result[0].volume shouldBe 5L
    }

    @Test
    fun `off-session one minute candles are ignored for TradingView regular session`() {
        val candles =
            listOf(
                oneMin(kstTs(2026, 5, 11, 8, 59), 50L, 50L, 50L, 50L, 100L),
                oneMin(kstTs(2026, 5, 11, 9, 0), 100L, 110L, 95L, 105L, 1L),
                oneMin(kstTs(2026, 5, 11, 15, 30), 200L, 200L, 200L, 200L, 100L),
            )

        val result = CandleAggregator.aggregate(candles, CandleInterval.FIVE_MIN)

        result shouldHaveSize 1
        result[0].ts shouldBe kstTs(2026, 5, 11, 9, 0)
        result[0].openRaw shouldBe BigDecimal(100)
        result[0].closeRaw shouldBe BigDecimal(105)
        result[0].volume shouldBe 1L
    }
}
