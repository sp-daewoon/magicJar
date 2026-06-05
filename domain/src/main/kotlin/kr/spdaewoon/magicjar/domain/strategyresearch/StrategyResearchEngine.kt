package kr.spdaewoon.magicjar.domain.strategyresearch

import kotlinx.datetime.Instant
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StrategyResearchEngine {
    fun research(
        request: StrategyResearchRequest,
        candles: List<ResearchCandle>,
    ): StrategyResearchReport {
        val sortedCandles =
            candles
                .filter { it.code == request.code && it.interval == request.interval }
                .sortedBy { it.ts }

        val warnings = dataWarnings(sortedCandles)
        if (sortedCandles.size < MIN_CANDLES) {
            return StrategyResearchReport(
                request = request,
                candleCount = sortedCandles.size,
                marketRegime = marketRegime(sortedCandles),
                warnings = warnings + "전략 후보를 평가하려면 최소 ${MIN_CANDLES}개 candle이 필요합니다.",
                candles = sortedCandles.map(::toPoint),
                candidates = emptyList(),
            )
        }

        val candidates =
            candidateSpecs(request)
                .map { spec -> spec.toCandidate(request, sortedCandles) }
                .sortedByDescending { it.score }

        return StrategyResearchReport(
            request = request,
            candleCount = sortedCandles.size,
            marketRegime = marketRegime(sortedCandles),
            warnings = warnings,
            candles = sortedCandles.map(::toPoint),
            candidates = candidates,
        )
    }

    private fun CandidateSpec.toCandidate(
        request: StrategyResearchRequest,
        candles: List<ResearchCandle>,
    ): StrategyResearchCandidate {
        val variants =
            exitProfiles(request)
                .map { profile ->
                    val profileRequest =
                        request.copy(
                            targetProfitPct = profile.targetProfitPct,
                            stopLossPct = profile.stopLossPct,
                            maxHoldBars = profile.maxHoldBars,
                        )
                    val trades = simulate(profileRequest, candles, this)
                    val stats = stats(trades)
                    CandidateVariant(profileRequest, trades, stats, score(stats))
                }
        val bestVariant = variants.maxWith(compareBy<CandidateVariant> { it.score }.thenBy { it.stats.trades })
        return StrategyResearchCandidate(
            key = key,
            title = title,
            summary = summary(bestVariant.stats),
            marketFit = marketFit,
            entryRules = entryRules,
            exitRules = exitRules(bestVariant.request),
            riskRules = riskRules(bestVariant.request),
            entryDslDraft = entryDsl,
            exitDslDraft = exitDsl(bestVariant.request),
            stats = bestVariant.stats,
            trades = bestVariant.trades,
            score = bestVariant.score,
        )
    }

    private fun simulate(
        request: StrategyResearchRequest,
        candles: List<ResearchCandle>,
        spec: CandidateSpec,
    ): List<StrategyResearchTrade> {
        val trades = mutableListOf<StrategyResearchTrade>()
        var index = 1
        while (index < candles.lastIndex) {
            if (!spec.entrySignal(candles, index)) {
                index += 1
                continue
            }

            val signalIndex = index
            val entryIndex = index + 1
            if (entryIndex > candles.lastIndex) break

            val entryPrice = candles[entryIndex].open.toDouble()
            if (entryPrice <= 0.0) {
                index += 1
                continue
            }

            val targetPrice = entryPrice * (1.0 + request.targetProfitPct / 100.0)
            val stopPrice = entryPrice * (1.0 - request.stopLossPct / 100.0)
            val maxExitIndex = min(candles.lastIndex, entryIndex + request.maxHoldBars)
            var exitIndex = maxExitIndex
            var exitPrice = candles[maxExitIndex].close.toDouble()
            var exitReason = StrategyResearchExitReason.TIME_EXIT

            for (cursor in entryIndex..maxExitIndex) {
                val candle = candles[cursor]
                val low = candle.low.toDouble()
                val high = candle.high.toDouble()
                if (low <= stopPrice) {
                    exitIndex = cursor
                    exitPrice = stopPrice
                    exitReason = StrategyResearchExitReason.STOP_LOSS
                    break
                }
                if (high >= targetPrice) {
                    exitIndex = cursor
                    exitPrice = targetPrice
                    exitReason = StrategyResearchExitReason.TAKE_PROFIT
                    break
                }
            }

            val returnPct = ((exitPrice - entryPrice) / entryPrice) * 100.0
            trades +=
                StrategyResearchTrade(
                    signalTime = candles[signalIndex].ts,
                    entryTime = candles[entryIndex].ts,
                    exitTime = candles[exitIndex].ts,
                    entryPrice = bd(entryPrice),
                    exitPrice = bd(exitPrice),
                    returnPct = round(returnPct),
                    exitReason = exitReason,
                    barsHeld = max(1, exitIndex - entryIndex + 1),
                    reason = spec.tradeReason(candles, signalIndex),
                )
            index = exitIndex + 1
        }
        return trades
    }

    private fun stats(trades: List<StrategyResearchTrade>): StrategyResearchStats {
        if (trades.isEmpty()) {
            return StrategyResearchStats(
                trades = 0,
                wins = 0,
                losses = 0,
                winRatePct = 0.0,
                totalReturnPct = 0.0,
                avgTradeReturnPct = 0.0,
                bestTradePct = 0.0,
                worstTradePct = 0.0,
                maxDrawdownPct = 0.0,
                maxConsecutiveLosses = 0,
                profitFactor = null,
            )
        }

        val wins = trades.count { it.returnPct > 0.0 }
        val losses = trades.count { it.returnPct < 0.0 }
        val winRate = wins.toDouble() / trades.size.toDouble() * 100.0
        val gains = trades.filter { it.returnPct > 0.0 }.sumOf { it.returnPct }
        val lossAbs = abs(trades.filter { it.returnPct < 0.0 }.sumOf { it.returnPct })
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        var consecutiveLosses = 0
        var maxConsecutiveLosses = 0

        trades.forEach { trade ->
            equity *= 1.0 + trade.returnPct / 100.0
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak * 100.0)
            if (trade.returnPct < 0.0) {
                consecutiveLosses += 1
                maxConsecutiveLosses = max(maxConsecutiveLosses, consecutiveLosses)
            } else {
                consecutiveLosses = 0
            }
        }

        return StrategyResearchStats(
            trades = trades.size,
            wins = wins,
            losses = losses,
            winRatePct = round(winRate),
            totalReturnPct = round((equity - 1.0) * 100.0),
            avgTradeReturnPct = round(trades.sumOf { it.returnPct } / trades.size.toDouble()),
            bestTradePct = round(trades.maxOf { it.returnPct }),
            worstTradePct = round(trades.minOf { it.returnPct }),
            maxDrawdownPct = round(maxDrawdown),
            maxConsecutiveLosses = maxConsecutiveLosses,
            profitFactor = if (lossAbs == 0.0) null else round(gains / lossAbs),
        )
    }

    private fun candidateSpecs(request: StrategyResearchRequest): List<CandidateSpec> =
        listOf(
            CandidateSpec(
                key = "MA10_RECLAIM_SCALP",
                title = "MA10 회복 반등 스캘프",
                marketFit = "하락 추세 중 단기 평균선 회복 구간만 짧게 노립니다.",
                entryRules =
                    listOf(
                        "직전 candle 종가가 MA10 아래",
                        "현재 candle 종가가 MA10 위로 회복",
                        "현재 종가가 시가 이상",
                        "MA20 대비 과열 구간은 제외",
                    ),
                entryDsl = "close crosses_above ma(10) and close >= open",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousMa10 = ma(candles, 10, index - 1)
                    val currentMa10 = ma(candles, 10, index)
                    val ma20 = ma(candles, 20, index)
                    previousMa10 != null &&
                        currentMa10 != null &&
                        ma20 != null &&
                        candles[index - 1].close.toDouble() < previousMa10 &&
                        candles[index].close.toDouble() >= currentMa10 &&
                        candles[index].close >= candles[index].open &&
                        candles[index].close.toDouble() <= ma20 * 1.025
                },
                reasonFactory = { candles, index ->
                    "MA10 ${price(ma(candles, 10, index))} 회복, 양봉 종가"
                },
            ),
            CandidateSpec(
                key = "RSI30_REBOUND_SCALP",
                title = "RSI30 재진입 반등 스캘프",
                marketFit = "급락 뒤 과매도에서 빠져나오는 첫 반등만 잡습니다.",
                entryRules =
                    listOf(
                        "직전 RSI14가 30 이하",
                        "현재 RSI14가 30 위로 회복",
                        "현재 종가가 직전 종가보다 높음",
                        "거래량이 최근 20개 평균의 60% 이상",
                    ),
                entryDsl = "rsi(14) crosses_above 30 and close > close[1]",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousRsi = rsi(candles, 14, index - 1)
                    val currentRsi = rsi(candles, 14, index)
                    val avgVolume = avgVolume(candles, 20, index)
                    previousRsi != null &&
                        currentRsi != null &&
                        avgVolume != null &&
                        previousRsi <= 30.0 &&
                        currentRsi > 30.0 &&
                        candles[index].close > candles[index - 1].close &&
                        candles[index].volume.toDouble() >= avgVolume * 0.6
                },
                reasonFactory = { candles, index ->
                    "RSI14 ${round(rsi(candles, 14, index) ?: 0.0)}로 30선 회복"
                },
            ),
            CandidateSpec(
                key = "VOLUME_WASHOUT_REBOUND",
                title = "거래량 투매 후 반등 스캘프",
                marketFit = "투매성 음봉 뒤 저가 방어와 거래량 반등이 보일 때만 진입합니다.",
                entryRules =
                    listOf(
                        "직전 candle이 -1.2% 이상 하락",
                        "직전 거래량이 최근 20개 평균의 1.5배 이상",
                        "현재 candle이 양봉",
                        "현재 종가가 직전 종가를 회복",
                    ),
                entryDsl = "prev_drop <= -1.2% and volume[1] > avg_volume(20) * 1.5 and close > open",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val avgVolume = avgVolume(candles, 20, index - 1)
                    val previousDrop = candleReturnPct(candles[index - 1])
                    avgVolume != null &&
                        previousDrop <= -1.2 &&
                        candles[index - 1].volume.toDouble() >= avgVolume * 1.5 &&
                        candles[index].close > candles[index].open &&
                        candles[index].close >= candles[index - 1].close
                },
                reasonFactory = { candles, index ->
                    "직전 투매 ${round(candleReturnPct(candles[index - 1]))}%, 거래량 반등"
                },
            ),
        ).map { spec ->
            spec.copy(
                exitRulesFactory = {
                    listOf(
                        "목표 수익 ${request.targetProfitPct}% 도달 시 익절",
                        "손실 ${request.stopLossPct}% 도달 시 손절",
                        "${request.maxHoldBars}개 candle 안에 목표 미도달 시 시간 청산",
                    )
                },
                riskRulesFactory = {
                    listOf(
                        "동시에 1포지션만 보유",
                        "신호 candle 종가가 아니라 다음 candle 시가 체결로 평가",
                        "같은 candle에서 익절·손절이 모두 닿으면 손절을 우선 적용",
                    )
                },
            )
        }

    private fun marketRegime(candles: List<ResearchCandle>): StrategyResearchMarketRegime {
        if (candles.size < 2) {
            return StrategyResearchMarketRegime(
                type = "UNKNOWN",
                trendPct = 0.0,
                volatilityPct = 0.0,
                explanation = "candle 수가 부족합니다.",
            )
        }
        val first = candles.first().close.toDouble()
        val last = candles.last().close.toDouble()
        val trendPct = if (first == 0.0) 0.0 else ((last - first) / first) * 100.0
        val returns =
            candles
                .zipWithNext()
                .mapNotNull { (prev, cur) ->
                    val previous = prev.close.toDouble()
                    if (previous == 0.0) null else abs((cur.close.toDouble() - previous) / previous * 100.0)
                }
        val volatility = if (returns.isEmpty()) 0.0 else returns.average()
        val type =
            when {
                trendPct <= -6.0 -> "DOWNTREND"
                trendPct >= 6.0 -> "UPTREND"
                else -> "SIDEWAYS"
            }
        val explanation =
            when (type) {
                "DOWNTREND" -> "구간 수익률이 음수라 추세 추종보다 짧은 반등형 후보를 우선 평가했습니다."
                "UPTREND" -> "구간 수익률이 양수라 눌림 후 회복 후보가 유리할 수 있습니다."
                else -> "방향성이 약해 평균 회귀와 거래량 반등 후보를 함께 평가했습니다."
            }
        return StrategyResearchMarketRegime(
            type = type,
            trendPct = round(trendPct),
            volatilityPct = round(volatility),
            explanation = explanation,
        )
    }

    private fun dataWarnings(candles: List<ResearchCandle>): List<String> {
        val warnings = mutableListOf<String>()
        if (candles.isEmpty()) {
            warnings += "해당 종목·interval·기간에 저장된 candle이 없습니다."
            return warnings
        }
        val duplicated = candles.size - candles.map { it.ts }.distinct().size
        if (duplicated > 0) warnings += "중복 candle timestamp ${duplicated}건이 감지되었습니다."
        return warnings
    }

    private fun score(stats: StrategyResearchStats): Double {
        if (stats.trades == 0) return -100.0
        val tradePenalty = if (stats.trades < 3) (3 - stats.trades) * 2.0 else 0.0
        return round(stats.totalReturnPct + stats.winRatePct * 0.08 - stats.maxDrawdownPct * 1.3 - tradePenalty)
    }

    private fun summary(stats: StrategyResearchStats): String =
        if (stats.trades == 0) {
            "해당 구간에서 조건을 만족한 진입이 없었습니다."
        } else {
            "거래 ${stats.trades}회, 승률 ${stats.winRatePct}%, 누적 ${stats.totalReturnPct}%"
        }

    private fun toPoint(candle: ResearchCandle): StrategyResearchCandlePoint =
        StrategyResearchCandlePoint(
            ts = candle.ts,
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            volume = candle.volume,
        )

    private fun exitProfiles(request: StrategyResearchRequest): List<ExitProfile> =
        listOf(
            ExitProfile(request.targetProfitPct, request.stopLossPct, request.maxHoldBars),
            ExitProfile(0.5, 0.3, 4),
            ExitProfile(0.8, 0.5, 8),
            ExitProfile(1.0, 0.5, 12),
            ExitProfile(1.2, 0.8, 12),
            ExitProfile(1.5, 0.8, 20),
            ExitProfile(2.0, 1.0, 30),
        ).distinctBy { "${it.targetProfitPct}:${it.stopLossPct}:${it.maxHoldBars}" }

    private data class CandidateVariant(
        val request: StrategyResearchRequest,
        val trades: List<StrategyResearchTrade>,
        val stats: StrategyResearchStats,
        val score: Double,
    )

    private data class ExitProfile(
        val targetProfitPct: Double,
        val stopLossPct: Double,
        val maxHoldBars: Int,
    )

    private data class CandidateSpec(
        val key: String,
        val title: String,
        val marketFit: String,
        val entryRules: List<String>,
        val entryDsl: String,
        val exitDslFactory: (StrategyResearchRequest) -> String,
        val exitRulesFactory: (StrategyResearchRequest) -> List<String> = { emptyList() },
        val riskRulesFactory: (StrategyResearchRequest) -> List<String> = { emptyList() },
        val entrySignal: (List<ResearchCandle>, Int) -> Boolean,
        val reasonFactory: (List<ResearchCandle>, Int) -> String,
    ) {
        fun exitDsl(request: StrategyResearchRequest): String = exitDslFactory(request)

        fun exitRules(request: StrategyResearchRequest): List<String> = exitRulesFactory(request)

        fun riskRules(request: StrategyResearchRequest): List<String> = riskRulesFactory(request)

        fun tradeReason(
            candles: List<ResearchCandle>,
            index: Int,
        ): String = reasonFactory(candles, index)
    }

    private companion object {
        const val MIN_CANDLES = 40
    }
}

