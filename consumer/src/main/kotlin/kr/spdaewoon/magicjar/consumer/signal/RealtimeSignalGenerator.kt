package kr.spdaewoon.magicjar.consumer.signal

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.consumer.reconciliation.DataReadinessService
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.market.session.TradingHoursGate
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalContext
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.signal.port.SignalRepository
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.strategy.JvmStrategyEvaluator
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitCalculator
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitDecision
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitInput
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 실시간 시그널 생성기 (ADR 0066 §7-1 RealtimeSignalGenerator + §7-2 평가 흐름 본체).
 *
 * Phase 1 본체 — BoundaryClosingKafkaListener가 boundary Kafka 토픽 수신 시 호출.
 *
 * ### 흐름 (ADR 0066 §7-2 1:1 매핑)
 * 1. `StrategyLookupPort.findActiveByCodeAndInterval(code, interval)` — 평가 가능 strategy 조회
 *    (Phase 2에서 Redis cache 적용 — 본 단계는 단순 stub 가능)
 * 2. 평가 가능 strategy 별:
 *    a. `SignalDedupeGuard.tryAcquire(...)` — Redis SETNX 30s (ADR 0066 §2-5 E-1)
 *       └─ 이미 처리됨 → skip
 *    b. `JvmStrategyEvaluator.evaluate(...)` — sandbox 평가 (ADR 0047 §2-2)
 *       └─ timeout 100ms · CPU budget 50ms · auto-disable
 *    c. signal 발생 시(BUY/SELL — HOLD는 영속 0):
 *       - `SignalRepository.save(signal)` — DB INSERT (UQ 위반 시 skip)
 *       - `SignalKafkaProducer.publish(signal)` — Kafka `signal.generated.v1`
 *
 * ### HOLD 영속 정책 (ADR 0066 §9-2 정합)
 * BUY/SELL만 publish — HOLD는 noise (0.012 msg/s 1,000/day 기준 BUY/SELL only).
 *
 * ### Phase 2 의존
 * - SignalRepository — Phase 2 backend-core JpaAdapter 구현
 * - StrategyLookupPort — Phase 2 backend-core Redis cache + JpaAdapter 구현
 *   Phase 1은 ObjectProvider 패턴으로 옵셔널 의존 — bean 부재 시 빈 list 반환 stub 사용.
 *
 * spec 인용: ADR 0066 §7-1 + §7-2 + §11-1 Phase 1 (3).
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class RealtimeSignalGenerator(
    private val strategyLookupProvider: ObjectProvider<StrategyLookupPort>,
    private val signalRepositoryProvider: ObjectProvider<SignalRepository>,
    private val candleRepositoryProvider: ObjectProvider<CandleRepository>,
    private val dataReadinessServiceProvider: ObjectProvider<DataReadinessService>,
    private val riskPositionPortProvider: ObjectProvider<RiskPositionPort>,
    private val strategyPositionPortProvider: ObjectProvider<StrategyPositionPort>,
    private val jvmStrategyEvaluator: JvmStrategyEvaluator,
    private val signalDedupeGuard: SignalDedupeGuard,
    private val signalKafkaProducer: SignalKafkaProducer,
    private val marketSessionResolver: MarketSessionResolver,
    private val businessDayCalendarProvider: ObjectProvider<BusinessDayCalendar>,
    @Value("\${magicjar.signal.eval-lookback-candles:500}") private val evalLookbackCandles: Int = 500,
    @Value("\${magicjar.signal.market-hours-gate.enabled:true}") private val marketHoursGateEnabled: Boolean = true,
    @Value("\${magicjar.strategy.scale-in.enabled:false}") private val scaleInEnabled: Boolean = false,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(RealtimeSignalGenerator::class.java)

    /**
     * Kafka boundary closed payload 수신 시 호출.
     *
     * @param code 종목 코드
     * @param interval 평가 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h 중 하나)
     * @param boundaryTs boundary closing ts (deterministic — ADR 0064 §4-2)
     * @param candle 마감된 boundary 캔들 (payload에서 변환)
     */
    fun onBoundaryClosed(
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        candle: Candle,
    ) {
        val strategyLookup = strategyLookupProvider.ifAvailable
        if (strategyLookup == null) {
            // Phase 2 미머지 시 — bean 부재 → 빈 list로 graceful no-op
            log.debug(
                "RealtimeSignalGenerator StrategyLookupPort bean 부재 (Phase 2 의존) — skip: code={} interval={} ts={}",
                code,
                interval.code,
                boundaryTs,
            )
            return
        }

        val activeStrategies =
            try {
                strategyLookup.findActiveByCodeAndInterval(code, interval)
            } catch (e: Exception) {
                log.error(
                    "RealtimeSignalGenerator StrategyLookup 실패: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                return
            }

        if (activeStrategies.isEmpty()) {
            log.trace(
                "RealtimeSignalGenerator 활성 strategy 0건 — skip: code={} interval={}",
                code,
                interval.code,
            )
            return
        }

        // 장운영시간 게이트 (2026-06-04 dogfooding 결함 fix) — boundaryTs가 영업일+정규장 안일 때만 평가 진행.
        // 08:00~09:00 NXT pre-hour 캔들(정책상 의도된 적재)로 시그널 발화 → 장전 주문 거부 사태 차단.
        // 캔들 적재 파이프라인은 불변 — 여기서 평가 게이트만 적용 (defense in depth 1층).
        if (marketHoursGateEnabled && !isWithinTradingHours(boundaryTs)) {
            log.info(
                "RealtimeSignalGenerator market-hours-gate skip: boundaryTs={} interval={} reason=NOT_REGULAR_SESSION_OR_HOLIDAY",
                boundaryTs,
                interval.code,
            )
            return
        }

        val readinessService = dataReadinessServiceProvider.ifAvailable
        if (readinessService != null) {
            val readiness = readinessService.check(code, interval, boundaryTs)
            if (!readiness.ready) {
                log.warn(
                    "RealtimeSignalGenerator data readiness skip: code={} interval={} ts={} status={} reason={} strategies={}",
                    code,
                    interval.code,
                    boundaryTs,
                    readiness.status,
                    readiness.reason,
                    activeStrategies.mapNotNull { it.id },
                )
                readinessService.auditSkip(readiness, activeStrategies.mapNotNull { it.id })
                return
            }
        }

        log.debug(
            "RealtimeSignalGenerator 평가 시작: code={} interval={} ts={} activeCount={}",
            code,
            interval.code,
            boundaryTs,
            activeStrategies.size,
        )

        for (strategy in activeStrategies) {
            try {
                processStrategy(strategy, code, interval, boundaryTs, candle)
            } catch (e: Exception) {
                log.error(
                    "RealtimeSignalGenerator strategy 평가 실패: strategyId={} code={} interval={} err={}",
                    strategy.id,
                    code,
                    interval.code,
                    e.message,
                )
            }
        }
    }

    private fun processStrategy(
        strategy: Strategy,
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        candle: Candle,
    ) {
        val strategyId =
            strategy.id ?: run {
                log.warn(
                    "RealtimeSignalGenerator strategy.id null — skip: userId={} code={}",
                    strategy.userId,
                    code,
                )
                return
            }

        // 1. dedupe guard — Redis SETNX 30s (ADR 0066 §2-5 E-1)
        if (!signalDedupeGuard.tryAcquire(strategyId, code, interval, boundaryTs)) {
            return
        }

        // 2. context 구성
        val context =
            SignalContext(
                code = code,
                interval = interval,
                boundaryTs = boundaryTs,
                nowKst = clock.now(),
            )

        val candles = loadEvaluationCandles(code, interval, boundaryTs, candle)

        // 3. JvmStrategyEvaluator 평가 (ADR 0047 sandbox)
        //    buyCondition 우선 평가 후, BUY가 아니면 sellCondition을 SELL 신호로 매핑한다.
        val buyAst =
            strategy.definition.dsl.buyCondition ?: run {
                log.trace(
                    "RealtimeSignalGenerator buyCondition 부재 — skip: strategyId={} code={}",
                    strategyId,
                    code,
                )
                return
            }
        val decision = evaluateDecision(strategy, code, buyAst, strategy.definition.dsl.sellCondition, candles, candle.closeRaw) ?: return
        val side = decision.side
        val evalResult = decision.result

        // 4. HOLD는 영속 0 (ADR 0066 §9-2 noise 회피)
        if (side == SignalSide.HOLD) {
            log.trace(
                "RealtimeSignalGenerator HOLD skip: strategyId={} code={} interval={} ts={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
            )
            return
        }
        if (strategy.status == StrategyStatus.BUY_BLOCKED && side == SignalSide.BUY) {
            log.warn(
                "RealtimeSignalGenerator BUY_BLOCKED 신규 BUY skip: strategyId={} code={} interval={} ts={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
            )
            return
        }

        // 5. Signal 생성
        val strength = (evalResult.strength.multiply(BigDecimal(100))).toInt().coerceIn(0, 100)
        val reason = buildReason(evalResult)
        val signal =
            Signal(
                strategyId = strategyId,
                userId = strategy.userId.toString(),
                code = code,
                interval = interval,
                ts = boundaryTs,
                side = side,
                strength = strength,
                reason = reason,
                priceAtSignal = candle.closeRaw,
                contextJson = null,
                createdAt = clock.now(),
            )

        // 6. 영속 — Phase 2 의존 (ObjectProvider 패턴)
        val signalRepository = signalRepositoryProvider.ifAvailable
        val persisted =
            if (signalRepository != null) {
                try {
                    signalRepository.save(signal)
                } catch (e: Exception) {
                    log.warn(
                        "RealtimeSignalGenerator save 실패 (UQ violation 또는 영속 오류): strategyId={} code={} err={}",
                        strategyId,
                        code,
                        e.message,
                    )
                    return
                }
            } else {
                log.debug(
                    "RealtimeSignalGenerator SignalRepository bean 부재 (Phase 2 의존) — id 없는 signal publish 차단: strategyId={} code={}",
                    strategyId,
                    code,
                )
                signal
            }

        if (persisted.id == null) {
            log.error(
                "RealtimeSignalGenerator signal id null — Kafka publish 차단: strategyId={} code={} interval={} ts={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
            )
            return
        }

        // 7. Kafka publish — Phase 2 STOMP fanout consumer가 listen
        signalKafkaProducer.publish(persisted)

        log.info(
            "RealtimeSignalGenerator 시그널 발행: strategyId={} userId={} code={} interval={} ts={} side={} strength={} reason={}",
            strategyId,
            signal.userId,
            code,
            interval.code,
            boundaryTs,
            side,
            strength,
            reason,
        )
    }

    /**
     * 기존 [kr.spdaewoon.magicjar.domain.strategy.Signal] enum → 신규 [SignalSide] 매핑.
     * (ADR 0066 §5-2 신규 도메인 패키지 분리 정합)
     */
    private fun mapToSignalSide(legacy: kr.spdaewoon.magicjar.domain.strategy.Signal): SignalSide =
        when (legacy) {
            kr.spdaewoon.magicjar.domain.strategy.Signal.BUY -> SignalSide.BUY
            kr.spdaewoon.magicjar.domain.strategy.Signal.SELL -> SignalSide.SELL
            kr.spdaewoon.magicjar.domain.strategy.Signal.HOLD -> SignalSide.HOLD
        }

    private data class SignalDecision(
        val side: SignalSide,
        val result: StrategyEvalResult,
    )

    private fun evaluateDecision(
        strategy: Strategy,
        code: String,
        buyAst: DslNode,
        sellAst: DslNode?,
        candles: List<Candle>,
        currentPrice: BigDecimal,
    ): SignalDecision? {
        val strategyId = strategy.id ?: return null
        val buyResult = evaluateAst(strategyId, code, "BUY", buyAst, candles) ?: return null
        val buyFired = mapToSignalSide(buyResult.signal) == SignalSide.BUY

        // scale-in/exit 활성 게이트 (spec §8-1) — toggle ON + scaleIn|partialExits 설정 전략만 신규 경로.
        // 그 외(toggle OFF · 미설정 전략)는 현행 단일 진입 + 전량 익절 경로(zero-regression LOCK).
        if (isScaleInExitActive(strategy)) {
            evaluateScaleInExit(strategy, code, currentPrice, buyResult, buyFired)?.let { return it }
            if (buyFired) {
                return SignalDecision(SignalSide.BUY, buyResult)
            }
        } else {
            evaluateTakeProfitExit(strategy, code, currentPrice, buyResult)?.let { return it }
            if (buyFired) {
                return SignalDecision(SignalSide.BUY, buyResult)
            }
        }

        if (sellAst != null) {
            val sellResult = evaluateAst(strategyId, code, "SELL", sellAst, candles) ?: return null
            if (mapToSignalSide(sellResult.signal) == SignalSide.BUY) {
                return SignalDecision(
                    SignalSide.SELL,
                    sellResult.copy(
                        signal = kr.spdaewoon.magicjar.domain.strategy.Signal.SELL,
                        reason = sellResult.reason + ("condition" to "SELL"),
                    ),
                )
            }
        }

        return SignalDecision(SignalSide.HOLD, buyResult)
    }

    private fun evaluateTakeProfitExit(
        strategy: Strategy,
        code: String,
        currentPrice: BigDecimal,
        baseResult: StrategyEvalResult,
    ): SignalDecision? {
        val takeProfitPct = strategy.definition.takeProfitPct ?: return null
        val positionPort = riskPositionPortProvider.ifAvailable
        if (positionPort == null) {
            log.warn(
                "takeProfitPct 설정 전략의 position 조회 port 부재 — exit skip: strategyId={} code={}",
                strategy.id,
                code,
            )
            return null
        }
        val position =
            try {
                positionPort
                    .findCurrentByUser(strategy.userId.toString())
                    .firstOrNull { it.code == code && it.quantity > 0 }
            } catch (e: Exception) {
                log.warn(
                    "takeProfitPct position 조회 실패 — exit skip: strategyId={} code={} err={}",
                    strategy.id,
                    code,
                    e.message,
                )
                null
            } ?: return null

        if (position.avgPrice <= BigDecimal.ZERO) return null
        val profitPct =
            currentPrice
                .subtract(position.avgPrice)
                .divide(position.avgPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))

        if (profitPct < takeProfitPct) return null

        return SignalDecision(
            SignalSide.SELL,
            baseResult.copy(
                signal = kr.spdaewoon.magicjar.domain.strategy.Signal.SELL,
                strength = BigDecimal.ONE,
                reason =
                    baseResult.reason +
                        mapOf(
                            "trigger" to "TAKE_PROFIT",
                            "condition" to "POSITION_PROFIT_PCT",
                            "profitPct" to profitPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "takeProfitPct" to takeProfitPct.toPlainString(),
                            "avgPrice" to position.avgPrice.toPlainString(),
                            "currentPrice" to currentPrice.toPlainString(),
                        ),
            ),
        )
    }

    /**
     * scale-in/exit 신규 경로 활성 여부 (spec §8-1 ★ feature toggle 게이트).
     *
     * `magicjar.strategy.scale-in.enabled`(default false) ON **AND** 전략이 `scaleIn` 또는 `partialExits`를
     * 설정한 경우에만 [ScaleInExitCalculator] 경로를 탄다. toggle OFF거나 미설정 전략은 현행 단일 경로(zero-regression).
     *
     * 라이브 자본 경로 진입은 추가로 StrategyService ACTIVE 전이 게이트(`SCALE_IN_NOT_ENABLED`)가 막는다 —
     * 본 게이트는 그 위의 평가 단(엔진은 들어왔으나 toggle OFF면 평가 자체를 현행 경로로).
     */
    private fun isScaleInExitActive(strategy: Strategy): Boolean {
        if (!scaleInEnabled) return false
        val def = strategy.definition
        return def.scaleIn != null || !def.partialExits.isNullOrEmpty()
    }

    /**
     * scale-in 회차 진입 + 평단 기준 stop-loss/분할 익절/전량 익절 단일 평가 (spec §5 · ADR 0111 §2-4).
     *
     * [ScaleInExitCalculator] 순수 함수를 호출하고 그 sealed 결정을 시그널 [SignalDecision]으로 매핑한다.
     * 모든 leg는 **시그널 경로**로만 발행 — OrderIntent 직접 생성 0(spec §5-3 LOCK). 회차 수량·체결가 기반
     * idempotency suffix·StrategyPosition upsert는 R2-2 backend-core(어댑터·체결 listener) 책임.
     *
     * StrategyPositionPort bean 부재(R2-2 어댑터 머지 전)면 graceful no-op — 신규 경로 평가를 건너뛰고
     * null을 반환해 호출부가 BUY/HOLD 현행 경로로 이어간다.
     */
    private fun evaluateScaleInExit(
        strategy: Strategy,
        code: String,
        currentPrice: BigDecimal,
        baseResult: StrategyEvalResult,
        buyFired: Boolean,
    ): SignalDecision? {
        val strategyId = strategy.id ?: return null
        val positionPort = strategyPositionPortProvider.ifAvailable
        if (positionPort == null) {
            log.warn(
                "scale-in/exit 활성 전략의 StrategyPositionPort bean 부재 (R2-2 어댑터 의존) — 신규 경로 skip: strategyId={} code={}",
                strategyId,
                code,
            )
            return null
        }

        val position: StrategyPosition? =
            try {
                positionPort.findByUserStrategyCode(strategy.userId.toString(), strategyId, code)
            } catch (e: Exception) {
                log.warn(
                    "scale-in/exit StrategyPosition 조회 실패 — 신규 경로 skip: strategyId={} code={} err={}",
                    strategyId,
                    code,
                    e.message,
                )
                return null
            }

        val input =
            ScaleInExitInput(
                position = position,
                currentPrice = currentPrice,
                scaleIn = strategy.definition.scaleIn,
                fixedStopLossPct = strategy.definition.fixedStopLoss,
                takeProfitPct = strategy.definition.takeProfitPct,
                partialExits = strategy.definition.partialExits,
                buySignalFired = buyFired,
                // 회차 수량(budget 산정)·직전 체결가·회차 epoch 은 R2-2 어댑터(체결 listener)가 채운다.
                // R2-1 시그널 단계에서는 진입 여부·청산 판정만 수행하고 수량은 0(어댑터가 budget으로 확정).
                sliceBudgetQuantity = 0,
            )

        return when (val decision = ScaleInExitCalculator.evaluate(input)) {
            is ScaleInExitDecision.StopLoss ->
                sellSignal(
                    baseResult = baseResult,
                    reasonExtras =
                        mapOf(
                            "trigger" to "STOP_LOSS",
                            "condition" to "POSITION_AVG_STOP_LOSS",
                            "quantity" to decision.quantity.toString(),
                            "currentPrice" to currentPrice.toPlainString(),
                        ) + avgPriceReason(position),
                )

            is ScaleInExitDecision.PartialTakeProfit ->
                sellSignal(
                    baseResult = baseResult,
                    reasonExtras =
                        mapOf(
                            "trigger" to "PARTIAL_TAKE_PROFIT#${decision.stageIndex}",
                            "condition" to "POSITION_PARTIAL_TP",
                            "stageIndex" to decision.stageIndex.toString(),
                            "exitWeightPct" to decision.weightPct.toPlainString(),
                            "quantity" to decision.quantity.toString(),
                            "profitPct" to decision.profitPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "currentPrice" to currentPrice.toPlainString(),
                        ) + avgPriceReason(position),
                )

            is ScaleInExitDecision.TakeProfit ->
                sellSignal(
                    baseResult = baseResult,
                    reasonExtras =
                        mapOf(
                            "trigger" to "TAKE_PROFIT",
                            "condition" to "POSITION_PROFIT_PCT",
                            "quantity" to decision.quantity.toString(),
                            "profitPct" to decision.profitPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "takeProfitPct" to (strategy.definition.takeProfitPct?.toPlainString() ?: ""),
                            "currentPrice" to currentPrice.toPlainString(),
                        ) + avgPriceReason(position),
                )

            is ScaleInExitDecision.EnterSlice ->
                SignalDecision(
                    SignalSide.BUY,
                    baseResult.copy(
                        signal = kr.spdaewoon.magicjar.domain.strategy.Signal.BUY,
                        reason =
                            baseResult.reason +
                                mapOf(
                                    "trigger" to "SCALE_IN_SLICE#${decision.sliceIndex + 1}:${decision.trigger.name}",
                                    "condition" to "SCALE_IN",
                                    "sliceIndex" to decision.sliceIndex.toString(),
                                    "sliceWeightPct" to decision.weightPct.toPlainString(),
                                    "reentryTrigger" to decision.trigger.name,
                                ),
                    ),
                )

            ScaleInExitDecision.Hold -> null
        }
    }

    private fun avgPriceReason(position: StrategyPosition?): Map<String, Any> =
        position?.let { mapOf("avgPrice" to it.avgPrice.toPlainString()) } ?: emptyMap()

    private fun sellSignal(
        baseResult: StrategyEvalResult,
        reasonExtras: Map<String, Any>,
    ): SignalDecision =
        SignalDecision(
            SignalSide.SELL,
            baseResult.copy(
                signal = kr.spdaewoon.magicjar.domain.strategy.Signal.SELL,
                strength = BigDecimal.ONE,
                reason = baseResult.reason + reasonExtras,
            ),
        )

    private fun evaluateAst(
        strategyId: Long,
        code: String,
        label: String,
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult? =
        try {
            jvmStrategyEvaluator.evaluate(strategyId, ast, candles)
        } catch (e: Exception) {
            log.warn(
                "RealtimeSignalGenerator {} evaluate 실패: strategyId={} code={} candles={} err={}",
                label,
                strategyId,
                code,
                candles.size,
                e.message,
            )
            null
        }

    private fun loadEvaluationCandles(
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        boundaryCandle: Candle,
    ): List<Candle> {
        val repo = candleRepositoryProvider.ifAvailable
        if (repo == null) {
            log.warn(
                "RealtimeSignalGenerator CandleRepository bean 부재 — boundary candle 1건으로 평가: code={} interval={}",
                code,
                interval.code,
            )
            return listOf(boundaryCandle)
        }

        val lookback = evalLookbackCandles.coerceAtLeast(1)
        val historyLimit = (lookback - 1).coerceAtLeast(0)
        val persisted =
            try {
                if (historyLimit == 0) {
                    emptyList()
                } else {
                    repo.findLatestBeforeByCodeAndInterval(code, interval, boundaryTs.toJavaInstant(), historyLimit)
                }
            } catch (e: Exception) {
                log.warn(
                    "RealtimeSignalGenerator candle history 조회 실패 — boundary candle 1건으로 평가: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                return listOf(boundaryCandle)
            }

        return (persisted + boundaryCandle)
            .sortedBy { it.ts }
            .takeLast(lookback)
    }

    /**
     * boundaryTs(캔들 마감 UTC Instant)가 영업일 + 정규장(09:00~15:30 KST) 안인지 판정.
     *
     * - 영업일 판정: [BusinessDayCalendar] (market_calendar DB 권위) 우선, bean 부재 시 resolver 주말 가드 fallback.
     * - 정규장 판정: [MarketSessionResolver.resolve] 가 [MarketSession.Regular] 반환 시.
     *   resolver는 KST 변환 후 09:00~15:29(분 단위) 을 Regular로 본다.
     *   09:00 정각 boundary(=08:59 1m 캔들 마감) 는 Regular 진입, 15:30 boundary(=15:29 캔들 마감) 는 경계.
     */
    private fun isWithinTradingHours(boundaryTs: Instant): Boolean =
        TradingHoursGate(
            sessionResolver = marketSessionResolver,
            businessDayCalendar = businessDayCalendarProvider.ifAvailable,
            onCalendarError = { date, e ->
                log.warn(
                    "RealtimeSignalGenerator BusinessDayCalendar 조회 실패 — resolver 영업일 판정 fallback: date={} err={}",
                    date,
                    e.message,
                )
            },
        ).isWithinTradingHours(boundaryTs)

    /** StrategyEvalResult.reason Map → 한 줄 reason string. */
    private fun buildReason(result: StrategyEvalResult): String {
        val trigger = result.reason["trigger"]?.toString() ?: "RULE_MATCH"
        val lhs = result.reason["lhs"]?.toString()
        val rhs = result.reason["rhs"]?.toString()
        return if (lhs != null && rhs != null) {
            "$trigger: $lhs vs $rhs"
        } else {
            trigger
        }
    }
}
