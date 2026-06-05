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
            CandidateSpec(
                key = "MA20_PULLBACK_CONTINUATION",
                title = "MA20 눌림목 추세 지속",
                marketFit = "중기 상승 흐름에서 MA20 근처 눌림 후 재상승하는 구간을 노립니다.",
                entryRules =
                    listOf(
                        "MA20이 MA50 위에 있음",
                        "현재 저가가 MA20 근처까지 눌림",
                        "현재 종가가 MA20 위로 회복",
                        "현재 candle이 양봉이고 거래량이 평균의 70% 이상",
                    ),
                entryDsl = "ma(20) > ma(50) and close > ma(20) and close > open",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val ma20 = ma(candles, 20, index)
                    val ma50 = ma(candles, 50, index)
                    val avgVolume = avgVolume(candles, 20, index)
                    ma20 != null &&
                        ma50 != null &&
                        avgVolume != null &&
                        ma20 > ma50 &&
                        candles[index].low.toDouble() <= ma20 * 1.01 &&
                        candles[index].close.toDouble() >= ma20 &&
                        candles[index].close > candles[index].open &&
                        candles[index].volume.toDouble() >= avgVolume * 0.7
                },
                reasonFactory = { candles, index ->
                    "MA20 ${price(ma(candles, 20, index))} 눌림 후 양봉 회복"
                },
            ),
            CandidateSpec(
                key = "MA5_20_GOLDEN_CROSS",
                title = "MA5·MA20 골든크로스",
                marketFit = "짧은 평균선이 중기 평균선을 상향 돌파하는 초기 반전 구간을 평가합니다.",
                entryRules =
                    listOf(
                        "직전 MA5가 MA20 이하",
                        "현재 MA5가 MA20 위로 돌파",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 80% 이상",
                    ),
                entryDsl = "ma(5) crosses_above ma(20) and close > open",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousMa5 = ma(candles, 5, index - 1)
                    val previousMa20 = ma(candles, 20, index - 1)
                    val currentMa5 = ma(candles, 5, index)
                    val currentMa20 = ma(candles, 20, index)
                    val avgVolume = avgVolume(candles, 20, index)
                    previousMa5 != null &&
                        previousMa20 != null &&
                        currentMa5 != null &&
                        currentMa20 != null &&
                        avgVolume != null &&
                        previousMa5 <= previousMa20 &&
                        currentMa5 > currentMa20 &&
                        candles[index].close > candles[index].open &&
                        candles[index].volume.toDouble() >= avgVolume * 0.8
                },
                reasonFactory = { candles, index ->
                    "MA5 ${price(ma(candles, 5, index))}가 MA20 ${price(ma(candles, 20, index))} 돌파"
                },
            ),
            CandidateSpec(
                key = "BREAKOUT_20_HIGH_VOLUME",
                title = "20봉 고점 거래량 돌파",
                marketFit = "최근 박스권 상단을 거래량과 함께 돌파하는 모멘텀 구간을 봅니다.",
                entryRules =
                    listOf(
                        "현재 종가가 직전 20개 candle 고점 위",
                        "거래량이 최근 20개 평균의 1.5배 이상",
                        "현재 candle이 양봉",
                        "종가가 candle 상단부에 위치",
                    ),
                entryDsl = "close > donchian_upper(20) and volume > volume_ma(20) * 1.5",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousHigh = rollingHigh(candles, 20, index - 1)
                    val avgVolume = avgVolume(candles, 20, index)
                    previousHigh != null &&
                        avgVolume != null &&
                        candles[index].close.toDouble() > previousHigh &&
                        candles[index].volume.toDouble() >= avgVolume * 1.5 &&
                        candles[index].close > candles[index].open &&
                        closePosition(candles[index]) >= 0.65
                },
                reasonFactory = { candles, index ->
                    "직전 20봉 고점 ${price(rollingHigh(candles, 20, index - 1))} 거래량 돌파"
                },
            ),
            CandidateSpec(
                key = "RSI40_TREND_PULLBACK",
                title = "상승 추세 RSI40 눌림 회복",
                marketFit = "MA50 위 상승 흐름에서 RSI40을 되찾는 눌림 회복 구간을 노립니다.",
                entryRules =
                    listOf(
                        "현재 종가가 MA50 위",
                        "직전 RSI14가 40 아래",
                        "현재 RSI14가 40 위로 회복",
                        "현재 candle이 양봉",
                    ),
                entryDsl = "close > ma(50) and rsi(14) crosses_above 40",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val ma50 = ma(candles, 50, index)
                    val previousRsi = rsi(candles, 14, index - 1)
                    val currentRsi = rsi(candles, 14, index)
                    ma50 != null &&
                        previousRsi != null &&
                        currentRsi != null &&
                        candles[index].close.toDouble() > ma50 &&
                        previousRsi < 40.0 &&
                        currentRsi >= 40.0 &&
                        candles[index].close > candles[index].open
                },
                reasonFactory = { candles, index ->
                    "MA50 위에서 RSI14 ${round(rsi(candles, 14, index) ?: 0.0)}로 40선 회복"
                },
            ),
            CandidateSpec(
                key = "RSI50_MOMENTUM_RECLAIM",
                title = "RSI50 모멘텀 회복",
                marketFit = "중립선을 되찾으며 단기 모멘텀이 살아나는 종목을 평가합니다.",
                entryRules =
                    listOf(
                        "현재 종가가 MA20 위",
                        "직전 RSI14가 50 아래",
                        "현재 RSI14가 50 위로 회복",
                        "거래량이 최근 20개 평균의 80% 이상",
                    ),
                entryDsl = "close > ma(20) and rsi(14) crosses_above 50",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val ma20 = ma(candles, 20, index)
                    val previousRsi = rsi(candles, 14, index - 1)
                    val currentRsi = rsi(candles, 14, index)
                    val avgVolume = avgVolume(candles, 20, index)
                    ma20 != null &&
                        previousRsi != null &&
                        currentRsi != null &&
                        avgVolume != null &&
                        candles[index].close.toDouble() > ma20 &&
                        previousRsi < 50.0 &&
                        currentRsi >= 50.0 &&
                        candles[index].volume.toDouble() >= avgVolume * 0.8
                },
                reasonFactory = { candles, index ->
                    "RSI14 ${round(rsi(candles, 14, index) ?: 0.0)}로 50선 회복"
                },
            ),
            CandidateSpec(
                key = "RANGE_SUPPORT_BOUNCE",
                title = "박스권 하단 지지 반등",
                marketFit = "최근 저점대 근처에서 저가 방어 후 반등하는 평균회귀 구간을 봅니다.",
                entryRules =
                    listOf(
                        "현재 저가가 직전 20개 candle 저점 근처",
                        "현재 candle이 양봉",
                        "현재 종가가 직전 종가보다 높음",
                        "RSI14가 35 이상 60 이하",
                    ),
                entryDsl = "low < donchian_lower(20) * 1.01 and close > open",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousLow = rollingLow(candles, 20, index - 1)
                    val currentRsi = rsi(candles, 14, index)
                    previousLow != null &&
                        currentRsi != null &&
                        candles[index].low.toDouble() <= previousLow * 1.01 &&
                        candles[index].close > candles[index].open &&
                        candles[index].close > candles[index - 1].close &&
                        currentRsi in 35.0..60.0
                },
                reasonFactory = { candles, index ->
                    "20봉 저점 ${price(rollingLow(candles, 20, index - 1))} 근처 지지"
                },
            ),
            CandidateSpec(
                key = "BB_LOWER_RECLAIM",
                title = "볼린저 하단 회복 반등",
                marketFit = "볼린저 하단 이탈 후 다시 밴드 안으로 회복하는 과매도 반등을 봅니다.",
                entryRules =
                    listOf(
                        "직전 종가가 볼린저 하단 아래",
                        "현재 종가가 볼린저 하단 위로 회복",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 70% 이상",
                    ),
                entryDsl = "close crosses_above bb_lower(20, 2) and close > open",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousLower = bollingerLower(candles, 20, index - 1)
                    val currentLower = bollingerLower(candles, 20, index)
                    val avgVolume = avgVolume(candles, 20, index)
                    previousLower != null &&
                        currentLower != null &&
                        avgVolume != null &&
                        candles[index - 1].close.toDouble() < previousLower &&
                        candles[index].close.toDouble() >= currentLower &&
                        candles[index].close > candles[index].open &&
                        candles[index].volume.toDouble() >= avgVolume * 0.7
                },
                reasonFactory = { candles, index ->
                    "볼린저 하단 ${price(bollingerLower(candles, 20, index))} 회복"
                },
            ),
            CandidateSpec(
                key = "DONCHIAN10_BREAKOUT",
                title = "10봉 단기 돌파",
                marketFit = "짧은 박스권을 빠르게 벗어나는 단기 추세 시동을 평가합니다.",
                entryRules =
                    listOf(
                        "현재 종가가 직전 10개 candle 고점 위",
                        "거래량이 최근 20개 평균의 1.2배 이상",
                        "현재 candle이 양봉",
                        "종가가 candle 상단부에 위치",
                    ),
                entryDsl = "close > donchian_upper(10) and volume > volume_ma(20) * 1.2",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousHigh = rollingHigh(candles, 10, index - 1)
                    val avgVolume = avgVolume(candles, 20, index)
                    previousHigh != null &&
                        avgVolume != null &&
                        candles[index].close.toDouble() > previousHigh &&
                        candles[index].volume.toDouble() >= avgVolume * 1.2 &&
                        candles[index].close > candles[index].open &&
                        closePosition(candles[index]) >= 0.6
                },
                reasonFactory = { candles, index ->
                    "직전 10봉 고점 ${price(rollingHigh(candles, 10, index - 1))} 돌파"
                },
            ),
            CandidateSpec(
                key = "VOLUME_SURGE_GREEN",
                title = "거래량 급증 장대양봉",
                marketFit = "거래량이 평소보다 크게 터지며 강한 양봉이 나온 구간을 추적합니다.",
                entryRules =
                    listOf(
                        "거래량이 최근 20개 평균의 2배 이상",
                        "현재 candle 수익률이 0.8% 이상",
                        "현재 candle이 양봉",
                        "종가가 candle 상단부에 위치",
                    ),
                entryDsl = "close > open and volume > volume_ma(20) * 2",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val avgVolume = avgVolume(candles, 20, index)
                    avgVolume != null &&
                        candles[index].volume.toDouble() >= avgVolume * 2.0 &&
                        candleReturnPct(candles[index]) >= 0.8 &&
                        candles[index].close > candles[index].open &&
                        closePosition(candles[index]) >= 0.65
                },
                reasonFactory = { candles, index ->
                    "거래량 ${round(candles[index].volume / (avgVolume(candles, 20, index) ?: 1.0))}배 장대양봉"
                },
            ),
            CandidateSpec(
                key = "LOW_VOLUME_PULLBACK_BREAK",
                title = "저거래 눌림 후 전봉 돌파",
                marketFit = "거래가 말라붙은 눌림 이후 직전 고가를 회복하는 조용한 재시동을 봅니다.",
                entryRules =
                    listOf(
                        "직전 거래량이 최근 20개 평균의 70% 이하",
                        "현재 종가가 직전 고가 위",
                        "현재 종가가 MA20 위",
                        "현재 거래량이 평균의 1.1배 이상",
                    ),
                entryDsl = "close > ma(20) and volume > volume_ma(20) * 1.1",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousAvgVolume = avgVolume(candles, 20, index - 1)
                    val currentAvgVolume = avgVolume(candles, 20, index)
                    val ma20 = ma(candles, 20, index)
                    previousAvgVolume != null &&
                        currentAvgVolume != null &&
                        ma20 != null &&
                        candles[index - 1].volume.toDouble() <= previousAvgVolume * 0.7 &&
                        candles[index].close > candles[index - 1].high &&
                        candles[index].close.toDouble() > ma20 &&
                        candles[index].volume.toDouble() >= currentAvgVolume * 1.1
                },
                reasonFactory = { candles, index ->
                    "저거래 눌림 후 직전 고가 ${candles[index - 1].high.toPlainString()} 회복"
                },
            ),
            CandidateSpec(
                key = "THREE_BAR_RECOVERY",
                title = "3연속 종가 회복",
                marketFit = "작은 반등이 3개 candle 연속 이어질 때 추세 회복 가능성을 봅니다.",
                entryRules =
                    listOf(
                        "최근 3개 candle 종가가 연속 상승",
                        "현재 종가가 MA10 위",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 70% 이상",
                    ),
                entryDsl = "close > ma(10) and close > open",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val ma10 = ma(candles, 10, index)
                    val avgVolume = avgVolume(candles, 20, index)
                    index >= 2 &&
                        ma10 != null &&
                        avgVolume != null &&
                        candles[index - 2].close < candles[index - 1].close &&
                        candles[index - 1].close < candles[index].close &&
                        candles[index].close.toDouble() > ma10 &&
                        candles[index].close > candles[index].open &&
                        candles[index].volume.toDouble() >= avgVolume * 0.7
                },
                reasonFactory = { _, _ ->
                    "3개 candle 연속 종가 상승"
                },
            ),
            CandidateSpec(
                key = "HAMMER_VOLUME_REVERSAL",
                title = "거래량 동반 망치형 반전",
                marketFit = "아래꼬리로 매도 압력을 흡수한 뒤 양봉 마감하는 반전 후보입니다.",
                entryRules =
                    listOf(
                        "아래꼬리가 몸통의 2배 이상",
                        "윗꼬리가 전체 range의 35% 이하",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 1.2배 이상",
                    ),
                entryDsl = "close > open and volume > volume_ma(20) * 1.2",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val avgVolume = avgVolume(candles, 20, index)
                    val body = candleBody(candles[index])
                    val range = candleRange(candles[index])
                    avgVolume != null &&
                        range > 0.0 &&
                        lowerWick(candles[index]) >= max(body, range * 0.05) * 2.0 &&
                        upperWick(candles[index]) <= range * 0.35 &&
                        candles[index].close > candles[index].open &&
                        candles[index].volume.toDouble() >= avgVolume * 1.2
                },
                reasonFactory = { _, _ ->
                    "아래꼬리 긴 양봉 반전"
                },
            ),
            CandidateSpec(
                key = "GAP_DOWN_REVERSAL",
                title = "갭하락 회복 반전",
                marketFit = "시초 또는 구간 갭하락을 빠르게 메우는 강한 되돌림을 추적합니다.",
                entryRules =
                    listOf(
                        "현재 시가가 직전 종가보다 2% 이상 낮음",
                        "현재 종가가 직전 종가 근처까지 회복",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균 이상",
                    ),
                entryDsl = "close > open and rsi(14) < 45",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val avgVolume = avgVolume(candles, 20, index)
                    val previousClose = candles[index - 1].close.toDouble()
                    previousClose > 0.0 &&
                        avgVolume != null &&
                        candles[index].open.toDouble() <= previousClose * 0.98 &&
                        candles[index].close.toDouble() >= previousClose * 0.995 &&
                        candles[index].close > candles[index].open &&
                        candles[index].volume.toDouble() >= avgVolume
                },
                reasonFactory = { candles, index ->
                    "갭하락 후 직전 종가 ${candles[index - 1].close.toPlainString()} 근처 회복"
                },
            ),
            CandidateSpec(
                key = "VOLATILITY_SQUEEZE_BREAKOUT",
                title = "변동성 수축 후 돌파",
                marketFit = "거래 범위가 작아진 뒤 단기 고점을 돌파하는 에너지 방출 구간을 봅니다.",
                entryRules =
                    listOf(
                        "최근 10개 평균 range가 최근 50개 평균 range의 75% 이하",
                        "현재 종가가 직전 10개 candle 고점 위",
                        "거래량이 최근 20개 평균 이상",
                        "현재 candle이 양봉",
                    ),
                entryDsl = "close > donchian_upper(10) and volume > volume_ma(20)",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val shortRange = avgRangePct(candles, 10, index - 1)
                    val longRange = avgRangePct(candles, 50, index - 1)
                    val previousHigh = rollingHigh(candles, 10, index - 1)
                    val avgVolume = avgVolume(candles, 20, index)
                    shortRange != null &&
                        longRange != null &&
                        previousHigh != null &&
                        avgVolume != null &&
                        shortRange <= longRange * 0.75 &&
                        candles[index].close.toDouble() > previousHigh &&
                        candles[index].volume.toDouble() >= avgVolume &&
                        candles[index].close > candles[index].open
                },
                reasonFactory = { candles, index ->
                    "변동성 수축 후 10봉 고점 ${price(rollingHigh(candles, 10, index - 1))} 돌파"
                },
            ),
            CandidateSpec(
                key = "MA50_TREND_DIP_RECLAIM",
                title = "MA50 상승 추세 MA20 재회복",
                marketFit = "큰 추세는 유지된 상태에서 MA20 이탈 후 빠르게 되찾는 눌림목을 평가합니다.",
                entryRules =
                    listOf(
                        "MA20이 MA50 위",
                        "직전 종가가 MA20 아래",
                        "현재 종가가 MA20 위로 회복",
                        "RSI14가 45 이상 65 이하",
                    ),
                entryDsl = "ma(20) > ma(50) and close crosses_above ma(20)",
                exitDslFactory = {
                    "take_profit(${it.targetProfitPct}%) or stop_loss(${it.stopLossPct}%) or ${it.maxHoldBars} bars timeout"
                },
                entrySignal = { candles, index ->
                    val previousMa20 = ma(candles, 20, index - 1)
                    val currentMa20 = ma(candles, 20, index)
                    val currentMa50 = ma(candles, 50, index)
                    val currentRsi = rsi(candles, 14, index)
                    previousMa20 != null &&
                        currentMa20 != null &&
                        currentMa50 != null &&
                        currentRsi != null &&
                        currentMa20 > currentMa50 &&
                        candles[index - 1].close.toDouble() < previousMa20 &&
                        candles[index].close.toDouble() >= currentMa20 &&
                        candles[index].close > candles[index].open &&
                        currentRsi in 45.0..65.0
                },
                reasonFactory = { candles, index ->
                    "MA50 추세 위에서 MA20 ${price(ma(candles, 20, index))} 재회복"
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

private fun rollingHigh(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .maxOf { it.high.toDouble() }
}

private fun rollingLow(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .minOf { it.low.toDouble() }
}

private fun stdDevClose(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    val average = ma(candles, period, index) ?: return null
    val variance =
        candles
            .subList(index - period + 1, index + 1)
            .sumOf {
                val diff = it.close.toDouble() - average
                diff * diff
            }.div(period.toDouble())
    return kotlin.math.sqrt(variance)
}

private fun bollingerLower(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
    multiplier: Double = 2.0,
): Double? {
    val middle = ma(candles, period, index) ?: return null
    val dev = stdDevClose(candles, period, index) ?: return null
    return middle - dev * multiplier
}

private fun candleRange(candle: ResearchCandle): Double = candle.high.toDouble() - candle.low.toDouble()

private fun candleBody(candle: ResearchCandle): Double = abs(candle.close.toDouble() - candle.open.toDouble())

private fun lowerWick(candle: ResearchCandle): Double = min(candle.open.toDouble(), candle.close.toDouble()) - candle.low.toDouble()

private fun upperWick(candle: ResearchCandle): Double = candle.high.toDouble() - max(candle.open.toDouble(), candle.close.toDouble())

private fun closePosition(candle: ResearchCandle): Double {
    val range = candleRange(candle)
    if (range <= 0.0) return 0.5
    return (candle.close.toDouble() - candle.low.toDouble()) / range
}

private fun avgRangePct(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .mapNotNull { candle ->
            val close = candle.close.toDouble()
            if (close <= 0.0) null else candleRange(candle) / close * 100.0
        }.takeIf { it.isNotEmpty() }
        ?.average()
}

private fun bd(value: Double): BigDecimal = BigDecimal.valueOf(round(value))

private fun round(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

private fun price(value: Double?): String = value?.let { BigDecimal.valueOf(round(it)).toPlainString() } ?: "-"
