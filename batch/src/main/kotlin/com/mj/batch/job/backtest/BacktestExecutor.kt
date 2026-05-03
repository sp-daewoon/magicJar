package com.mj.batch.job.backtest

import com.mj.batch.infrastructure.persistence.backtest.BacktestEquityPointId
import com.mj.batch.infrastructure.persistence.backtest.BacktestEquityPointJpa
import com.mj.batch.infrastructure.persistence.backtest.BacktestEquityPointJpaRepository
import com.mj.batch.infrastructure.persistence.backtest.BacktestTradeJpa
import com.mj.batch.infrastructure.persistence.backtest.BacktestTradeJpaRepository
import com.mj.batch.infrastructure.persistence.market.CandleJpa
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import com.mj.domain.common.Market
import com.mj.domain.market.instrument.port.InstrumentRepository
import com.mj.domain.strategy.backtest.BacktestBar
import com.mj.domain.strategy.backtest.BacktestEngine
import com.mj.domain.strategy.backtest.BacktestSummary
import com.mj.domain.strategy.backtest.CostModel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * BacktestExecutor — 도메인 [BacktestEngine] 호출 + 영속 어댑터 (track9 R1).
 *
 * 책임:
 * 1. candle 테이블에서 봉 시계열 로드 (adjusted_close 우선, 없으면 raw close)
 * 2. 도메인 BacktestEngine 호출
 * 3. equity curve / trades 영속 (V15 테이블)
 *
 * 멱등성: 동일 runId 재실행 시 기존 레코드 삭제 후 재삽입.
 *
 * 비범위:
 * - candle backfill (별도 잡 KisRestPort.fetchCandles)
 * - backtest_run status 업데이트 (호출자 [BacktestRunJob] 책임)
 * - Kafka 발행 (호출자 책임)
 *
 * 다중 종목·멀티 포지션은 R2 이후. 현재는 universe 1종목 long-only.
 */
