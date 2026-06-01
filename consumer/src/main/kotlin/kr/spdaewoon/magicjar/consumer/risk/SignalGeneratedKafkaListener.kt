package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.order.KisExecutionBackfillService
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.RiskGatewayResult
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.RiskVerdict
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskNotifier
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.risk.port.SignalRiskStatusUpdater
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Kafka `signal.generated.v1` listener (ADR 0067 §7-1 SignalGeneratedKafkaListener + §11-1 Phase 1 (8) + E-1).
 *
 * ### 흐름 (ADR 0067 §7-2)
 * ```
 * Kafka signal.generated.v1 (partition by userId)
 *   ↓ onSignalGenerated(record)
 * 1. payload deserialize → Signal
 * 2. RiskContext 빌드 (config + capital + positions + dailyPnl + tradeFreq + killSwitch)
 * 3. RiskGatewayEvaluator.evaluate
 *      ├─ Allowed → SignalRiskStatusUpdater.markAllowed
 *      │         → TradeFrequencyGuard.record (sliding window 누적)
 *      │         → OrderIntentKafkaProducer.publish (order.intent.v1)
 *      └─ Blocked → RiskEventRepository.save
 *                 → SignalRiskStatusUpdater.markBlocked
 *                 → RiskNotifier.notifyViolation (STOMP)
 *                 → RiskViolationKafkaProducer.publish (risk.violation.v1)
 *                 → AutoPauseGuard.checkAndPauseIfNeeded (auto-PAUSE 임계 검사)
 * ```
 *
 * ### Phase 2 graceful fallback (헌법 §6 + ADR 0066 ObjectProvider 패턴)
 * Phase 2 backend-core 미합류 시 — `RiskConfigRepository`·`RiskEventRepository`·`RiskNotifier`·
 * `SignalRiskStatusUpdater`·`RiskPositionPort` bean 모두 부재 가능. 본 listener는 ObjectProvider로
 * graceful skip — config 부재 시 RiskConfig.defaultFor seed, 다른 port 부재 시 해당 단계 skip.
 *
 * ### 헌법 §3 KIS-first 영향 0
 * Kafka 내부 listen + publish만 — KIS REST/WS 호출 0.
 *
 * spec 인용: ADR 0067 §7-1 SignalGeneratedKafkaListener + §7-2 평가 흐름 + E-1 (Signal → Risk → Order intent).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class SignalGeneratedKafkaListener(
    private val objectMapper: ObjectMapper,
    private val riskGatewayEvaluator: RiskGatewayEvaluator,
    private val tradeFrequencyGuard: TradeFrequencyRedisGuard,
    private val dailyPnlCalculator: DailyPnlCalculator,
    private val capitalSnapshotProvider: kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    private val riskViolationKafkaProducer: RiskViolationKafkaProducer,
    private val autoPauseGuard: AutoPauseGuard,
    private val riskConfigRepositoryProvider: ObjectProvider<RiskConfigRepository>,
    private val riskEventRepositoryProvider: ObjectProvider<RiskEventRepository>,
    private val riskPositionPortProvider: ObjectProvider<RiskPositionPort>,
    private val signalRiskStatusUpdaterProvider: ObjectProvider<SignalRiskStatusUpdater>,
    private val riskNotifierProvider: ObjectProvider<RiskNotifier>,
    private val killSwitchQueryPortProvider: ObjectProvider<KillSwitchQueryPort>,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val strategyLookupPortProvider: ObjectProvider<StrategyLookupPort>,
    private val kisBalanceQueryPortProvider: ObjectProvider<KisBalanceQueryPort>,
    private val executionBackfillServiceProvider: ObjectProvider<KisExecutionBackfillService>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(SignalGeneratedKafkaListener::class.java)

    /**
     * `signal.generated.v1` listen — partition key = userId (ADR 0066 §7-1 정합).
     *
     * groupId = `magicjar-consumer-risk` — 독립 thread + offset.
     */
    @KafkaListener(
        topics = ["signal.generated.v1"],
        groupId = "magicjar-consumer-risk",
        containerFactory = "manualImmediateKafkaListenerContainerFactory",
    )
    fun onSignalGenerated(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        try {
            val signal =
                deserialize(record.value()) ?: run {
                    ack.acknowledge()
                    return
                }

            // HOLD 시그널은 risk 검사 무관 (T-O 진입 X)
            if (signal.side == SignalSide.HOLD) {
                log.debug("SignalGeneratedKafkaListener HOLD skip: signalId={}", signal.id)
                ack.acknowledge()
                return
            }

            val riskPlanBlocked = ensureRiskPlanIfBuy(signal)
            if (riskPlanBlocked != null) {
                handleBlocked(signal, riskPlanBlocked)
                ack.acknowledge()
                return
            }

            val syncBlocked = ensureKisExecutionSyncIfReal(signal)
            if (syncBlocked != null) {
                handleBlocked(signal, syncBlocked)
                ack.acknowledge()
                return
            }

            val context =
                try {
                    buildContext(signal)
                } catch (e: RealRiskContextUnavailableException) {
                    handleBlocked(signal, syncBlocked(signal, e.reasonCode))
                    ack.acknowledge()
                    return
                }
            val result = riskGatewayEvaluator.evaluate(signal, context)

            when (result) {
                is RiskGatewayResult.Allowed -> handleAllowed(signal, context, result)
                is RiskGatewayResult.Blocked -> handleBlocked(signal, result)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            log.error(
                "SignalGeneratedKafkaListener dispatch 실패 (ack 보류, 재처리 대상): offset={} key={} err={}",
                record.offset(),
                record.key(),
                e.message,
                e,
            )
        }
    }

    private fun handleAllowed(
        signal: Signal,
        context: RiskContext,
        allowed: RiskGatewayResult.Allowed,
    ) {
        val quantity = resolveOrderQuantity(signal, context)
        if (quantity <= 0) {
            handleBlocked(
                signal,
                RiskGatewayResult.Blocked(
                    signalId = allowed.signalId,
                    rule = RiskRule.INVALID_ORDER_INTENT,
                    reason = "Resolved order quantity must be positive: $quantity",
                    context =
                        mapOf(
                            "code" to signal.code,
                            "side" to signal.side.name,
                            "quantity" to quantity,
                        ),
                ),
            )
            return
        }

        val sellableBlocked = ensureKisHoldingIfSell(signal, quantity)
        if (sellableBlocked != null) {
            handleBlocked(signal, sellableBlocked)
            return
        }

        // 1. signal.risk_status = ALLOWED 갱신 (Phase 2 어댑터 부재 시 skip)
        signalRiskStatusUpdaterProvider.getIfAvailable()?.let {
            try {
                it.markAllowed(allowed.signalId)
            } catch (e: Exception) {
                log.warn("markAllowed 실패 (skip): signalId={} err={}", allowed.signalId, e.message)
            }
        }

        // 2. TradeFrequencyGuard 누적 — 다음 평가 시 sliding window count 반영
        tradeFrequencyGuard.record(signal.strategyId, signal.userId, allowed.signalId, context.nowKst)

        // 3. order.intent.v1 publish — T-O 진입
        orderIntentKafkaProducer.publish(signal, quantity)
    }

    private fun ensureKisHoldingIfSell(
        signal: Signal,
        requestedQuantity: Int,
    ): RiskGatewayResult.Blocked? {
        if (signal.side != SignalSide.SELL) return null
        val port =
            kisBalanceQueryPortProvider.getIfAvailable()
                ?: return kisSellQuantityBlocked(signal, requestedQuantity, 0, "KIS_BALANCE_LOOKUP_UNAVAILABLE")
        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val snapshot =
            try {
                port.fetchFullBalance(signal.userId, accountType)
            } catch (e: Exception) {
                log.warn("KIS SELL 잔고 검증 실패: userId={} code={} err={}", signal.userId, signal.code, e.message)
                return kisSellQuantityBlocked(
                    signal = signal,
                    requestedQuantity = requestedQuantity,
                    kisHoldingQty = 0,
                    reasonCode = "KIS_BALANCE_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}",
                )
            }
        val kisHoldingQty = snapshot.holdings.firstOrNull { it.code == signal.code }?.quantity ?: 0
        if (kisHoldingQty < requestedQuantity) {
            return kisSellQuantityBlocked(
                signal = signal,
                requestedQuantity = requestedQuantity,
                kisHoldingQty = kisHoldingQty,
                reasonCode = "KIS_HOLDING_QTY_INSUFFICIENT",
            )
        }
        return null
    }

    private fun kisSellQuantityBlocked(
        signal: Signal,
        requestedQuantity: Int,
        kisHoldingQty: Int,
        reasonCode: String,
    ): RiskGatewayResult.Blocked =
        RiskGatewayResult.Blocked(
            signalId = signal.id ?: 0L,
            rule = RiskRule.SELL_QUANTITY_UNAVAILABLE,
            reason = "SELL_QUANTITY_UNAVAILABLE:$reasonCode",
            context =
                mapOf(
                    "source" to "KIS_BALANCE",
                    "code" to signal.code,
                    "side" to signal.side.name,
                    "requestedQty" to requestedQuantity,
                    "kisHoldingQty" to kisHoldingQty,
                    "accountType" to RuntimeModeParser.accountTypeOrMock(magicjarMode).name,
                    "reasonCode" to reasonCode,
                ),
        )

    private fun ensureKisExecutionSyncIfReal(signal: Signal): RiskGatewayResult.Blocked? {
        if (!RuntimeModeParser.isRealMode(magicjarMode)) return null
        val syncService =
            executionBackfillServiceProvider.getIfAvailable()
                ?: return syncBlocked(signal, "SYNC_SERVICE_UNAVAILABLE")

        val result =
            try {
                syncService.syncUserTodayExecutions(signal.userId, AccountType.REAL)
            } catch (e: Exception) {
                log.error("risk KIS execution sync 예외: userId={} signalId={} err={}", signal.userId, signal.id, e.message, e)
                return syncBlocked(signal, "SYNC_EXCEPTION:${e.message ?: "UNKNOWN"}")
            }

        if (result.isComplete) {
            log.info(
                "risk KIS execution sync PASS: userId={} signalId={} fetched={} inserted={} alreadySynced={}",
                signal.userId,
                signal.id,
                result.fetched,
                result.inserted,
                result.alreadySynced,
            )
            return null
        }

        return syncBlocked(signal, result.reasonCode)
    }

    private fun ensureRiskPlanIfBuy(signal: Signal): RiskGatewayResult.Blocked? {
        if (signal.side != SignalSide.BUY) return null
        val lookup =
            strategyLookupPortProvider.getIfAvailable()
                ?: return riskPlanBlocked(signal, "STRATEGY_LOOKUP_UNAVAILABLE")
        val strategy =
            try {
                lookup
                    .findActiveByCodeAndInterval(signal.code, signal.interval)
                    .firstOrNull { it.id == signal.strategyId }
            } catch (e: Exception) {
                log.warn("RiskPlan strategy lookup 실패: userId={} strategyId={} err={}", signal.userId, signal.strategyId, e.message)
                return riskPlanBlocked(signal, "STRATEGY_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}")
            } ?: return riskPlanBlocked(signal, "ACTIVE_STRATEGY_NOT_FOUND")

        val stopLossPct = strategy.definition.fixedStopLoss
        val takeProfitPct = strategy.definition.takeProfitPct
        if (stopLossPct == null || takeProfitPct == null) {
            return riskPlanBlocked(signal, "MISSING_STOP_OR_TAKE_PROFIT")
        }
        if (stopLossPct <= BigDecimal.ZERO || takeProfitPct <= BigDecimal.ZERO) {
            return riskPlanBlocked(signal, "INVALID_STOP_OR_TAKE_PROFIT")
        }
        return null
    }

    private fun riskPlanBlocked(
        signal: Signal,
        reasonCode: String,
    ): RiskGatewayResult.Blocked =
        RiskGatewayResult.Blocked(
            signalId = signal.id ?: 0L,
            rule = RiskRule.RISK_PLAN_REQUIRED,
            reason = "RISK_PLAN_REQUIRED:$reasonCode",
            context =
                mapOf(
                    "code" to signal.code,
                    "side" to signal.side.name,
                    "strategyId" to signal.strategyId,
                    "reasonCode" to reasonCode,
                ),
        )

    private fun syncBlocked(
        signal: Signal,
        reasonCode: String,
    ): RiskGatewayResult.Blocked =
        RiskGatewayResult.Blocked(
            signalId = signal.id ?: 0L,
            rule = RiskRule.KIS_EXECUTION_SYNC_STALE,
            reason = "KIS_EXECUTION_SYNC_STALE:$reasonCode",
            context =
                mapOf(
                    "code" to signal.code,
                    "side" to signal.side.name,
                    "magicjarMode" to magicjarMode,
                    "reasonCode" to reasonCode,
                ),
        )

    private fun handleBlocked(
        signal: Signal,
        blocked: RiskGatewayResult.Blocked,
    ) {
        val nowKst = clock.now()
        val contextJson =
            try {
                objectMapper.writeValueAsString(blocked.context)
            } catch (e: Exception) {
                log.warn("contextJson 직렬화 실패: signalId={} err={}", blocked.signalId, e.message)
                "{}"
            }

        // 1. risk_event INSERT (Phase 2 어댑터 부재 시 skip)
        val savedEvent: RiskEvent? =
            riskEventRepositoryProvider.getIfAvailable()?.let {
                try {
                    it.save(
                        RiskEvent(
                            userId = signal.userId,
                            signalId = blocked.signalId,
                            strategyId = signal.strategyId,
                            rule = blocked.rule,
                            verdict = RiskVerdict.BLOCKED,
                            reason = blocked.reason,
                            contextJson = contextJson,
                            createdAt = nowKst,
                        ),
                    )
                } catch (e: Exception) {
                    log.warn("risk_event save 실패 (skip): signalId={} err={}", blocked.signalId, e.message)
                    null
                }
            }

        // 2. signal.risk_status = BLOCKED 갱신
        signalRiskStatusUpdaterProvider.getIfAvailable()?.let {
            try {
                it.markBlocked(blocked.signalId, blocked.rule, blocked.reason)
            } catch (e: Exception) {
                log.warn("markBlocked 실패 (skip): signalId={} err={}", blocked.signalId, e.message)
            }
        }

        // 3. STOMP fanout (Phase 2 어댑터 부재 시 skip)
        if (savedEvent != null) {
            riskNotifierProvider.getIfAvailable()?.let {
                try {
                    it.notifyViolation(signal.userId, savedEvent)
                } catch (e: Exception) {
                    log.warn("notifyViolation 실패 (skip): signalId={} err={}", blocked.signalId, e.message)
                }
            }
        }

        // 4. risk.violation.v1 publish
        riskViolationKafkaProducer.publish(signal, blocked, savedEvent?.id)

        // 5. AutoPauseGuard — 연속 BLOCKED 임계 도달 검사
        val config =
            riskConfigRepositoryProvider
                .getIfAvailable()
                ?.let { runCatching { it.getOrDefault(signal.userId, nowKst) }.getOrNull() }
        val threshold = config?.autoPauseThreshold ?: 10
        autoPauseGuard.checkAndPauseIfNeeded(signal.strategyId, threshold)
    }

    private fun buildContext(signal: Signal): RiskContext {
        val nowKst = clock.now()
        val realMode = RuntimeModeParser.isRealMode(magicjarMode)
        val configRepo = riskConfigRepositoryProvider.getIfAvailable()
        val config =
            if (configRepo == null) {
                kr.spdaewoon.magicjar.domain.risk.RiskConfig
                    .defaultFor(signal.userId, nowKst)
            } else {
                try {
                    configRepo.getOrDefault(signal.userId, nowKst)
                } catch (e: Exception) {
                    log.warn("RiskConfig getOrDefault 실패 — defaultFor seed (userId={} err={})", signal.userId, e.message)
                    kr.spdaewoon.magicjar.domain.risk.RiskConfig
                        .defaultFor(signal.userId, nowKst)
                }
            }

        val capital =
            try {
                capitalSnapshotProvider.getCapital(signal.userId)
            } catch (e: Exception) {
                if (realMode) {
                    throw RealRiskContextUnavailableException("CAPITAL_UNAVAILABLE:${e.message ?: "UNKNOWN"}")
                }
                log.warn("CapitalSnapshotProvider 실패 (fallback 0): userId={} err={}", signal.userId, e.message)
                BigDecimal.ZERO
            }

        val positionPort = riskPositionPortProvider.getIfAvailable()
        val positions =
            if (positionPort == null) {
                if (realMode) {
                    throw RealRiskContextUnavailableException("POSITION_PORT_UNAVAILABLE")
                }
                emptyList()
            } else {
                try {
                    positionPort.findCurrentByUser(signal.userId)
                } catch (e: Exception) {
                    if (realMode) {
                        throw RealRiskContextUnavailableException("POSITION_UNAVAILABLE:${e.message ?: "UNKNOWN"}")
                    }
                    emptyList()
                }
            }

        val dailyPnl =
            try {
                dailyPnlCalculator.getDailyPnl(signal.userId, nowKst)
            } catch (e: Exception) {
                if (realMode) {
                    throw RealRiskContextUnavailableException("DAILY_PNL_UNAVAILABLE:${e.message ?: "UNKNOWN"}")
                }
                log.warn("DailyPnlCalculator 실패 (fallback 0): userId={} err={}", signal.userId, e.message)
                BigDecimal.ZERO
            }

        val recentMinute = tradeFrequencyGuard.countRecentMinute(signal.strategyId, signal.userId, nowKst)
        val recentDay = tradeFrequencyGuard.countRecentDay(signal.strategyId, signal.userId, nowKst)

        val openOrders =
            orderRequestRepositoryProvider.getIfAvailable()?.let {
                try {
                    it.findOpenByUserId(signal.userId)
                } catch (e: Exception) {
                    if (realMode) {
                        throw RealRiskContextUnavailableException("OPEN_ORDER_UNAVAILABLE:${e.message ?: "UNKNOWN"}")
                    }
                    log.warn("OrderRequestRepository.findOpenByUserId 실패 (fallback empty): userId={} err={}", signal.userId, e.message)
                    emptyList()
                }
            } ?: emptyList()

        val killSwitchOn =
            killSwitchQueryPortProvider.getIfAvailable()?.let {
                try {
                    it.isOn(signal.userId)
                } catch (e: Exception) {
                    log.warn("KillSwitchQueryPort.isOn 실패 (fallback true): userId={} err={}", signal.userId, e.message)
                    true
                }
            } ?: false

        return RiskContext(
            userId = signal.userId,
            config = config,
            currentCapital = capital,
            currentPositions = positions,
            openOrders = openOrders,
            dailyRealizedPnl = dailyPnl,
            recentTradesMinute = recentMinute,
            recentTradesDay = recentDay,
            killSwitchOn = killSwitchOn,
            nowKst = nowKst,
        )
    }

    private fun resolveOrderQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int =
        when (signal.side) {
            SignalSide.BUY -> {
                riskGatewayEvaluator.resolveBuyQuantity(signal, context)
            }
            SignalSide.SELL -> riskGatewayEvaluator.resolveSellQuantity(signal, context)
            SignalSide.HOLD -> 0
        }

    private fun deserialize(value: String): Signal? =
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            val id = (payload["id"] as? Number)?.toLong() ?: return null
            val strategyId = (payload["strategyId"] as? Number)?.toLong() ?: return null
            val userId = payload["userId"]?.toString() ?: return null
            val code = payload["code"]?.toString() ?: return null
            val intervalCode = payload["interval"]?.toString() ?: return null
            val interval =
                CandleInterval.values().find { it.code == intervalCode }
                    ?: return null
            val tsEpoch =
                when (val raw = payload["ts"]) {
                    is Number -> raw.toLong()
                    is String -> raw.toLongOrNull() ?: return null
                    else -> return null
                }
            val sideName = payload["side"]?.toString() ?: return null
            val side = SignalSide.values().find { it.name == sideName } ?: return null
            val strength = (payload["strength"] as? Number)?.toInt() ?: 0
            val reason = payload["reason"]?.toString() ?: ""
            val priceAtSignal =
                when (val raw = payload["priceAtSignal"]) {
                    is Number -> BigDecimal.valueOf(raw.toDouble())
                    is String -> raw.toBigDecimalOrNull() ?: return null
                    else -> BigDecimal.ZERO
                }
            val contextJson = payload["contextJson"]?.toString()

            Signal(
                id = id,
                strategyId = strategyId,
                userId = userId,
                code = code,
                interval = interval,
                ts = Instant.fromEpochSeconds(tsEpoch),
                side = side,
                strength = strength,
                reason = reason,
                priceAtSignal = priceAtSignal,
                contextJson = contextJson,
                createdAt = null,
            )
        } catch (e: Exception) {
            log.error("SignalGeneratedKafkaListener deserialize 실패: err={}", e.message)
            null
        }

    private class RealRiskContextUnavailableException(
        val reasonCode: String,
    ) : RuntimeException(reasonCode)
}
