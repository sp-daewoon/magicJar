package kr.spdaewoon.magicjar.api.backtest

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.api.config.IntradayRealisticBacktestProperties
import kr.spdaewoon.magicjar.api.service.CandleRefreshService
import kr.spdaewoon.magicjar.api.service.backtest.BacktestCandlePrepareApiAdapter
import kr.spdaewoon.magicjar.api.service.backtest.CandleIntegrityChecker
import kr.spdaewoon.magicjar.api.service.dto.IntervalRefreshResult
import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [BacktestCandlePrepareApiAdapter] 단위 테스트 — ADR 0133 §3-A1 (#4 · api in-process backfill 통합 실작동 단언).
 *
 * 핵심 단언:
 *   - api in-process port 가 [CandleRefreshService.backfillIntradayRange] 로 정상 위임(데이터 없는 종목도 backfill 후 평가).
 *   - backfillEnabled false → KIS fetch 0(DB 기존 봉만 평가).
 *   - lookback clamp(KIS 1년 ∧ ENV) — 거짓 결과 차단.
 *   - 정합성 등급(OK/INSUFFICIENT_DATA) 노출 — mock 보간 0.
 *   - per-symbol fail-soft — 한 종목 backfill 예외가 다른 종목·정합성 평가를 막지 않음.
 */
class BacktestCandlePrepareApiAdapterTest {
    private val candleRefreshService = mockk<CandleRefreshService>()
    private val candleRepository = mockk<CandleRepository>(relaxed = true)
    private val checker = CandleIntegrityChecker(candleRepository)

    private fun adapter(
        props: IntradayRealisticBacktestProperties = IntradayRealisticBacktestProperties(),
    ): BacktestCandlePrepareApiAdapter =
        BacktestCandlePrepareApiAdapter(
            candleRefreshService = candleRefreshService,
            candleIntegrityChecker = checker,
            properties = props,
        )

    // 2026-06-22(월)~06-26(금) = 5 거래일. 1m 기대봉 = 390*5 = 1950.
    private val from = LocalDate(2026, 6, 22)
    private val to = LocalDate(2026, 6, 26)

    private fun ts(n: Int): List<Instant> = (0 until n).map { Instant.ofEpochSecond(it.toLong()) }

    private fun okResults() =
        listOf(
            IntervalRefreshResult("1m", 1950, 1950, null, "OK"),
            IntervalRefreshResult("3m", 650, 650, null, "OK"),
        )

    @Test
    fun `backfillEnabled true면 데이터 없던 종목도 CandleRefreshService로 backfill 후 정합성 평가한다 (api in-process 실작동)`() {
        // 데이터 없던 종목: backfill 전 0건이었으나 backfillIntradayRange 위임 후 1950건 채워짐.
        every { candleRefreshService.backfillIntradayRange("005930", any(), any()) } returns okResults()
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1950)

        val reports = adapter().prepareAndReport(listOf("005930"), "1m", from, to, backfillEnabled = true)

        // api in-process port 가 CandleRefreshService(BACKFILL priority 고정)로 정확히 1회 위임.
        verify(exactly = 1) { candleRefreshService.backfillIntradayRange("005930", any(), any()) }
        assertThat(reports["005930"]!!.confidence).isEqualTo(CandleConfidence.OK)
        assertThat(reports["005930"]!!.actualBars).isEqualTo(1950)
    }

    @Test
    fun `backfillEnabled false면 KIS fetch 0 — DB 기존 봉만으로 정합성 평가`() {
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1950)

        val reports = adapter().prepareAndReport(listOf("005930"), "1m", from, to, backfillEnabled = false)

        verify(exactly = 0) { candleRefreshService.backfillIntradayRange(any(), any(), any()) }
        assertThat(reports["005930"]!!.confidence).isEqualTo(CandleConfidence.OK)
    }

    @Test
    fun `결측률 과다면 INSUFFICIENT_DATA 등급으로 정직 노출 (mock 보간 0)`() {
        every { candleRefreshService.backfillIntradayRange(any(), any(), any()) } returns okResults()
        // 기대 1950 중 실제 100건만 → 결측률 ~95% → INSUFFICIENT_DATA.
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(100)

        val reports = adapter().prepareAndReport(listOf("000660"), "1m", from, to, backfillEnabled = true)

        assertThat(reports["000660"]!!.confidence).isEqualTo(CandleConfidence.INSUFFICIENT_DATA)
    }

    @Test
    fun `lookback clamp — 요청 from이 maxLookbackDays보다 과거면 경계로 clamp되고 lookbackClamped true`() {
        val props = IntradayRealisticBacktestProperties(maxLookbackDays = 5)
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(0)

        // from 을 1년 전(클램프 발동)으로.
        val ancientFrom = LocalDate(2025, 6, 26)
        val reports = adapter(props).prepareAndReport(listOf("005930"), "1m", ancientFrom, to, backfillEnabled = false)

        // clamp 됐으므로 보고 거래일은 5일치(1950 기대) — 1년치(252일)가 아니어야 함.
        assertThat(reports["005930"]!!.lookbackClamped).isTrue()
        assertThat(reports["005930"]!!.expectedBars).isEqualTo(1950)
    }

    @Test
    fun `per-symbol fail-soft — 한 종목 backfill 예외가 다른 종목 평가를 막지 않는다`() {
        every { candleRefreshService.backfillIntradayRange("FAIL01", any(), any()) } throws RuntimeException("KIS EGW")
        every { candleRefreshService.backfillIntradayRange("OK0001", any(), any()) } returns okResults()
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1950)

        val reports = adapter().prepareAndReport(listOf("FAIL01", "OK0001"), "1m", from, to, backfillEnabled = true)

        // 실패 종목도 DB 기존 봉으로 정합성 평가 — 둘 다 보고에 존재(엔진이 등급으로 skip 판정).
        assertThat(reports).containsKeys("FAIL01", "OK0001")
        assertThat(reports["OK0001"]!!.confidence).isEqualTo(CandleConfidence.OK)
        verify(exactly = 1) { candleRefreshService.backfillIntradayRange("OK0001", any(), any()) }
    }

    @Test
    fun `BACKFILL 위임 시각 범위가 KST 거래일 경계를 따른다`() {
        every { candleRefreshService.backfillIntradayRange(any(), any(), any()) } returns okResults()
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1950)

        adapter().prepareAndReport(listOf("005930"), "1m", from, to, backfillEnabled = true)

        // from = 2026-06-22 00:00 KST = 2026-06-21T15:00:00Z, to = 06-26 24:00 KST - 1ns.
        val expectedFrom = Instant.parse("2026-06-21T15:00:00Z")
        verify {
            candleRefreshService.backfillIntradayRange(
                "005930",
                expectedFrom,
                match { it.isAfter(Instant.parse("2026-06-26T14:59:00Z")) },
            )
        }
    }
}
