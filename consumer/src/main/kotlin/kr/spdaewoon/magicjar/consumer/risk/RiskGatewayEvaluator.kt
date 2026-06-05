package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.risk.RiskGatewayResult
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Risk Gateway 평가 본체 (ADR 0067 §5-5 + §7-2).
 *
 * ### 평가 순서 (short-circuit — 첫 BLOCKED 발견 시 즉시 반환)
 *  1. [RiskRule.KILL_SWITCH_ON]
 *  2. [RiskRule.DAILY_LOSS_LIMIT]
 *  3. [RiskRule.MAX_CONCURRENT_POSITIONS]   (BUY만)
 *  4. [RiskRule.TRADE_FREQUENCY_MINUTE]
 *  5. [RiskRule.TRADE_FREQUENCY_DAY]
 *  6. [RiskRule.POSITION_LIMIT]            (BUY만)
 *
 * ### Pure logic (deterministic)
 * 외부 I/O 0건 — RiskContext에 모든 입력 사전 빌드 후 호출.
 *
 * spec 인용: ADR 0067 §5-5 + §7-2.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class RiskGatewayEvaluator {
    private val log = LoggerFactory.getLogger(RiskGatewayEvaluator::class.java)

    fun evaluate(
        signal: Signal,
        context: RiskContext,
    ): RiskGatewayResult {
        val signalId = signal.id ?: error("Signal.id must be non-null for risk evaluation")

        // 1. KILL_SWITCH_ON — ADR 0043 정합 (최우선)
        if (context.killSwitchOn) {
            return blocked(
                signalId,
                RiskRule.KILL_SWITCH_ON,
                reason = "KillSwitch enabled for user=${context.userId}",
                context = mapOf("userId" to context.userId),
            )
        }

        if (signal.priceAtSignal <= BigDecimal.ZERO) {
            return blocked(
                signalId,
                RiskRule.INVALID_SIGNAL_PRICE,
                reason = "Signal price must be positive: ${signal.priceAtSignal.toPlainString()}",
                context =
                    mapOf(
                        "priceAtSignal" to signal.priceAtSignal.toPlainString(),
                        "side" to signal.side.name,
                        "code" to signal.code,
                    ),
            )
        }

        // 2. DAILY_LOSS_LIMIT — B-1 amount OR percent xor
        if (context.config.isDailyLossLimitActive) {
            val pnl = context.dailyRealizedPnl
            val amountLimit = context.config.dailyLossLimitAmount
            val percentLimit = context.config.dailyLossLimitPercent

            if (amountLimit != null) {
                if (pnl < BigDecimal.ZERO && pnl.abs() >= amountLimit) {
                    return blocked(
                        signalId,
                        RiskRule.DAILY_LOSS_LIMIT,
                        reason = "Daily loss ${pnl.toPlainString()} reached amount limit -${amountLimit.toPlainString()}",
                        context =
                            mapOf(
                                "dailyRealizedPnl" to pnl.toPlainString(),
                                "limit" to amountLimit.toPlainString(),
                                "limitType" to "amount",
                            ),
                    )
                }
            } else if (percentLimit != null) {
                if (context.currentCapital > BigDecimal.ZERO && pnl < BigDecimal.ZERO) {
                    val pnlPctAbs =
                        pnl
                            .abs()
                            .divide(context.currentCapital, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal("100"))
                    if (pnlPctAbs >= percentLimit) {
                        val pnlPctScaled = pnlPctAbs.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        return blocked(
                            signalId,
                            RiskRule.DAILY_LOSS_LIMIT,
                            reason = "Daily loss $pnlPctScaled% reached percent limit -${percentLimit.toPlainString()}%",
                            context =
                                mapOf(
                                    "dailyRealizedPnl" to pnl.toPlainString(),
                                    "currentCapital" to context.currentCapital.toPlainString(),
                                    "pnlPercentAbs" to pnlPctAbs.toPlainString(),
                                    "limit" to percentLimit.toPlainString(),
                                    "limitType" to "percent",
                                ),
                        )
                    }
                }
            }
        }

        // 3. MAX_CONCURRENT_POSITIONS — BUY만 (SELL은 청산 → 통과)
        if (signal.side == SignalSide.BUY) {
            val currentCount = context.currentPositions.size
            if (currentCount >= context.config.maxConcurrentPositions) {
                return blocked(
                    signalId,
                    RiskRule.MAX_CONCURRENT_POSITIONS,
                    reason = "Concurrent positions $currentCount >= limit ${context.config.maxConcurrentPositions}",
                    context =
                        mapOf(
                            "currentCount" to currentCount,
                            "limit" to context.config.maxConcurrentPositions,
                            "codes" to context.currentPositions.map { it.code },
                        ),
                )
            }
        }

        if (signal.side == SignalSide.SELL) {
            val holdingQty = holdingQuantity(signal, context)
            if (holdingQty <= 0) {
                return blocked(
                    signalId,
                    RiskRule.SELL_WITHOUT_POSITION,
                    reason = "SELL signal has no current holding for code=${signal.code}",
                    context =
                        mapOf(
                            "code" to signal.code,
                            "currentCodes" to context.currentPositions.map { it.code },
                        ),
                )
            }
            val reservedSellQty = reservedSellQuantity(signal, context)
            val availableSellQty = (holdingQty - reservedSellQty).coerceAtLeast(0)
            if (availableSellQty <= 0) {
                return blocked(
                    signalId,
                    RiskRule.SELL_QUANTITY_UNAVAILABLE,
                    reason =
                        "SELL signal has no sellable quantity for code=${signal.code} " +
                            "(holding=$holdingQty, reservedSell=$reservedSellQty)",
                    context =
                        mapOf(
                            "code" to signal.code,
                            "holdingQty" to holdingQty,
                            "reservedSellQty" to reservedSellQty,
                            "availableSellQty" to availableSellQty,
                            "openOrderIds" to
                                context.openOrders
                                    .filter { it.code == signal.code && it.side == OrderSide.SELL }
                                    .mapNotNull { it.id },
                        ),
                )
            }
        }

        // 4. TRADE_FREQUENCY_MINUTE — D-1
        if (context.recentTradesMinute >= context.config.maxTradesPerMinute) {
            return blocked(
                signalId,
                RiskRule.TRADE_FREQUENCY_MINUTE,
                reason = "Strategy minute trade count ${context.recentTradesMinute} >= limit ${context.config.maxTradesPerMinute}",
                context =
                    mapOf(
                        "strategyId" to signal.strategyId,
                        "recentTradesMinute" to context.recentTradesMinute,
                        "limit" to context.config.maxTradesPerMinute,
                    ),
            )
        }

        // 5. TRADE_FREQUENCY_DAY — D-1
        if (context.recentTradesDay >= context.config.maxTradesPerDay) {
            return blocked(
                signalId,
                RiskRule.TRADE_FREQUENCY_DAY,
                reason = "Strategy daily trade count ${context.recentTradesDay} >= limit ${context.config.maxTradesPerDay}",
                context =
                    mapOf(
                        "strategyId" to signal.strategyId,
                        "recentTradesDay" to context.recentTradesDay,
                        "limit" to context.config.maxTradesPerDay,
                    ),
            )
        }

        // 6. POSITION_LIMIT — A-1 min(absolute, capital × percent/100), BUY만 의미
        if (signal.side == SignalSide.BUY) {
            val positionBudget = resolvePositionBudget(context)
            val existingExposure = currentCodeExposure(signal, context)
            val remainingBudget = (positionBudget.budget - existingExposure).coerceAtLeast(BigDecimal.ZERO)

            // Phase 1 quantity 결정은 T-O 트랙 — 시그널 단위 가격(priceAtSignal) > 남은 종목 예산 시 즉시 BLOCKED.
            val unitCost = signal.priceAtSignal
            if (unitCost > remainingBudget) {
                val reasonText =
                    "Unit price ${unitCost.toPlainString()} exceeds remaining position budget " +
                        "${remainingBudget.toPlainString()} " +
                        "(budget=${positionBudget.budget.toPlainString()}, existing=${existingExposure.toPlainString()}, " +
                        "min(abs=${positionBudget.absoluteLimit.toPlainString()}, pct=${positionBudget.percentBudget.toPlainString()}))"
                return blocked(
                    signalId,
                    RiskRule.POSITION_LIMIT,
                    reason = reasonText,
                    context =
                        mapOf(
                            "unitCost" to unitCost.toPlainString(),
                            "budget" to positionBudget.budget.toPlainString(),
                            "remainingBudget" to remainingBudget.toPlainString(),
                            "existingExposure" to existingExposure.toPlainString(),
                            "absLimit" to positionBudget.absoluteLimit.toPlainString(),
                            "percentBudget" to positionBudget.percentBudget.toPlainString(),
                            "currentCapital" to context.currentCapital.toPlainString(),
                            "maxPositionPercent" to context.config.maxPositionPercent.toPlainString(),
                        ),
                )
            }
        }

        log.debug(
            "RiskGatewayEvaluator ALLOWED: signalId={} userId={} strategyId={} side={} code={}",
            signalId,
            context.userId,
            signal.strategyId,
            signal.side,
            signal.code,
        )
        return RiskGatewayResult.Allowed(signalId)
    }

    fun resolveBuyQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int {
        val positionBudget = resolvePositionBudget(context)
        val existingExposure = currentCodeExposure(signal, context)
        val remainingBudget = (positionBudget.budget - existingExposure).coerceAtLeast(BigDecimal.ZERO)
        return remainingBudget
            .divide(signal.priceAtSignal, 0, RoundingMode.DOWN)
            .toInt()
    }

    fun resolveSellQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int = (holdingQuantity(signal, context) - reservedSellQuantity(signal, context)).coerceAtLeast(0)

    private fun resolvePositionBudget(context: RiskContext): PositionBudget {
        val absoluteLimit = context.config.maxPositionAmount
        val percentBudget =
            if (context.currentCapital > BigDecimal.ZERO) {
                context.currentCapital
                    .multiply(context.config.maxPositionPercent)
                    .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
        val budget = if (percentBudget < absoluteLimit) percentBudget else absoluteLimit
        return PositionBudget(
            absoluteLimit = absoluteLimit,
            percentBudget = percentBudget,
            budget = budget,
        )
    }

    private fun currentCodeExposure(
        signal: Signal,
        context: RiskContext,
    ): BigDecimal =
        context.currentPositions
            .filter { it.code == signal.code && it.quantity > 0 }
            .fold(BigDecimal.ZERO) { acc, position ->
                acc + signal.priceAtSignal.multiply(BigDecimal(position.quantity))
            }

    /**
     * SELL 가용 수량 평가용 보유 수량 (ADR 0110 §2-4 SELL 전략별 분기).
     *
     * - `context.strategyPosition != null`(분할매수 전략 lot 존재) → **그 전략 보유 수량**(다전략 교차 청산 방지).
     * - null(분할매수 미사용 전략·과거 보유) → 기존 user+code 합산 보유(하위호환 fallback — zero-regression).
     */
    private fun holdingQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int =
        context.strategyPosition
            ?.takeIf { it.code == signal.code && it.strategyId == signal.strategyId }
            ?.quantity
            ?: context.currentPositions
                .firstOrNull { it.code == signal.code && it.quantity > 0 }
                ?.quantity
            ?: 0

    /**
     * 미체결 SELL 예약 수량 (ADR 0110 §2-4).
     *
     * - 전략 lot 기준 평가 시(`strategyPosition != null`) → **그 전략의** open SELL 만 합산(교차 차감 방지).
     * - null fallback → 기존 user+code 전체 open SELL 합산(하위호환).
     */
    private fun reservedSellQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int {
        val byStrategy = context.strategyPosition?.let { it.code == signal.code && it.strategyId == signal.strategyId } == true
        return context.openOrders
            .filter { it.code == signal.code && it.side == OrderSide.SELL }
            .filter { !byStrategy || it.strategyId == signal.strategyId }
            .sumOf { it.quantity }
    }

    private data class PositionBudget(
        val absoluteLimit: BigDecimal,
        val percentBudget: BigDecimal,
        val budget: BigDecimal,
    )

    private fun blocked(
        signalId: Long,
        rule: RiskRule,
        reason: String,
        context: Map<String, Any?>,
    ): RiskGatewayResult.Blocked {
        log.info("RiskGatewayEvaluator BLOCKED: signalId={} rule={} reason={}", signalId, rule, reason)
        return RiskGatewayResult.Blocked(signalId, rule, reason, context)
    }
}