data class StrategyResearchRequest(
    val userId: String,
    val code: String,
    val interval: String,
    val from: Instant,
    val to: Instant,
    val targetProfitPct: Double = 1.5,
    val stopLossPct: Double = 0.8,
    val maxHoldBars: Int = 12,
)

data class ResearchCandle(
    val code: String,
    val interval: String,
    val ts: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class StrategyResearchReport(
    val request: StrategyResearchRequest,
    val candleCount: Int,
    val marketRegime: StrategyResearchMarketRegime,
    val warnings: List<String>,
    val candles: List<StrategyResearchCandlePoint>,
    val candidates: List<StrategyResearchCandidate>,
)

data class StrategyResearchMarketRegime(
    val type: String,
    val trendPct: Double,
    val volatilityPct: Double,
    val explanation: String,
)

data class StrategyResearchCandlePoint(
    val ts: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class StrategyResearchCandidate(
    val key: String,
    val title: String,
    val summary: String,
    val marketFit: String,
    val entryRules: List<String>,
    val exitRules: List<String>,
    val riskRules: List<String>,
    val entryDslDraft: String,
    val exitDslDraft: String,
    val stats: StrategyResearchStats,
    val trades: List<StrategyResearchTrade>,
    val score: Double,
)

data class StrategyResearchStats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val totalReturnPct: Double,
    val avgTradeReturnPct: Double,
    val bestTradePct: Double,
    val worstTradePct: Double,
    val maxDrawdownPct: Double,
    val maxConsecutiveLosses: Int,
    val profitFactor: Double?,
)

data class StrategyResearchTrade(
    val signalTime: Instant,
    val entryTime: Instant,
    val exitTime: Instant,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val returnPct: Double,
    val exitReason: StrategyResearchExitReason,
    val barsHeld: Int,
    val reason: String,
)

enum class StrategyResearchExitReason {
    TAKE_PROFIT,
    STOP_LOSS,
    TIME_EXIT,
}

private fun ma(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .sumOf { it.close.toDouble() }
        .div(period.toDouble())
}

private fun rsi(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period) return null
    var gains = 0.0
    var losses = 0.0
    for (cursor in (index - period + 1)..index) {
        val diff = candles[cursor].close.toDouble() - candles[cursor - 1].close.toDouble()
        if (diff >= 0.0) {
            gains += diff
        } else {
            losses += abs(diff)
        }
    }
    val averageGain = gains / period.toDouble()
    val averageLoss = losses / period.toDouble()
    if (averageLoss == 0.0) return 100.0
    val rs = averageGain / averageLoss
    return 100.0 - (100.0 / (1.0 + rs))
}

private fun avgVolume(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .sumOf { it.volume.toDouble() }
        .div(period.toDouble())
}

private fun candleReturnPct(candle: ResearchCandle): Double {
    val open = candle.open.toDouble()
    if (open == 0.0) return 0.0
    return (candle.close.toDouble() - open) / open * 100.0
}

private fun bd(value: Double): BigDecimal = BigDecimal.valueOf(round(value))

private fun round(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

private fun price(value: Double?): String = value?.let { BigDecimal.valueOf(round(it)).toPlainString() } ?: "-"
