package kr.spdaewoon.magicjar.consumer.signal

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.reconciliation.DataReadinessService
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalContext
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.signal.port.SignalRepository
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.strategy.JvmStrategyEvaluator
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
 * 1. `StrategyLookupPort.findActiveByCodeAndInterval(code, interval)` — 활성 strategy 조회
 *    (Phase 2에서 Redis cache 적용 — 본 단계는 단순 stub 가능)
 * 2. 활성 strategy 별:
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
    private val jvmStrategyEvaluator: JvmStrategyEvaluator,
    private val signalDedupeGuard: SignalDedupeGuard,
    private val signalKafkaProducer: SignalKafkaProducer,
    @Value("\${magicjar.signal.eval-lookback-candles:500}") private val evalLookbackCandles: Int = 500,
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
        evaluateTakeProfitExit(strategy, code, currentPrice, buyResult)?.let { return it }
        if (mapToSignalSide(buyResult.signal) == SignalSide.BUY) {
            return SignalDecision(SignalSide.BUY, buyResult)
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

        val persisted =
            try {
                repo
                    .findByCodeAndInterval(code, interval)
                    .asSequence()
                    .filter { it.ts <= boundaryTs }
                    .filterNot { it.ts == boundaryTs }
                    .toList()
            } catch (e: Exception) {
                log.warn(
                    "RealtimeSignalGenerator candle history 조회 실패 — boundary candle 1건으로 평가: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                return listOf(boundaryCandle)
            }

        val lookback = evalLookbackCandles.coerceAtLeast(1)
        return (persisted + boundaryCandle)
            .sortedBy { it.ts }
            .takeLast(lookback)
    }

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
