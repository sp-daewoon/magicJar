package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.mcp.McpTool
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalBigDecimal
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalInt
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalString
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.requireString
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.requireStringList
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * MCP tool `backtest_strategy` — 백테스트 실행 + 성과 카드 (ADR 0027 §2-2).
 *
 * 기존 [BacktestService] reuse — modify 0 (영역 침범 0 LOCK).
 *
 * ## input
 * - `strategyId`: 저장된 strategy id (필수)
 * - `universe`: 종목 코드 list (필수 · 최소 1개)
 * - `interval`: 캔들 인터벌 (필수)
 * - `from`: yyyy-MM-dd (필수)
 * - `to`: yyyy-MM-dd (필수)
 * - `initialCapital`: 초기 자본 (옵션 · default 10_000_000)
 * - `priceAdjustmentMode`: ADJUSTED · RAW (옵션 · default ADJUSTED)
 *
 * ## output
 * `{ status: SUCCESS|ABORTED, performance: { sharpe, mdd, winRate, ... }, trades: [...], finalCapital, durationMs }`
 */
@Component
class BacktestStrategyTool(
    private val backtestService: BacktestService,
) : McpTool {
    override val name: String = "backtest_strategy"
    override val description: String =
        "저장된 strategy DSL 기반 backtest 실행 + 성과 카드 (Sharpe · MDD · 승률 · profit factor · CAGR 등)."

    override val inputSchema: Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "strategyId" to mapOf("type" to "integer", "minimum" to 1, "description" to "저장된 strategy id"),
                    "universe" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "minItems" to 1,
                            "description" to "종목 코드 list (최소 1개)",
                        ),
                    "interval" to
                        mapOf(
                            "type" to "string",
                            "enum" to listOf("1d", "1wk", "1mo"),
                            "description" to "캔들 인터벌 (1차 일봉 이상만)",
                        ),
                    "from" to mapOf("type" to "string", "format" to "date", "description" to "yyyy-MM-dd"),
                    "to" to mapOf("type" to "string", "format" to "date", "description" to "yyyy-MM-dd"),
                    "initialCapital" to
                        mapOf(
                            "type" to "number",
                            "minimum" to 0,
                            "default" to DEFAULT_CAPITAL,
                            "description" to "초기 자본 (KRW · default 1천만원)",
                        ),
                    "priceAdjustmentMode" to
                        mapOf(
                            "type" to "string",
                            "enum" to listOf("ADJUSTED", "RAW"),
                            "default" to "ADJUSTED",
                            "description" to "가격 조정 모드 (default ADJUSTED — 권리락/배당 반영)",
                        ),
                    "tradesLimit" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 0,
                            "maximum" to 5000,
                            "default" to 200,
                            "description" to "응답 trades 최대 수 (응답 크기 cap · 0=trades 미포함)",
                        ),
                ),
            "required" to listOf("strategyId", "universe", "interval", "from", "to"),
        )

    override fun execute(
        params: Map<String, Any?>,
        userId: String,
    ): Map<String, Any?> {
        val strategyId =
            params.optionalInt("strategyId", min = 1)
                ?: throw McpToolException("필수 파라미터 누락: strategyId")
        val universe = params.requireStringList("universe", minSize = 1)
        val interval = params.requireString("interval") { "interval 누락" }
        val from = params.requireString("from") { "from 누락" }
        val to = params.requireString("to") { "to 누락" }
        val initialCapital =
            params.optionalBigDecimal("initialCapital", default = BigDecimal(DEFAULT_CAPITAL))!!
        val priceAdjustmentMode = params.optionalString("priceAdjustmentMode") ?: "ADJUSTED"
        val tradesLimit = params.optionalInt("tradesLimit", default = 200, min = 0, max = 5000) ?: 200

        val result =
            backtestService.runBacktest(
                strategyId = strategyId.toLong(),
                universe = universe,
                interval = interval,
                fromStr = from,
                toStr = to,
                initialCapital = initialCapital,
                priceAdjustmentMode = priceAdjustmentMode,
            )

        return when (result) {
            is BacktestResult.Success -> result.toResponseMap(tradesLimit)
            is BacktestResult.Aborted -> result.toResponseMap(tradesLimit)
        }
    }

    private fun BacktestResult.Success.toResponseMap(tradesLimit: Int): Map<String, Any?> =
        mapOf(
            "status" to "SUCCESS",
            "finalCapital" to finalCapital.toPlainString(),
            "performance" to performance.toMap(),
            "tradesCount" to trades.size,
            "trades" to if (tradesLimit == 0) emptyList() else trades.take(tradesLimit).map { it.toMap() },
            "positionsCount" to positions.size,
            "executedAt" to executedAt.toString(),
            "durationMs" to durationMs,
        )

    private fun BacktestResult.Aborted.toResponseMap(tradesLimit: Int): Map<String, Any?> =
        mapOf(
            "status" to "ABORTED",
            "reason" to reason,
            "timeoutCount" to timeoutCount,
            "partialTradesCount" to partialTrades.size,
            "trades" to if (tradesLimit == 0) emptyList() else partialTrades.take(tradesLimit).map { it.toMap() },
            "executedAt" to executedAt.toString(),
            "durationMs" to durationMs,
        )

    private fun BacktestPerformance.toMap(): Map<String, Any?> =
        mapOf(
            "totalReturn" to totalReturn.toPlainString(),
            "cagr" to cagr.toPlainString(),
            "maxDrawdown" to maxDrawdown.toPlainString(),
            "sharpeRatio" to sharpeRatio?.toPlainString(),
            "winRate" to winRate.toPlainString(),
            "totalTrades" to totalTrades,
            "winningTrades" to winningTrades,
            "losingTrades" to losingTrades,
            "avgWin" to avgWin.toPlainString(),
            "avgLoss" to avgLoss.toPlainString(),
            "profitFactor" to profitFactor?.toPlainString(),
            "tradingFrequency" to tradingFrequency.toPlainString(),
        )

    private fun BacktestTrade.toMap(): Map<String, Any?> =
        mapOf(
            "code" to code,
            "side" to side.name,
            "quantity" to quantity,
            "executedPrice" to executedPrice.toPlainString(),
            "rawPrice" to rawPrice.toPlainString(),
            "executedAt" to executedAt.toString(),
            "commission" to commission.toPlainString(),
            "tax" to tax.toPlainString(),
            "slippage" to slippage.toPlainString(),
            "signalReason" to signalReason,
        )

    private companion object {
        const val DEFAULT_CAPITAL = 10_000_000L
    }
}
