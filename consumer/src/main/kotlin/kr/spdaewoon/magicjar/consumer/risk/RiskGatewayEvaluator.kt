package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.consumer.order.AutoExitReservations.isCancellableAutoExit
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.risk.RiskBudgetCalculator
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.risk.RiskGatewayResult
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
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
 *  4. [RiskRule.TRADE_FREQUENCY_MINUTE]     (신규 포지션 오픈 BUY만 — 아래 "거래빈도 = 왕복 개시" 참조)
 *  5. [RiskRule.TRADE_FREQUENCY_DAY]        (신규 포지션 오픈 BUY만)
 *  6. [RiskRule.POSITION_LIMIT]            (BUY만)
 *
 * ### 거래빈도 = 왕복(신규 포지션 오픈) 개시당 1 (사용자 결재 2026-06-16)
 * `maxTradesPerMinute`/`maxTradesPerDay` 빈도 룰은 **신규 진입(flat→보유 전환) BUY 시그널에만** 적용한다
 * ([isNewPositionOpen]). 청산(모든 SELL)·스케일인 추가매수(이미 보유 중 BUY)는 빈도 룰로 차단되지 않는다 —
 * 특히 청산은 자본 보호상 절대 throttle 금지. [SignalGeneratedKafkaListener]의 record(누적)도 동일 정의를
 * 써서 check 와 record 가 같은 의미("왕복 개시")로 정합한다(record≠check 갭 차단).
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

    /**
     * @param plannedEntryEnabled 계획 기반 진입 게이트(`magicjar.autopilot.planned-entry.enabled`, default false).
     *   true + owner=AUTOPILOT 일 때만 L1 lot 소유권을 강제한다(SELL holding 의 user+code fallback 차단 — §ADR 0130 L1).
     *   false(또는 USER/SYSTEM)면 현행 fallback 그대로(zero-regression). caller([SignalGeneratedKafkaListener])가 자신의
     *   게이트 값을 전달한다. 기본값 false 라 기존 호출·테스트는 byte-동일(전달 안 하면 현행).
     */
    fun evaluate(
        signal: Signal,
        context: RiskContext,
        plannedEntryEnabled: Boolean = false,
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
            val holdingQty = holdingQuantity(signal, context, plannedEntryEnabled)
            if (holdingQty <= 0) {
                return blocked(
                    signalId,
                    RiskRule.SELL_WITHOUT_POSITION,
                    reason = "SELL signal has no current holding for code=${signal.code}",
                    context =
                        mapOf(
                            "code" to signal.code,
                            "currentCodes" to context.currentPositions.map { it.code },
                            // L1 lot 소유권 차단 가시화 — owner=AUTOPILOT 이 그 종목 lot 미보유로 fallback 차단됐는지 audit.
                            "ownershipEnforced" to lotOwnershipEnforced(signal, plannedEntryEnabled),
                            "strategyOwner" to signal.strategyOwner?.name,
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

        // 4·5. TRADE_FREQUENCY — D-1. **신규 포지션 오픈 BUY(왕복 개시)에만** 적용 (사용자 결재 2026-06-16).
        //   청산(SELL)·스케일인(이미 보유 중 BUY)은 빈도 룰로 차단하지 않는다(record 정의와 정합 — 갭 차단).
        if (isNewPositionOpen(signal, context)) {
            // 4. TRADE_FREQUENCY_MINUTE
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

            // 5. TRADE_FREQUENCY_DAY
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
        }

        // 6. POSITION_LIMIT — BUY 매수 한도=계좌 전체 공유 풀 기준 (사용자 결재 2026-06-16), BUY만 의미.
        //    매수가능 = min(총한도 − 전체노출(전 종목·전 전략 합), 예수금). 시그널 단위가격이 매수가능 초과 시 즉시 BLOCKED.
        if (signal.side == SignalSide.BUY) {
            val buyBudget = resolveBuyBudget(context)

            val unitCost = signal.priceAtSignal
            if (unitCost > buyBudget.buyable) {
                val reasonText =
                    "Unit price ${unitCost.toPlainString()} exceeds buyable amount " +
                        "${buyBudget.buyable.toPlainString()} " +
                        "(totalLimit=${buyBudget.totalLimit.toPlainString()}, totalExposure=${buyBudget.totalExposure.toPlainString()}, " +
                        "remainingLimit=${buyBudget.remainingLimit.toPlainString()}, orderableCash=${buyBudget.orderableCash.toPlainString()})"
                return blocked(
                    signalId,
                    RiskRule.POSITION_LIMIT,
                    reason = reasonText,
                    context =
                        mapOf(
                            "unitCost" to unitCost.toPlainString(),
                            "totalLimit" to buyBudget.totalLimit.toPlainString(),
                            "totalExposure" to buyBudget.totalExposure.toPlainString(),
                            "remainingLimit" to buyBudget.remainingLimit.toPlainString(),
                            "buyable" to buyBudget.buyable.toPlainString(),
                            "orderableCash" to buyBudget.orderableCash.toPlainString(),
                            "absLimit" to buyBudget.absoluteLimit.toPlainString(),
                            "percentLimit" to buyBudget.percentLimit.toPlainString(),
                            "currentCapital" to context.currentCapital.toPlainString(),
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

    /**
     * 공짜 룰 선차단 (canary D2 hotfix #5 — KIS rate limit 자가 압박 회피).
     *
     * 외부 KIS TR(체결 동기화·잔고 조회) 호출 **전에** I/O 0 인 룰만 먼저 평가해, 어차피 차단될 시그널이
     * 원장 TR 을 소모하지 않게 한다(어제 EGW00201 자가 기여 차단). 판정 **의미는 [evaluate]와 동일**(같은 룰·같은
     * short-circuit 순서) — 호출 순서만 앞당긴 것이다. 통과 시 caller 가 KIS 호출 후 [evaluate]로 전체 재평가한다.
     *
     * 평가 룰(공짜 입력만):
     *  1. [RiskRule.KILL_SWITCH_ON]            — killSwitch 포트(부재 시 fail-closed=ON 은 caller 책임)
     *  2. [RiskRule.MAX_CONCURRENT_POSITIONS]  — BUY 만, positions count
     *  3. [RiskRule.TRADE_FREQUENCY_MINUTE]    — **신규 포지션 오픈 BUY([isNewOpenBuy])만** (사용자 결재 2026-06-16)
     *  4. [RiskRule.TRADE_FREQUENCY_DAY]       — **신규 포지션 오픈 BUY([isNewOpenBuy])만**
     *
     * [isNewOpenBuy] 는 caller([SignalGeneratedKafkaListener])가 [evaluate]의 [isNewPositionOpen]과 동일 소스
     * (strategyPosition lot → user+code 합산 fallback)로 산출해 전달한다. record/check/preCheck 세 경로가 같은
     * "왕복 개시" 정의를 공유해 의미 갭을 없앤다. 청산(SELL)·스케일인은 빈도 룰 대상이 아니다(자본 보호 — 청산 throttle 금지).
     *
     * 자본·체결 의존 룰(DAILY_LOSS·POSITION_LIMIT·SELL 잔고 가드)은 KIS 입력이 필요하므로 본 선차단 대상이 아니며
     * [evaluate] 전체 평가에서만 판정한다(의미 보존).
     */
    fun preCheckFreeRules(
        signal: Signal,
        killSwitchOn: Boolean,
        config: kr.spdaewoon.magicjar.domain.risk.RiskConfig,
        currentPositionCount: Int,
        currentCodes: List<String>,
        recentTradesMinute: Int,
        recentTradesDay: Int,
        isNewOpenBuy: Boolean,
    ): RiskGatewayResult.Blocked? {
        val signalId = signal.id ?: error("Signal.id must be non-null for risk evaluation")

        if (killSwitchOn) {
            return blocked(
                signalId,
                RiskRule.KILL_SWITCH_ON,
                reason = "KillSwitch enabled for user=${signal.userId}",
                context = mapOf("userId" to signal.userId),
            )
        }

        if (signal.side == SignalSide.BUY && currentPositionCount >= config.maxConcurrentPositions) {
            return blocked(
                signalId,
                RiskRule.MAX_CONCURRENT_POSITIONS,
                reason = "Concurrent positions $currentPositionCount >= limit ${config.maxConcurrentPositions}",
                context =
                    mapOf(
                        "currentCount" to currentPositionCount,
                        "limit" to config.maxConcurrentPositions,
                        "codes" to currentCodes,
                    ),
            )
        }

        // 거래빈도 선차단 — 신규 포지션 오픈 BUY 에만(evaluate 와 동일 정의). 청산·스케일인은 통과시켜 evaluate 로 흘림.
        if (isNewOpenBuy) {
            if (recentTradesMinute >= config.maxTradesPerMinute) {
                return blocked(
                    signalId,
                    RiskRule.TRADE_FREQUENCY_MINUTE,
                    reason = "Strategy minute trade count $recentTradesMinute >= limit ${config.maxTradesPerMinute}",
                    context =
                        mapOf(
                            "strategyId" to signal.strategyId,
                            "recentTradesMinute" to recentTradesMinute,
                            "limit" to config.maxTradesPerMinute,
                        ),
                )
            }

            if (recentTradesDay >= config.maxTradesPerDay) {
                return blocked(
                    signalId,
                    RiskRule.TRADE_FREQUENCY_DAY,
                    reason = "Strategy daily trade count $recentTradesDay >= limit ${config.maxTradesPerDay}",
                    context =
                        mapOf(
                            "strategyId" to signal.strategyId,
                            "recentTradesDay" to recentTradesDay,
                            "limit" to config.maxTradesPerDay,
                        ),
                )
            }
        }

        return null
    }

    /**
     * 거래빈도 카운트 단위 = **신규 포지션 오픈(왕복 개시)** 인가 (사용자 결재 2026-06-16 · 단일 진실원).
     *
     * `true` ⇔ `signal.side == BUY` **AND** 진입 직전 그 전략이 그 종목을 0주 보유(flat→보유 전환)일 때만.
     * - 스케일인 추가매수(이미 보유 중 BUY) → false(미카운트).
     * - 모든 SELL(청산) → false(미카운트 — 청산은 빈도 throttle 절대 금지, 자본 보호).
     *
     * 진입 직전 보유 판정은 **SELL_WITHOUT_POSITION 가 쓰는 동일 소스**([holdingQuantity] — strategyPosition lot →
     * user+code 합산 fallback)를 그대로 재사용한다(새 KIS 호출 0). evaluate(check)·preCheck·listener(record)가
     * 모두 본 함수를 호출해 같은 "왕복 개시" 정의를 공유한다(record≠check 갭 차단).
     */
    fun isNewPositionOpen(
        signal: Signal,
        context: RiskContext,
    ): Boolean = signal.side == SignalSide.BUY && holdingQuantity(signal, context) <= 0

    /**
     * BUY 매수 수량 산정 — **분할매수(scale-in) 비중 반영** (P0 hotfix 2026-06-18).
     *
     * ## 결함 (이 hotfix가 닫는 것 — 라이브 canary 실증)
     * 분할매수 전략(scale_in_config 설정 + 토글 ON)이 첫 회차에 **전량 매수**했다. [RealtimeSignalGenerator]의
     * scale-in 경로([ScaleInExitDecision.EnterSlice])가 BUY 시그널의 `reason`에 `sliceWeightPct`만 실어 발행하고,
     * 실제 수량은 listener([SignalGeneratedKafkaListener.resolveOrderQuantity]) → 본 함수가 다시 `buyable/price`로
     * **전량 재계산**했다. 회차 비중(`sliceWeights[filledLots]`)이 어디에서도 곱해지지 않아 첫 회 40%가 100%로 부풀었다.
     * (ScaleInExitInput.sliceBudgetQuantity 는 generator·live 경로에서 0으로만 채워져 회차 수량 산정의 권위가 아니었다 —
     *  generator 주석 "회차 수량은 호출자가 budget으로 산정"의 그 호출자가 본 함수다.)
     *
     * ## ① base = 1차 진입 시점 가용액 **고정** (2026-06-19 사용자 결재, ADR 0128)
     * 회차마다 buyable 을 재계산하면, 1차 매수가 전체노출을 늘려 남은 한도가 줄고 → 2·3차 회차 예산이 의도보다
     * 작아진다(분할 비중 왜곡). 1차 시점 base 를 [StrategyPosition.scaleInBaseBudget] 에 박제하고(체결 listener 책임),
     * 2·3차는 그 **고정 base × 회차 비중**으로 산정한다. base 가 박제돼 있으면(slice ≥ 1) 그것을 쓰고, 1차(또는 박제
     * 부재)면 라이브 buyable 을 base 로 쓴다(그 값이 listener 가 박제할 후보). **외부 주입 추상화** — 미래 "지정 가격 +
     * 목표 금액" 매집은 base 를 그 목표 금액으로 채워 넣으면 회차 산식 변경 0으로 호환된다.
     *
     * ## 정합 — 백테스트와 동일 산식(단일 진실원)
     * `회차예산 = floor(고정 base × weight%/100)` → `수량 = floor(회차예산 / price)`. weight 는 0% 회차 skip 정합
     * ([ScaleInPlan.sliceAt] — ⑨)으로 산출한다.
     *
     * - `scaleIn == null`(분할 미설정 전략) → 현행 전량 경로 그대로(zero-regression — byte 동일).
     * - `scaleIn != null`:
     *   - **⑧ totalSlices 초과 진입 0** — `filledLots`가 실효 회차 수 이상이면 [ScaleInPlan.sliceAt]가 null → **수량 0**
     *     (이전 `weights.getOrElse(sliceIndex){weights.last()}` 가 4회차에도 마지막 가중으로 계속 매수하던 라이브 버그 fix).
     *   - **⑨ weight 0% 회차 skip** — sliceAt 가 0% 회차를 건너뛰어 양수 비중 회차의 (index, weight)만 반환 → 0% 회차는
     *     산정 대상이 아니다(빈 주문·filledLots 꼬임 없음).
     *   - **② 부분 매수 잔여** — 직전 회차가 자금 부족으로 일부만 체결돼 [StrategyPosition.scaleInRemainderQty] 가 남아
     *     있으면, 이번 회차 수량에 잔여를 더해(buyable 로 clamp) 마저 채운다.
     *
     * @param scaleIn 분할매수 정책(listener 가 strategy.definition.scaleIn 을 주입). null = 단일 진입(현행).
     */
    fun resolveBuyQuantity(
        signal: Signal,
        context: RiskContext,
        scaleIn: ScaleInPlan? = null,
    ): Int {
        val buyBudget = resolveBuyBudget(context)
        if (signal.priceAtSignal <= BigDecimal.ZERO) return 0

        if (scaleIn == null) {
            // 분할 미설정 — 현행 전량 경로(zero-regression).
            return buyBudget.buyable
                .divide(signal.priceAtSignal, 0, RoundingMode.DOWN)
                .toInt()
                .coerceAtLeast(0)
        }

        val position = context.strategyPosition?.takeIf { it.code == signal.code && it.strategyId == signal.strategyId }
        val filledLots = (position?.filledLots ?: 0).coerceAtLeast(0)

        // ⑧⑨ — filledLots 번째 양수 비중 회차의 (index, weight). 완료/초과/모두 0%면 null → 진입 0.
        val slice = scaleIn.sliceAt(filledLots)
        if (slice == null) {
            log.info(
                "scale-in 회차 한도 초과·완료 — 진입 0: strategyId={} code={} filledLots={} effectiveSlices={}",
                signal.strategyId,
                signal.code,
                filledLots,
                scaleIn.effectiveSliceCount(),
            )
            return 0
        }
        val (sliceIndex, weight) = slice

        // ① base 고정 — 박제된 base(slice ≥ 1)가 있으면 그것을, 없으면(1차) 라이브 buyable 을 base 로.
        val fixedBase = position?.scaleInBaseBudget?.takeIf { it > BigDecimal.ZERO }
        val base = fixedBase ?: buyBudget.buyable

        // 회차 예산 = floor(고정 base × weight% / 100). 백테스트와 동일 산식.
        val sliceBudget =
            base
                .multiply(weight)
                .divide(BigDecimal("100"), 0, RoundingMode.DOWN)
        val sliceQty =
            sliceBudget
                .divide(signal.priceAtSignal, 0, RoundingMode.DOWN)
                .toInt()
                .coerceAtLeast(0)

        // ② 부분 매수 잔여 — 직전 회차 미체결분을 더한다(실제 매수가능 buyable 로 상한 clamp — 자금 없으면 못 산다).
        val remainder = (position?.scaleInRemainderQty ?: 0).coerceAtLeast(0)
        val maxAffordable =
            buyBudget.buyable
                .divide(signal.priceAtSignal, 0, RoundingMode.DOWN)
                .toInt()
                .coerceAtLeast(0)
        val quantity = (sliceQty + remainder).coerceAtMost(maxAffordable)

        log.info(
            "scale-in 회차 수량 산정: strategyId={} code={} filledLots={} sliceIndex={} weightPct={} base={} fixedBase={} " +
                "sliceBudget={} sliceQty={} remainder={} maxAffordable={} price={} qty={}",
            signal.strategyId,
            signal.code,
            filledLots,
            sliceIndex,
            weight.toPlainString(),
            base.toPlainString(),
            (fixedBase != null),
            sliceBudget.toPlainString(),
            sliceQty,
            remainder,
            maxAffordable,
            signal.priceAtSignal.toPlainString(),
            quantity,
        )
        return quantity
    }

    /**
     * **분할매수 base 박제 후보값 산정** — 1차 진입 시점 라이브 buyable (ADR 0128 ① R2 wiring).
     *
     * ## 왜 sizing 시점(listener)이 박제 지점인가
     * base = "1차 진입 시점 가용액"([RiskBudgetCalculator.BuyBudget.buyable])이다. 이 값은 1차 BUY 시그널을
     * 사이징하는 바로 이 시점([resolveBuyQuantity] 가 slice 1 을 `buyable × weight%` 로 자른 그 순간)에만 정확히
     * 존재한다. 체결(KIS WS)은 Kafka order.intent → 주문 → 비동기 체결로 **시점이 분리**되며, 그 사이 보유·예수금이
     * 바뀌어 체결 직후 재계산한 buyable 은 1차 시점 값과 다르다. 따라서 [resolveBuyQuantity] 와 **동일한 budget 산식**
     * (단일 진실원 [resolveBuyBudget])으로 산출한 buyable 을 sizing 시점에 박제해야 한다.
     *
     * R1 설계 주석(본 클래스 [resolveBuyQuantity] KDoc — "1차면 라이브 buyable 을 base 로 쓴다. 그 값이 **listener 가
     * 박제할 후보**") 의 그 후보값을 본 함수가 반환한다. 박제 자체(strategy_position write)는 listener
     * ([SignalGeneratedKafkaListener.captureScaleInBaseIfFirstSlice]) 책임이고, 전량 청산 reset 은 체결
     * listener([ConsumerExecutionStrategyPositionAdapter.applySell]) 책임이다.
     *
     * ## 박제 조건 (listener 가드)
     * - scaleIn 활성 전략 + BUY + `filledLots == 0`(1차) + 기존 박제 부재(`scaleInBaseBudget == null`) 일 때만.
     * - 이미 박제됐으면(2·3차) 재계산 0 — 고정 base 보존(분할 비중 왜곡 방지 핵심).
     *
     * @return 1차 시점 buyable(원, > 0). buyable ≤ 0(예수금/한도 소진)이면 null(박제 안 함 — 진입 0 케이스).
     */
    fun resolveScaleInBaseBudget(context: RiskContext): BigDecimal? = resolveBuyBudget(context).buyable.takeIf { it > BigDecimal.ZERO }

    fun resolveSellQuantity(
        signal: Signal,
        context: RiskContext,
        plannedEntryEnabled: Boolean = false,
    ): Int = (holdingQuantity(signal, context, plannedEntryEnabled) - reservedSellQuantity(signal, context)).coerceAtLeast(0)

    /**
     * BUY 매수 버짓 산정 — **계좌 전체 공유 풀 + 예수금 cap** (사용자 결재 2026-06-16).
     *
     * ## 왜 종목별 풀이 아니라 계좌 전체 공유 풀인가
     * 기존(PR #1008 이전)엔 노출을 **그 종목만**(`currentCodeExposure` = filter code==signal.code) 차감해, 종목마다
     * 한도(maxPositionAmount)가 따로 걸렸다. 사용자 의도는 **"계좌 전체가 공유하는 1개 풀"** 이다. 예) 순자산 1억·
     * 한도 1,000만에서 #1 전략이 900만 매수하면, #2 전략은 **남은 100만만** 매수할 수 있어야 한다. 따라서 노출은
     * 전 종목·전 전략 합산([totalExposure])으로 깎는다.
     *
     * ## 비율 한도 복원 (PR #1008 회귀 정정)
     * PR #1008 이 비율 한도(maxPositionPercent)를 제거했으나, 원래 A-1 규약은 **min(절대, 비율)** 이다. 복원한다.
     * - 총한도 = min(maxPositionAmount(절대, 원), currentCapital(순자산) × maxPositionPercent / 100).
     * - 비율 기준 자본 = **순자산(currentCapital)** — 사용자 결재.
     *
     * ## 예수금 cap (PR #1008 정합 유지)
     * 보유주식이 많아 순자산은 크지만 실제 주문할 현금(예수금)이 부족하면 KIS 가 "주문가능금액 초과(EGW...)"로 통째
     * 거부한다. 따라서 최종 매수가능은 예수금([RiskContext.orderableCash] = KIS `prvs_rcdl_excc_amt` D+2 실가용,
     * 비정상 시 예수금총액 fallback — consumer 배선, 0 합성 금지)으로 한 번 더 cap 한다.
     *
     * ## 최종 공식
     * ```
     * 총한도   = min( maxPositionAmount,  currentCapital × maxPositionPercent / 100 )
     * 전체노출 = Σ over currentPositions { avgPrice × quantity }   ← 전 종목·전 전략, 매수원가 기준
     * 남은한도 = (총한도 − 전체노출).coerceAtLeast(0)
     * 매수가능 = min( 남은한도,  orderableCash )
     * ```
     * 예수금은 노출을 다시 차감하지 않는다(이미 현금 잔액 — 보유는 이미 현금에서 빠진 값). 남은한도와 예수금 중
     * 작은 값이 매수가능이다.
     */
    private fun resolveBuyBudget(context: RiskContext): RiskBudgetCalculator.BuyBudget =
        // 사이징·사용량 표시의 단일 진실원 — domain RiskBudgetCalculator 로 위임(api RiskUsageService 와 동일 산식 보장).
        RiskBudgetCalculator.resolveBuyBudget(
            config = context.config,
            currentCapital = context.currentCapital,
            orderableCash = context.orderableCash,
            positions = context.currentPositions,
        )

    /**
     * SELL 가용 수량 평가용 보유 수량 (ADR 0110 §2-4 SELL 전략별 분기).
     *
     * - `context.strategyPosition != null`(분할매수 전략 lot 존재) → **그 전략 보유 수량**(다전략 교차 청산 방지).
     * - null(분할매수 미사용 전략·과거 보유) → 기존 user+code 합산 보유(하위호환 fallback — zero-regression).
     *
     * ## 원장 0 → 실잔고 fallback (canary D2 hotfix #4 — D1 잔여)
     * 과거엔 strategyPosition 행이 **존재하면 quantity=0 이어도 그 0을 채택**해 실잔고(currentPositions) fallback 을
     * 못 탔다. strategy_position 원장이 오염(체결 누락·replay 미스)돼 quantity=0 으로 떨어지면, 실제로는 KIS 잔고에
     * 보유가 있는데도 SELL 이 `SELL_WITHOUT_POSITION` 으로 영구 차단 → 청산 마비가 재발한다. `quantity > 0` 조건을
     * takeIf 에 추가해, 원장 0 이면 user+code 실잔고 합산을 최후 방어선으로 사용한다(분할매수 정상 lot 은 영향 0).
     */
    private fun holdingQuantity(
        signal: Signal,
        context: RiskContext,
        plannedEntryEnabled: Boolean = false,
    ): Int {
        val byStrategy =
            context.strategyPosition
                ?.takeIf { it.code == signal.code && it.strategyId == signal.strategyId && it.quantity > 0 }
                ?.quantity
        if (byStrategy != null) return byStrategy

        // ★ ADR 0130 L1 — owner=AUTOPILOT + 게이트 on 이면 user+code 실잔고 fallback 을 **차단**한다.
        //   산 적 없는 전략(그 종목 lot 미보유)의 SELL 이 holding 0 → SELL_WITHOUT_POSITION BLOCKED → 교차 청산(001820) 원천 차단.
        //   USER/SYSTEM·legacy(null)·게이트 off 는 현행 fallback 유지(canary D2 청산 마비 방지 — zero-regression).
        if (lotOwnershipEnforced(signal, plannedEntryEnabled)) return 0

        return context.currentPositions
            .firstOrNull { it.code == signal.code && it.quantity > 0 }
            ?.quantity
            ?: 0
    }

    /**
     * L1 lot 소유권 강제 여부 (ADR 0130 §6-1) — owner=AUTOPILOT **AND** 게이트 on 일 때만 true.
     *
     * true 면 [holdingQuantity] 가 user+code 실잔고 fallback 을 쓰지 않는다(산 전략만 자기 lot 청산). false 면
     * 현행 fallback(zero-regression). USER/SYSTEM·legacy owner(null)·게이트 off 는 항상 false.
     */
    private fun lotOwnershipEnforced(
        signal: Signal,
        plannedEntryEnabled: Boolean,
    ): Boolean = plannedEntryEnabled && signal.strategyOwner == StrategyOwner.AUTOPILOT

    /**
     * 미체결 SELL 예약 수량 (ADR 0110 §2-4 + 자본 path hotfix — "먼저 오는 쪽이 이긴다").
     *
     * - 전략 lot 기준 평가 시(`strategyPosition != null`) → **그 전략의** open SELL 만 합산(교차 차감 방지).
     * - null fallback → 기존 user+code 전체 open SELL 합산(하위호환).
     *
     * ## auto-tp/auto-sl 선주문 제외 (hotfix — 매도 시그널 무력화 방지)
     * 우리가 건 `auto-tp:`/`auto-sl:` 같은 전략 AUTO 선주문은 매도 시그널/손절 발행 직전 [RestingSellOrderCanceller]가
     * **선취소**해 수량을 풀어준다. 따라서 이 자가 관리 SELL은 예약 수량에서 제외해야 새 청산 시그널이 ALLOWED 된다
     * (제외하지 않으면 익절 선주문이 보유 전량을 예약 → availableSell=0 → 매도 시그널이 SELL_QUANTITY_UNAVAILABLE로
     * 무력화되던 결함). 실제 KIS 매도가능수량은 [SignalGeneratedKafkaListener.ensureKisHoldingIfSell] + KIS 자체가 보증.
     * 수동(MANUAL)/타전략 open SELL은 그대로 합산(침범 금지 — 취소 대상 아님).
     */
    private fun reservedSellQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int {
        val byStrategy = context.strategyPosition?.let { it.code == signal.code && it.strategyId == signal.strategyId } == true
        return context.openOrders
            .filter { it.code == signal.code && it.side == OrderSide.SELL }
            .filter { !byStrategy || it.strategyId == signal.strategyId }
            .filterNot { it.isCancellableAutoExit(signal.strategyId) }
            .sumOf { it.quantity }
    }

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
