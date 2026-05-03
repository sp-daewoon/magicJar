package com.mj.batch.job.backtest

import com.mj.batch.infrastructure.persistence.backtest.BacktestEquityPointJpa
import com.mj.batch.infrastructure.persistence.backtest.BacktestEquityPointJpaRepository
import com.mj.batch.infrastructure.persistence.backtest.BacktestTradeJpa
import com.mj.batch.infrastructure.persistence.backtest.BacktestTradeJpaRepository
import com.mj.batch.infrastructure.persistence.market.CandleJpa
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
import com.mj.domain.market.instrument.port.InstrumentRepository
import com.mj.domain.strategy.backtest.CostModel
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * BacktestExecutor 단위 테스트 (track9 R1).
 *
 * 검증:
 * 1. CandleJpa → BacktestBar 변환 시 adjusted 컬럼 우선 사용
 * 2. 도메인 BacktestEngine 호출 → trades/equity 영속
 * 3. 멱등성: 동일 runId 재실행 시 deleteByRunId 호출
 * 4. 빈 candle 시 empty result
 */
class BacktestExecutorTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val candleRepo = mockk<CandleJpaRepository>(relaxed = true)
    private val instrumentRepo = mockk<InstrumentRepository>(relaxed = true)
    private val tradeRepo = mockk<BacktestTradeJpaRepository>(relaxed = true)
    private val equityRepo = mockk<BacktestEquityPointJpaRepository>(relaxed = true)
    private val executor = BacktestExecutor(candleRepo, instrumentRepo, tradeRepo, equityRepo)

    private fun makeCandle(
        code: String,
        date: LocalDate,
        close: BigDecimal,
        open: BigDecimal = close,
        adjustedClose: BigDecimal? = null,
    ): CandleJpa =
        CandleJpa(
            code = code,
            interval = Interval.D1,
            openTime = date.atStartOfDay(zone).toInstant(),
            open = open,
            high = close,
            low = close,
            close = close,
            volume = 1_000_000L,
            tradeCount = 0L,
            openAdjusted = adjustedClose?.let { open },
            highAdjusted = adjustedClose,
            lowAdjusted = adjustedClose,
            closeAdjusted = adjustedClose,
            volumeAdjusted = if (adjustedClose != null) 1_000_000L else null,
            adjustmentFactor = if (adjustedClose != null) BigDecimal("1.0") else null,
        )

    @Test
    fun `empty candles produces empty result without persistence`() {
        every {
            candleRepo.findByCodeAndIntervalAndOpenTimeBetween(any(), any(), any(), any())
        } returns emptyList()

        val result =
            executor.execute(
                runId = "run-empty",
                symbol = "005930",
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 12, 31),
                conditionExpr = "close > 0",
                initialCapital = BigDecimal("1000000"),
            )

        assertEquals(0, result.persistedTrades)
        assertEquals(0, result.persistedEquityPoints)
        assertEquals(0, result.summary.trades.size)
        verify(exactly = 0) { tradeRepo.saveAll(any<List<BacktestTradeJpa>>()) }
        verify(exactly = 0) { equityRepo.saveAll(any<List<BacktestEquityPointJpa>>()) }
    }

    @Test
    fun `trades and equity points are persisted with correct count`() {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 1, 10)
        val symbol = "005930"

        // 5개 봉. ON-OFF 패턴.
        val candles =
            listOf(
                makeCandle(symbol, LocalDate.of(2024, 1, 2), BigDecimal("150"), open = BigDecimal("150")),
                makeCandle(symbol, LocalDate.of(2024, 1, 3), BigDecimal("160"), open = BigDecimal("155")),
                makeCandle(symbol, LocalDate.of(2024, 1, 4), BigDecimal("90"), open = BigDecimal("100")),
                makeCandle(symbol, LocalDate.of(2024, 1, 5), BigDecimal("80"), open = BigDecimal("85")),
                makeCandle(symbol, LocalDate.of(2024, 1, 8), BigDecimal("70"), open = BigDecimal("75")),
            )
        every {
            candleRepo.findByCodeAndIntervalAndOpenTimeBetween(symbol, Interval.D1, any(), any())
        } returns candles

        every { instrumentRepo.findByCode(symbol) } returns
            Instrument(
                code = symbol,
                isin = null,
                name = "삼성전자",
                nameEn = null,
                market = Market.KOSPI,
                type = InstrumentType.STOCK,
                sector = null,
                listedAt = LocalDate.of(2000, 1, 1),
                delistedAt = null,
                parValue = BigDecimal("100"),
                tradingUnit = 1,
                active = true,
            )

        justRun { tradeRepo.deleteByRunId(any()) }
        justRun { equityRepo.deleteByIdRunId(any()) }
        val tradesSlot = slot<List<BacktestTradeJpa>>()
        val equitySlot = slot<List<BacktestEquityPointJpa>>()
        every { tradeRepo.saveAll(capture(tradesSlot)) } answers { tradesSlot.captured }
        every { equityRepo.saveAll(capture(equitySlot)) } answers { equitySlot.captured }

        val result =
            executor.execute(
                runId = "run-1",
                symbol = symbol,
                from = from,
                to = to,
                conditionExpr = "close > 100",
                initialCapital = BigDecimal("1000000"),
                costModel = CostModel.ZERO,
            )

        // 봉 0: ON, 봉 1: ON, 봉 2: OFF → 매도, 봉 3: OFF, 봉 4: OFF
        // 진입은 봉 1 open=155 (봉 0 신호 → 봉 1 체결)
        // 청산은 봉 3 open=85 (봉 2 신호 → 봉 3 체결)
        // 1 trade.
        assertEquals(1, result.summary.trades.size)
        assertEquals(1, result.persistedTrades)
        assertEquals(5, result.persistedEquityPoints)
        verify(exactly = 1) { tradeRepo.deleteByRunId("run-1") }
        verify(exactly = 1) { equityRepo.deleteByIdRunId("run-1") }
        assertEquals("run-1", tradesSlot.captured.first().runId)
        assertEquals(symbol, tradesSlot.captured.first().symbol)
        // sequence는 0부터 시작
        assertEquals(0, tradesSlot.captured.first().sequence)
        // equity points 시퀀스도 0~4
        val sequences = equitySlot.captured.map { it.id.sequence }
        assertEquals(listOf(0, 1, 2, 3, 4), sequences)
    }

    @Test
    fun `adjusted close is preferred when available`() {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 1, 10)
        val symbol = "005930"

        // raw close 150이지만 adjusted close 75 (50:1 분할 시뮬레이션)
        val candles =
            listOf(
                makeCandle(symbol, LocalDate.of(2024, 1, 2), BigDecimal("150"), open = BigDecimal("150"), adjustedClose = BigDecimal("75")),
                makeCandle(symbol, LocalDate.of(2024, 1, 3), BigDecimal("160"), open = BigDecimal("155"), adjustedClose = BigDecimal("80")),
                makeCandle(symbol, LocalDate.of(2024, 1, 4), BigDecimal("180"), open = BigDecimal("170"), adjustedClose = BigDecimal("90")),
            )
        every {
            candleRepo.findByCodeAndIntervalAndOpenTimeBetween(symbol, Interval.D1, any(), any())
        } returns candles

        every { instrumentRepo.findByCode(symbol) } returns
            Instrument(
                code = symbol,
                isin = null,
                name = "T",
                nameEn = null,
                market = Market.KOSPI,
                type = InstrumentType.STOCK,
                sector = null,
                listedAt = LocalDate.of(2000, 1, 1),
                delistedAt = null,
                parValue = BigDecimal("100"),
                tradingUnit = 1,
                active = true,
            )

        justRun { tradeRepo.deleteByRunId(any()) }
        justRun { equityRepo.deleteByIdRunId(any()) }
        val equitySlot = slot<List<BacktestEquityPointJpa>>()
        every { equityRepo.saveAll(capture(equitySlot)) } answers { equitySlot.captured }

        // close > 100 — adjusted=75/80/90 모두 false → 진입 X
        val result =
            executor.execute(
                runId = "run-2",
                symbol = symbol,
                from = from,
                to = to,
                conditionExpr = "close > 100",
                initialCapital = BigDecimal("1000000"),
                costModel = CostModel.ZERO,
            )

        // adjusted close가 100 미만이라 진입 안 됨 — raw로 평가됐다면 모두 ON이므로 진입 발생.
        // 진입 0건이어야 adjusted 우선 사용 입증.
        assertEquals(0, result.summary.trades.size)
        // 자산 곡선 3점 모두 1,000,000 그대로 유지 (포지션 없음)
        assertEquals(3, result.persistedEquityPoints)
        equitySlot.captured.forEach {
            assertTrue(
                it.equity.compareTo(BigDecimal("1000000.00")) == 0,
                "Expected equity 1000000.00 (no position), got ${it.equity}",
            )
        }
    }

    @Test
    fun `defaults to KOSPI when instrument unknown`() {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 1, 5)
        val symbol = "999999"

        val candles =
            listOf(
                makeCandle(symbol, LocalDate.of(2024, 1, 2), BigDecimal("150")),
                makeCandle(symbol, LocalDate.of(2024, 1, 3), BigDecimal("160")),
            )
        every {
            candleRepo.findByCodeAndIntervalAndOpenTimeBetween(symbol, Interval.D1, any(), any())
        } returns candles
        every { instrumentRepo.findByCode(symbol) } returns null
        justRun { tradeRepo.deleteByRunId(any()) }
        justRun { equityRepo.deleteByIdRunId(any()) }

        val result =
            executor.execute(
                runId = "run-3",
                symbol = symbol,
                from = from,
                to = to,
                conditionExpr = "close > 100",
                initialCapital = BigDecimal("1000000"),
            )

        // 단순 실행 성공만 검증 (KOSPI 디폴트로 처리)
        assertTrue(result.persistedEquityPoints >= 1)
    }
}
