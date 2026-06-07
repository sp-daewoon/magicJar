package kr.spdaewoon.magicjar.api.strategyresearch

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.time.Instant

class StrategyResearchServiceTest {
    private val candleRepository: CandleJpaRepository = mock()
    private val service = StrategyResearchService(candleRepository, maxCandlesPerRun = 5000)

    @Test
    fun `propose returns research candidates without persisting or ordering`() {
        whenever(
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = "038110",
                interval = "1m",
                fromTs = Instant.parse("2026-04-28T00:00:00Z"),
                toTs = Instant.parse("2026-04-28T00:44:00Z"),
            ),
        ).thenReturn(washoutEntities())

        val report =
            service.propose(
                StrategyResearchRequestDto(
                    userId = "74683223",
                    code = "038110",
                    interval = "1m",
                    from = "2026-04-28T00:00:00Z",
                    to = "2026-04-28T00:44:00Z",
                    targetProfitPct = 1.0,
                    stopLossPct = 0.8,
                    maxHoldBars = 4,
                ),
            )

        report.candleCount shouldBe 45
        report.candidates.shouldNotBeEmpty()
        report.candidates
            .first { it.key == "VOLUME_WASHOUT_REBOUND" }
            .trades
            .shouldNotBeEmpty()
    }

    @Test
    fun `propose prepends previous candles when requested window has less than minimum candles`() {
        val allCandles = washoutEntities().take(40)
        val fromTs = Instant.parse("2026-04-28T00:18:00Z")
        val toTs = Instant.parse("2026-04-28T00:39:00Z")
        whenever(
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = "038110",
                interval = "1m",
                fromTs = fromTs,
                toTs = toTs,
            ),
        ).thenReturn(allCandles.drop(18))
        whenever(
            candleRepository.findByCodeAndIntervalAndTsBefore(
                code = "038110",
                interval = "1m",
                before = fromTs,
                pageable = PageRequest.of(0, 18),
            ),
        ).thenReturn(allCandles.take(18).asReversed())

        val report =
            service.propose(
                StrategyResearchRequestDto(
                    userId = "74683223",
                    code = "038110",
                    interval = "1m",
                    from = fromTs.toString(),
                    to = toTs.toString(),
                    targetProfitPct = 1.0,
                    stopLossPct = 0.8,
                    maxHoldBars = 4,
                ),
            )

        report.candleCount shouldBe 40
        report.warnings.any { it.contains("최소 40개 candle") } shouldBe false
    }

    private fun washoutEntities(): List<CandleJpaEntity> {
        val candles = mutableListOf<CandleJpaEntity>()
        repeat(45) { index ->
            val ts = Instant.parse("2026-04-28T00:${index.toString().padStart(2, '0')}:00Z")
            val open: String
            val high: String
            val low: String
            val close: String
            val volume: Long
            when (index) {
                25 -> {
                    open = "100.0"
                    high = "100.2"
                    low = "97.8"
                    close = "98.0"
                    volume = 3000
                }
                26 -> {
                    open = "98.0"
                    high = "99.3"
                    low = "97.9"
                    close = "99.0"
                    volume = 1800
                }
                27 -> {
                    open = "99.0"
                    high = "100.5"
                    low = "98.8"
                    close = "100.1"
                    volume = 1600
                }
                else -> {
                    open = "100.0"
                    high = "100.4"
                    low = "99.6"
                    close = "100.0"
                    volume = 1000
                }
            }
            candles += candle(ts, open, high, low, close, volume)
        }
        return candles
    }

    private fun candle(
        ts: Instant,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: Long,
    ): CandleJpaEntity =
        CandleJpaEntity(
            code = "038110",
            interval = "1m",
            ts = ts,
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            volume = volume,
            source = "TEST",
        )
}
