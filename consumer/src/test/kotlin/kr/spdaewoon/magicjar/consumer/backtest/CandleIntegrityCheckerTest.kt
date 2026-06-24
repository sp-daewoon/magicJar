package kr.spdaewoon.magicjar.consumer.backtest

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class CandleIntegrityCheckerTest {
    private val candleRepository = mockk<CandleRepository>()
    private val checker = CandleIntegrityChecker(candleRepository)

    // 2026-06-22(월)~06-26(금) = 5 거래일.
    private val from = LocalDate.of(2026, 6, 22)
    private val to = LocalDate.of(2026, 6, 26)

    @Test
    fun `기대 봉수는 KRX 정규장 390 bar per day 기준 interval별로 산출된다`() {
        // 1m=390/일·3m=130·5m=78·15m=26·30m=13·90m=5(ceil) — 5 거래일.
        assertThat(CandleIntegrityChecker.expectedBarsFor("1m", 5)).isEqualTo(390 * 5)
        assertThat(CandleIntegrityChecker.expectedBarsFor("3m", 5)).isEqualTo(130 * 5)
        assertThat(CandleIntegrityChecker.expectedBarsFor("5m", 5)).isEqualTo(78 * 5)
        assertThat(CandleIntegrityChecker.expectedBarsFor("15m", 5)).isEqualTo(26 * 5)
        assertThat(CandleIntegrityChecker.expectedBarsFor("30m", 5)).isEqualTo(13 * 5)
        // 390/90=4.33 → ceil=5
        assertThat(CandleIntegrityChecker.expectedBarsFor("90m", 5)).isEqualTo(5 * 5)
    }

    @Test
    fun `거래일 산출은 주말을 제외한다`() {
        // 06-22(월)~06-26(금) = 5 거래일, 06-22~06-28(일) = 여전히 5(주말 제외).
        assertThat(CandleIntegrityChecker.tradingDaysBetween(from, to)).isEqualTo(5)
        assertThat(CandleIntegrityChecker.tradingDaysBetween(from, LocalDate.of(2026, 6, 28))).isEqualTo(5)
    }

    @Test
    fun `결측률 5퍼센트 이하면 OK 등급`() {
        // 1m 5일 기대 1950. actual 1900 → missing 50 → ratio 0.0256 ≤ 5% → OK.
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1900)

        val report = checker.check("005930", CandleInterval.ONE_MIN, from, to)

        assertThat(report.expectedBars).isEqualTo(1950)
        assertThat(report.actualBars).isEqualTo(1900)
        assertThat(report.missingBars).isEqualTo(50)
        assertThat(report.confidence).isEqualTo(CandleConfidence.OK)
    }

    @Test
    fun `결측률 5에서 20퍼센트면 LOW_CONFIDENCE 등급`() {
        // 기대 1950, actual 1700 → missing 250 → ratio 0.128 → 5%<r≤20% → LOW.
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1700)

        val report = checker.check("005930", CandleInterval.ONE_MIN, from, to)

        assertThat(report.confidence).isEqualTo(CandleConfidence.LOW_CONFIDENCE)
        assertThat(report.missingRatio.toDouble()).isBetween(0.05, 0.20)
    }

    @Test
    fun `결측률 20퍼센트 초과면 INSUFFICIENT_DATA 등급`() {
        // 기대 1950, actual 1000 → missing 950 → ratio 0.487 > 20% → INSUFFICIENT.
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1000)

        val report = checker.check("005930", CandleInterval.ONE_MIN, from, to)

        assertThat(report.confidence).isEqualTo(CandleConfidence.INSUFFICIENT_DATA)
        assertThat(report.missingRatio.toDouble()).isGreaterThan(0.20)
    }

    @Test
    fun `checker는 candle을 생성하거나 수정하지 않는다 — mock 보간 0 단언`() {
        // 결측이 커도 saveAll·upsertAll 호출 0 (읽기 전용 — 결측은 등급으로만 노출).
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(10)

        val report = checker.check("005930", CandleInterval.ONE_MIN, from, to)

        // 결측 봉을 메우지 않았으므로 actualBars 는 실제 DB 값 그대로(보간 0).
        assertThat(report.actualBars).isEqualTo(10)
        assertThat(report.confidence).isEqualTo(CandleConfidence.INSUFFICIENT_DATA)
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        verify(exactly = 0) { candleRepository.upsertAll(any()) }
    }

    @Test
    fun `lookbackClamped 플래그가 보고에 전파된다`() {
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1950)

        val report = checker.check("005930", CandleInterval.ONE_MIN, from, to, lookbackClamped = true)

        assertThat(report.lookbackClamped).isTrue()
    }

    @Test
    fun `분봉이 아닌 interval은 거부한다`() {
        assertThatThrownBy { CandleIntegrityChecker.expectedBarsFor("1d", 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun ts(count: Int): List<Instant> {
        val base = Instant.parse("2026-06-22T00:00:00Z")
        return (0 until count).map { base.plusSeconds(it * 60L) }
    }
}