@Component
class BacktestExecutor(
    private val candleRepo: CandleJpaRepository,
    private val instrumentRepo: InstrumentRepository,
    private val tradeRepo: BacktestTradeJpaRepository,
    private val equityRepo: BacktestEquityPointJpaRepository,
) {
    private val log = LoggerFactory.getLogger(BacktestExecutor::class.java)
    private val zone = ZoneId.of("Asia/Seoul")

    @Transactional
    fun execute(
        runId: String,
        symbol: String,
        from: LocalDate,
        to: LocalDate,
        conditionExpr: String,
        initialCapital: BigDecimal,
        costModel: CostModel = CostModel.DEFAULT,
        interval: Interval = Interval.D1,
    ): ExecutionResult {
        // 1. 캔들 로드 (KST 기준 fromInstant ~ toInstant + 1d)
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toInstant = to.atStartOfDay(zone).plusDays(1).toInstant()
        val rawCandles =
            candleRepo
                .findByCodeAndIntervalAndOpenTimeBetween(symbol, interval, fromInstant, toInstant)
                .sortedBy { it.openTime }
        if (rawCandles.isEmpty()) {
            log.warn(
                "[backtest-executor] no candles found runId={} symbol={} from={} to={}",
                runId,
                symbol,
                from,
                to,
            )
            return ExecutionResult.empty(runId, symbol)
        }

        // 2. CandleJpa → BacktestBar (adjusted_close 우선)
        val bars = rawCandles.map { it.toBacktestBar() }
        log.info(
            "[backtest-executor] runId={} symbol={} bars={} (adjusted={})",
            runId,
            symbol,
            bars.size,
            rawCandles.count { it.closeAdjusted != null },
        )

        // 3. 시장 식별 (KOSPI/KOSDAQ — 거래세율)
        val market = instrumentRepo.findByCode(symbol)?.market ?: Market.KOSPI

        // 4. 도메인 BacktestEngine 실행
        val engine = BacktestEngine(costModel)
        val summary =
            engine.run(
                symbol = symbol,
                market = market,
                bars = bars,
                initialCapital = initialCapital,
                conditionExpr = conditionExpr,
            )

        // 5. 영속 (멱등: 기존 레코드 삭제 후 재삽입)
        tradeRepo.deleteByRunId(runId)
        equityRepo.deleteByIdRunId(runId)
        persistTrades(runId, summary)
        persistEquityCurve(runId, summary)

        log.info(
            "[backtest-executor] runId={} trades={} equityPoints={} totalReturn={}",
            runId,
            summary.trades.size,
            summary.equityCurve.size,
            summary.metrics.totalReturn,
        )
        return ExecutionResult(
            runId = runId,
            symbol = symbol,
            summary = summary,
            persistedTrades = summary.trades.size,
            persistedEquityPoints = summary.equityCurve.size,
        )
    }

    private fun persistTrades(
        runId: String,
        summary: BacktestSummary,
    ) {
        if (summary.trades.isEmpty()) return
        val now = Instant.now()
        val entities =
            summary.trades.mapIndexed { i, t ->
                BacktestTradeJpa(
                    tradeId = t.id,
                    runId = runId,
                    symbol = summary.symbol,
                    sequence = i,
                    entryAt = t.entryDate,
                    exitAt = t.exitDate,
                    entryPrice = t.entryPrice,
                    exitPrice = t.exitPrice,
                    shares = t.shares,
                    pnl = t.pnl,
                    pnlPercent = t.pnlPercent,
                    entryCommission = t.entryCommission,
                    exitCommission = t.exitCommission,
                    exitTax = t.exitTax,
                    exitReason = t.reason.name,
                    createdAt = now,
                )
            }
        tradeRepo.saveAll(entities)
    }

    private fun persistEquityCurve(
        runId: String,
        summary: BacktestSummary,
    ) {
        if (summary.equityCurve.isEmpty()) return
        val entities =
            summary.equityCurve.mapIndexed { i, p ->
                BacktestEquityPointJpa(
                    id = BacktestEquityPointId(runId = runId, sequence = i),
                    at = p.at,
                    equity = p.equity,
                    cash = p.cash,
                    positionValue = p.positionValue,
                )
            }
        equityRepo.saveAll(entities)
    }

    /**
     * CandleJpa → BacktestBar.
     *
     * adjusted_close 사용 우선:
     * - close_adjusted != null → 모든 OHLCV 컬럼에 adjusted 사용 (Corp Action 보정 후 값).
     * - close_adjusted == null → raw OHLCV 사용 (Corp Action 없는 종목 또는 미보정 상태).
     *
     * rawClose는 항상 raw close를 보존(보고/디버깅).
     */
    private fun CandleJpa.toBacktestBar(): BacktestBar {
        val useAdjusted = closeAdjusted != null
        return BacktestBar(
            openTime = openTime,
            open = if (useAdjusted) (openAdjusted ?: open) else open,
            high = if (useAdjusted) (highAdjusted ?: high) else high,
            low = if (useAdjusted) (lowAdjusted ?: low) else low,
            close = if (useAdjusted) (closeAdjusted ?: close) else close,
            volume = if (useAdjusted) (volumeAdjusted ?: volume) else volume,
            rawClose = close,
        )
    }

    data class ExecutionResult(
        val runId: String,
        val symbol: String,
        val summary: BacktestSummary,
        val persistedTrades: Int,
        val persistedEquityPoints: Int,
    ) {
        companion object {
            fun empty(
                runId: String,
                symbol: String,
            ): ExecutionResult =
                ExecutionResult(
                    runId = runId,
                    symbol = symbol,
                    summary =
                        BacktestSummary(
                            symbol = symbol,
                            trades = emptyList(),
                            equityCurve = emptyList(),
                            metrics = com.mj.domain.strategy.backtest.PerformanceMetrics.EMPTY,
                        ),
                    persistedTrades = 0,
                    persistedEquityPoints = 0,
                )
        }
    }
}
