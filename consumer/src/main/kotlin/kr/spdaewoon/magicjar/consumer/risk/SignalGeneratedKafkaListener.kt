package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.order.AutoExitReservations
import kr.spdaewoon.magicjar.consumer.order.AutoOrderIdempotencyKeys
import kr.spdaewoon.magicjar.consumer.order.KisExecutionBackfillService
import kr.spdaewoon.magicjar.consumer.order.RestingSellOrderCanceller
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.RiskGatewayResult
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.RiskVerdict
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotSelector
import kr.spdaewoon.magicjar.domain.risk.port.PaperCapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskNotifier
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.risk.port.SignalRiskStatusUpdater
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
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
    private val capitalSnapshotProvider: CapitalSnapshotProvider,
    // ADR 0122 §2-C — 페이퍼 가상 자본 provider (페이퍼 비활성 시 부재 가능 → ObjectProvider graceful).
    private val paperCapitalSnapshotProviderProvider: ObjectProvider<PaperCapitalSnapshotProvider>,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    private val riskViolationKafkaProducer: RiskViolationKafkaProducer,
    private val autoPauseGuard: AutoPauseGuard,
    private val riskConfigRepositoryProvider: ObjectProvider<RiskConfigRepository>,
    private val riskEventRepositoryProvider: ObjectProvider<RiskEventRepository>,
    private val riskPositionPortProvider: ObjectProvider<RiskPositionPort>,
    private val strategyPositionPortProvider: ObjectProvider<StrategyPositionPort>,
    private val signalRiskStatusUpdaterProvider: ObjectProvider<SignalRiskStatusUpdater>,
    private val riskNotifierProvider: ObjectProvider<RiskNotifier>,
    private val killSwitchQueryPortProvider: ObjectProvider<KillSwitchQueryPort>,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val strategyLookupPortProvider: ObjectProvider<StrategyLookupPort>,
    private val kisBalanceQueryPortProvider: ObjectProvider<KisBalanceQueryPort>,
    private val executionBackfillServiceProvider: ObjectProvider<KisExecutionBackfillService>,
    // ADR 0114 §3-1 — BUY marketable limit 가격 산정 배선.
    private val entryOrderPriceResolver: EntryOrderPriceResolver,
    // 자본 path hotfix — SELL 발행 전 resting auto-tp 선취소 공용 컴포넌트(watcher와 DRY 공유).
    private val restingSellOrderCanceller: RestingSellOrderCanceller,
    // ADR 0114 §3-1 — 진입 스킵 audit(SIGNAL_SKIPPED_*) 발사 (audit_trail = system_event).
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
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

            val syncBlocked = ensureKisExecutionSync(signal)
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

        val sellableBlocked = ensureKisHoldingIfSell(signal, quantity, context)
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

        // 3. order.intent.v1 publish — T-O 진입.
        //    ADR 0114 §3-1 — BUY는 marketable limit 산정(Enter→LIMIT / Skip→주문 0+audit).
        //    §3-3·§5 — SELL은 청산 trigger별 분기: 손절=MARKET / 익절(TP·PartialExit)=LIMIT(generator가 contextJson에 실음).
        if (signal.side == SignalSide.BUY) {
            publishBuyOrSkip(signal, quantity)
        } else {
            publishSell(signal, quantity)
        }
    }

    /**
     * SELL 청산 OrderIntent 발행 — 가격 유형 분기 (ADR 0114 §3-3·§5 / R2-C2b 계약 마무리).
     *
     * generator([RealtimeSignalGenerator.buildExitContextJson])가 `signal.contextJson`에 실은
     * `exitPriceType`(MARKET 손절 / LIMIT 익절) + `exitLimitPrice`를 읽어 분기한다.
     * - 손절(MARKET)·contextJson 부재(하위호환·1m 외 경로) → MARKET 발행.
     * - 익절(LIMIT) + limitPrice 유효 → LIMIT 발행.
     *
     * ## resting 익절 선주문 선취소 — "먼저 오는 쪽이 이긴다" (자본 path hotfix · ADR 0114 §5 보강)
     * [kr.spdaewoon.magicjar.consumer.order.AutoExitOrderCoordinator]가 BUY 체결 즉시 `auto-tp:` 키로 걸어둔
     * 익절 LIMIT 선주문이 KIS에서 **보유 수량을 잠근다**. 그 상태에서 DSL 매도/손절 SELL을 그대로 발행하면 KIS가
     * "매도가능수량 부족"으로 거부 → 매도 시그널이 무력화된다(과거 주석의 "잔고 가드로 자연 수렴"은 잘못된 가정).
     * 매도 시그널·손절은 **먼저 와서 청산을 의도한 쪽**이므로, 발행 전 같은 종목 resting auto SELL을 **선취소**한다
     * ([RestingSellOrderCanceller] — [kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcher]와 공용 컴포넌트로 DRY).
     * - 수동(MANUAL)/타전략 open SELL 존재 → fail-closed(발행 중단 — 사용자 수동 주문 침범 금지).
     * - KIS ACK 미확정 → 보류(발행 중단, 다음 시그널 재평가).
     * - 취소 성공/open SELL 없음 → 발행 진행.
     *
     * ## 예외 — 자기 자신이 익절 LIMIT 재발행이면 정합 시 skip (coordinator ALREADY_ALIGNED 정합)
     * 이번 SELL 자체가 익절 LIMIT(exitPriceType=LIMIT) 경로면, 동일가·동일수량 `auto-tp:` open SELL이 이미 있으면
     * 취소 없이 **발행 skip**(중복 회피). 다르면 취소 후 재발행.
     */
    private fun publishSell(
        signal: Signal,
        quantity: Int,
    ) {
        val exit = parseExitContext(signal.contextJson)
        val isTakeProfitLimit =
            exit != null && exit.priceType == OrderPriceType.LIMIT && exit.limitPrice != null && exit.limitPrice > BigDecimal.ZERO

        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            // fail-closed — resting SELL 검사 불가 시 SELL 발행 보류(이중 SELL·수량 잠금 충돌 회피).
            log.error(
                "SELL 발행 보류 (fail-closed): OrderRequestRepository 미합류 — resting SELL 검사 불가 signalId={} code={}",
                signal.id,
                signal.code,
            )
            return
        }

        // 익절 LIMIT 재발행 경로 — 동일가·동일수량 auto-tp가 이미 있으면 발행 skip(중복 회피).
        if (isTakeProfitLimit && hasAlignedTakeProfit(orderRepo, signal, quantity, exit!!.limitPrice!!)) {
            log.info(
                "SELL 익절 LIMIT skip (이미 정합 auto-tp open): signalId={} code={} qty={} limitPrice={}",
                signal.id,
                signal.code,
                quantity,
                exit.limitPrice,
            )
            return
        }

        // resting auto SELL(익절 TP 등) 선취소 — 발행 가능해야 진행("먼저 오는 쪽이 이긴다").
        val cancelResult =
            restingSellOrderCanceller.cancelRestingSells(
                orderRepo = orderRepo,
                userId = signal.userId,
                strategyId = signal.strategyId,
                code = signal.code,
            )
        if (!cancelResult.canProceed) {
            log.warn(
                "SELL 발행 보류: resting SELL 선취소 미완료 signalId={} code={} result={}",
                signal.id,
                signal.code,
                cancelResult::class.simpleName,
            )
            return
        }

        if (isTakeProfitLimit) {
            log.info(
                "SELL 익절 LIMIT 발행: signalId={} code={} qty={} limitPrice={}",
                signal.id,
                signal.code,
                quantity,
                exit!!.limitPrice,
            )
            orderIntentKafkaProducer.publish(signal, quantity, OrderPriceType.LIMIT, exit.limitPrice)
            return
        }
        // 손절(MARKET) 또는 exit 정보 부재 — 체결 확실성 우선 MARKET(§3-3).
        orderIntentKafkaProducer.publish(signal, quantity, OrderPriceType.MARKET, null)
    }

    /**
     * 이번 익절 LIMIT 재발행과 동일가·동일수량 `auto-tp:` open SELL이 이미 존재하는가
     * ([kr.spdaewoon.magicjar.consumer.order.AutoExitOrderCoordinator]의 ALREADY_ALIGNED 의미 정합 — 단일 정합 1건만 skip 대상).
     */
    private fun hasAlignedTakeProfit(
        orderRepo: OrderRequestRepository,
        signal: Signal,
        quantity: Int,
        limitPrice: BigDecimal,
    ): Boolean {
        val openTakeProfits =
            orderRepo
                .findOpenByUserId(signal.userId)
                .filter { it.isOpenAutoTakeProfitFor(signal.code, signal.strategyId) }
        return openTakeProfits.size == 1 &&
            openTakeProfits.single().let { it.quantity == quantity && it.limitPrice?.compareTo(limitPrice) == 0 }
    }

    private fun OrderRequest.isOpenAutoTakeProfitFor(
        code: String,
        strategyId: Long,
    ): Boolean =
        this.code == code &&
            this.strategyId == strategyId &&
            side == OrderSide.SELL &&
            source == OrderSource.AUTO &&
            idempotencyKey.startsWith("${AutoOrderIdempotencyKeys.PREFIX_TAKE_PROFIT}:") &&
            status in OPEN_STATUSES

    private data class SellExit(
        val priceType: OrderPriceType,
        val limitPrice: BigDecimal?,
    )

    /** contextJson에서 SELL 청산 가격 라우팅 파싱. 미가용·파싱 실패 시 null(MARKET fallback — 하위호환). */
    private fun parseExitContext(contextJson: String?): SellExit? {
        if (contextJson.isNullOrBlank()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(contextJson, Map::class.java) as Map<String, Any?>
            val priceType =
                map["exitPriceType"]?.toString()?.let { name -> OrderPriceType.values().firstOrNull { it.name == name } }
                    ?: return@runCatching null
            val limitPrice =
                when (val raw = map["exitLimitPrice"]) {
                    is Number -> BigDecimal.valueOf(raw.toDouble())
                    is String -> raw.toBigDecimalOrNull()
                    else -> null
                }
            SellExit(priceType, limitPrice)
        }.onFailure { e ->
            log.warn("SELL exit contextJson 파싱 실패 — MARKET fallback: signalContext err={}", e.message)
        }.getOrNull()
    }

    /**
     * BUY 진입 가격 산정 → MARKET / marketable limit 발행 또는 스킵 audit (ADR 0114 §3-1·§3-2 · opt-in hotfix 2026-06-08).
     *
     * - [EntryOrderDecision.Market]: priceType=MARKET 발행(현행 보존 — marketable-limit 미적용 시그널). 호가 조회 0.
     * - [EntryOrderDecision.Enter]: priceType=LIMIT + limitPrice 발행(매도1호가 상한 이내). 미체결 취소 워커가 entryTimeoutSec로 회수.
     * - [EntryOrderDecision.Skip]: **주문 0** + SIGNAL_SKIPPED_* audit(system_event). 시장가 fallback 절대 금지(§3-1-5, marketable-limit 적용 대상에 한해).
     */
    private fun publishBuyOrSkip(
        signal: Signal,
        quantity: Int,
    ) {
        val strategy = lookupStrategyOrNull(signal)
        when (val decision = entryOrderPriceResolver.resolve(signal, strategy)) {
            is EntryOrderDecision.Market -> {
                log.info(
                    "BUY MARKET 진입 (marketable-limit 미적용 — entry_policy 없음 + limit-enabled=false): signalId={} code={} qty={}",
                    signal.id,
                    signal.code,
                    quantity,
                )
                orderIntentKafkaProducer.publish(signal, quantity, OrderPriceType.MARKET, null)
            }
            is EntryOrderDecision.Enter -> {
                log.info(
                    "BUY marketable limit 진입: signalId={} code={} qty={} limitPrice={} entryTimeoutSec={}",
                    signal.id,
                    signal.code,
                    quantity,
                    decision.limitPrice,
                    decision.entryTimeoutSec,
                )
                orderIntentKafkaProducer.publish(signal, quantity, OrderPriceType.LIMIT, decision.limitPrice)
            }
            is EntryOrderDecision.Skip -> {
                log.info(
                    "BUY 진입 스킵 (주문 0 — 시장가 fallback 없음): signalId={} code={} auditCode={} ask1={} bid1={} quoteAsOf={}",
                    signal.id,
                    signal.code,
                    decision.auditCode,
                    decision.ask1,
                    decision.bid1,
                    decision.quoteAsOf,
                )
                emitEntrySkipAudit(signal, decision)
            }
        }
    }

    /** 진입 스킵 audit — SIGNAL_SKIPPED_* (system_event, category=OTHER, severity=WARN). graceful(emit 실패가 listener thread 안 죽임). */
    private fun emitEntrySkipAudit(
        signal: Signal,
        skip: EntryOrderDecision.Skip,
    ) {
        val logger = systemEventLoggerProvider.getIfAvailable() ?: return
        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.OTHER,
                    severity = SystemEventSeverity.WARN,
                    actor = "SignalGeneratedKafkaListener",
                    action = skip.auditCode,
                    userId = signal.userId,
                    detailJson =
                        objectMapper.writeValueAsString(
                            mapOf(
                                "signalId" to signal.id,
                                "strategyId" to signal.strategyId,
                                "code" to signal.code,
                                "side" to signal.side.name,
                                "priceAtSignal" to signal.priceAtSignal.toPlainString(),
                                "ask1" to skip.ask1?.toPlainString(),
                                "bid1" to skip.bid1?.toPlainString(),
                                "quoteAsOf" to skip.quoteAsOf?.toString(),
                            ),
                        ),
                ),
            )
        }.onFailure { e ->
            log.error("진입 스킵 audit emit 실패: signalId={} auditCode={} err={}", signal.id, skip.auditCode, e.message)
        }
    }

    /**
     * KIS 총보유 기준 매도 가용 수량 가드 (자본 path hotfix #789 후속 — auto-tp 예약 인지).
     *
     * ## 결함 (이 hotfix가 닫는 것)
     * KIS 국내주식은 미체결 SELL 선주문이 보유 수량을 잠근다. [kr.spdaewoon.magicjar.consumer.order.AutoExitOrderCoordinator]가
     * BUY 체결 즉시 건 `auto-tp:` 익절 LIMIT 선주문이 보유 전량을 예약하면 KIS 매도가능수량=0 → DSL 매도 시그널이
     * 매분 `SELL_QUANTITY_UNAVAILABLE`로 차단(플러딩)됐다. 하지만 그 익절 선주문은 [publishSell]의
     * [RestingSellOrderCanceller]가 발행 **직전** 선취소해 풀어주므로, 가드가 미리 막으면 취소 로직에 도달조차 못 한다.
     *
     * ## 보정 (#789 [RiskGatewayEvaluator.reservedSellQuantity]와 동일 출처 [AutoExitReservations] 재사용)
     * KIS 스냅샷 보유 수량([AccountHolding.quantity] = KIS `hldg_qty` 총보유)에서 **취소 불가(수동/타전략) open SELL만**
     * 차감한다. 우리가 선취소할 `auto-tp:`/`auto-sl:` 같은 전략 AUTO 예약은 차감 0(곧 풀려날 분이므로 가용으로 본다).
     * 판정: `총보유 - 수동·타전략예약 >= requestedQuantity` 이면 ALLOW.
     *
     * - 수동(MANUAL)/타전략 SELL 예약 → 그대로 차단 유지(침범 금지).
     * - 진짜 보유 부족(총보유 < 요청) → 정상 차단 유지.
     * - 조회 실패(rate limit 등) → fail-closed 보류(본 hotfix 범위 외 — 빈도는 #808 stampede 완화로 별개 추적).
     */
    private fun ensureKisHoldingIfSell(
        signal: Signal,
        requestedQuantity: Int,
        context: RiskContext,
    ): RiskGatewayResult.Blocked? {
        if (signal.side != SignalSide.SELL) return null
        val port =
            kisBalanceQueryPortProvider.getIfAvailable()
                ?: return kisSellQuantityBlocked(signal, requestedQuantity, 0, 0, "KIS_BALANCE_LOOKUP_UNAVAILABLE")
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
                    foreignReservedSellQty = 0,
                    reasonCode = "KIS_BALANCE_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}",
                )
            }
        // KIS `hldg_qty` = 총보유(주문가능수량 아님). 우리가 선취소할 auto-tp/auto-sl은 가용으로 보고,
        // 수동/타전략 open SELL 예약분만 총보유에서 차감 — #789 reservedSellQuantity와 동일 출처(AutoExitReservations).
        val kisHoldingQty = snapshot.holdings.firstOrNull { it.code == signal.code }?.quantity ?: 0
        val foreignReservedSellQty =
            AutoExitReservations.foreignReservedSellQuantity(
                openOrders = context.openOrders,
                code = signal.code,
                strategyId = signal.strategyId,
            )
        val availableSellQty = (kisHoldingQty - foreignReservedSellQty).coerceAtLeast(0)
        if (availableSellQty < requestedQuantity) {
            return kisSellQuantityBlocked(
                signal = signal,
                requestedQuantity = requestedQuantity,
                kisHoldingQty = kisHoldingQty,
                foreignReservedSellQty = foreignReservedSellQty,
                reasonCode = "KIS_HOLDING_QTY_INSUFFICIENT",
            )
        }
        return null
    }

    private fun kisSellQuantityBlocked(
        signal: Signal,
        requestedQuantity: Int,
        kisHoldingQty: Int,
        foreignReservedSellQty: Int,
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
                    "foreignReservedSellQty" to foreignReservedSellQty,
                    "availableSellQty" to (kisHoldingQty - foreignReservedSellQty).coerceAtLeast(0),
                    "accountType" to RuntimeModeParser.accountTypeOrMock(magicjarMode).name,
                    "reasonCode" to reasonCode,
                ),
        )

    /**
     * 주문 발행 전 KIS 체결 동기화 가드 (자본 정합 hotfix 2026-06-08 — REAL·MOCK 동일화).
     *
     * ## 결함 (이 hotfix가 닫는 것)
     * 과거 `ensureKisExecutionSyncIfReal`는 `if (!isRealMode) return null`로 **REAL 모드에서만** 체결 backfill을
     * 발사했다. MOCK(VPS 페이퍼) 모드에서는 KIS WS 체결통보 누락 시 `KisExecutionBackfillService`의 보정이
     * 돌지 않아 ACK 주문이 영구 정체(증상: 주문 144 — 005930 BUY 2주 AUTO MOCK이 KIS_ACK 후 체결통보 누락 →
     * 30분+ ACK 정체)했다. 체결 보정 같은 정합은 실돈 여부와 무관하므로 두 모드 모두 동일하게 돌려야 한다
     * (메모리 `feedback_kis_key_realonly_for_data` — 모드 분기는 실제 주문 액션에만).
     *
     * ## 보정 (정합은 동일, account type만 모드에서 파생)
     * - REAL-only 게이트 제거 → 두 모드 모두 실행.
     * - `AccountType.REAL` 하드코딩 → [RuntimeModeParser.accountTypeOrMock] (MOCK 모드 → VPS 페이퍼 계좌 체결 조회,
     *   REAL 모드 → 실계좌). [ensureKisHoldingIfSell]와 동일 출처 재사용(DRY).
     * - KIS 조회 endpoint(inquire-daily-ccld) tr_id·base-url 분기는 [KisOrderInquireCcldHttpAdapter]가
     *   accountType 기반으로 이미 처리(REAL=TTTC0081R/prod, MOCK=VTTC0081R/vps — raw WebFetch LOCK).
     * - 실제 주문 발행/취소 액션의 MOCK/REAL 분기(실돈 게이트)는 무변경 — 본 함수는 "조회·상태 보정"만.
     */
    private fun ensureKisExecutionSync(signal: Signal): RiskGatewayResult.Blocked? {
        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val syncService =
            executionBackfillServiceProvider.getIfAvailable()
                ?: return syncBlocked(signal, "SYNC_SERVICE_UNAVAILABLE")

        val result =
            try {
                syncService.syncUserTodayExecutions(signal.userId, accountType)
            } catch (e: Exception) {
                log.error("risk KIS execution sync 예외: userId={} signalId={} err={}", signal.userId, signal.id, e.message, e)
                return syncBlocked(signal, "SYNC_EXCEPTION:${e.message ?: "UNKNOWN"}")
            }

        if (result.isComplete) {
            log.info(
                "risk KIS execution sync PASS: userId={} signalId={} accountType={} fetched={} inserted={} alreadySynced={}",
                signal.userId,
                signal.id,
                accountType.name,
                result.fetched,
                result.inserted,
                result.alreadySynced,
            )
            return null
        }

        return syncBlocked(signal, result.reasonCode)
    }

    /**
     * 시그널 발행 전략 단건 조회(BUY entryPolicy·RiskPlan 검증 공용). 조회 실패·미발견 시 null.
     * ADR 0114 — [publishBuyOrSkip]의 entryPolicy override 입력으로도 재사용(DRY — 진보 페르소나).
     */
    private fun lookupStrategyOrNull(signal: Signal) =
        strategyLookupPortProvider.getIfAvailable()?.let { lookup ->
            runCatching {
                lookup
                    .findActiveByCodeAndInterval(signal.code, signal.interval)
                    .firstOrNull { it.id == signal.strategyId }
            }.onFailure { e ->
                log.warn("strategy lookup 실패: userId={} strategyId={} err={}", signal.userId, signal.strategyId, e.message)
            }.getOrNull()
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

        if (!blocked.countsTowardStrategyAutoPause()) {
            log.debug(
                "AutoPauseGuard skip: rule={} userId={} strategyId={} signalId={}",
                blocked.rule,
                signal.userId,
                signal.strategyId,
                blocked.signalId,
            )
            return
        }

        // 5. AutoPauseGuard — strategy-local 연속 BLOCKED 임계 도달 검사
        val configRepo = riskConfigRepositoryProvider.getIfAvailable()
        if (configRepo == null) {
            // 2026-06-10 incident: 빈 부재 시 autoPauseThreshold가 사용자 DB값 대신 하드코드 10으로 고정.
            // buildContext와 동일 silent 결함 — ERROR로 가시화.
            log.error(
                "RiskConfigRepository 빈 부재 — autoPauseThreshold 하드코드 10 fallback (userId={}, strategyId={}). " +
                    "사용자 DB risk_config 미반영 — 배선 결함.",
                signal.userId,
                signal.strategyId,
            )
        }
        val config = configRepo?.let { runCatching { it.getOrDefault(signal.userId, nowKst) }.getOrNull() }
        val threshold = config?.autoPauseThreshold ?: 10
        autoPauseGuard.checkAndPauseIfNeeded(signal.strategyId, threshold)
    }

    private fun RiskGatewayResult.Blocked.countsTowardStrategyAutoPause(): Boolean =
        when (rule) {
            RiskRule.KILL_SWITCH_ON -> false
            RiskRule.SELL_QUANTITY_UNAVAILABLE -> {
                val reasonCode = context["reasonCode"]?.toString().orEmpty()
                !(reasonCode.startsWith("KIS_BALANCE_LOOKUP_FAILED") || reasonCode == "KIS_BALANCE_LOOKUP_UNAVAILABLE")
            }
            else -> true
        }

    private fun buildContext(signal: Signal): RiskContext {
        val nowKst = clock.now()
        val realMode = RuntimeModeParser.isRealMode(magicjarMode)

        // ADR 0122 §2-C — 페이퍼(가상 자본·MOCK) 전략 식별. origin=PAPER_DISCOVERY 면 RiskConfig 키 파생(`<userId>:paper`)
        // + 가상 자본 provider 선택. 비페이퍼(USER_DSL·legacy null)는 기존 경로와 byte 동일(zero-regression).
        // 조회 실패·미발견 시 isPaper=false fallback(실 경로 — 보수적 default, 실 잔고 오염 방향 아님).
        val isPaper = lookupStrategyOrNull(signal)?.isPaperDiscovery() == true
        val riskConfigKey = if (isPaper) "${signal.userId}:paper" else signal.userId

        val configRepo = riskConfigRepositoryProvider.getIfAvailable()
        val config =
            if (configRepo == null) {
                // 2026-06-10 incident: RiskConfigRepository 빈이 consumer ApplicationContext에 부재하면
                // 사용자 DB risk_config(예: 5M/70%)를 무시하고 defaultFor(1M/10%)만 평가 → 설정을 좁힐수록
                // 실제로는 더 느슨하게 거래되는 역방향 자본 위험. 이전엔 silent 진행이었으므로 가시화.
                if (realMode) {
                    log.error(
                        "RiskConfigRepository 빈 부재 — REAL 모드 fail-fast (riskConfigKey={}). " +
                            "persistence-shared scan 경로/빈 배선 확인 필요.",
                        riskConfigKey,
                    )
                    throw RealRiskContextUnavailableException("RISK_CONFIG_REPO_UNAVAILABLE")
                }
                log.error(
                    "RiskConfigRepository 빈 부재 — defaultFor(1M/10%) fallback (riskConfigKey={}, MOCK). " +
                        "사용자 DB risk_config 미반영 — 배선 결함. persistence-shared 빈 합류 확인 필요.",
                    riskConfigKey,
                )
                kr.spdaewoon.magicjar.domain.risk.RiskConfig
                    .defaultFor(riskConfigKey, nowKst)
            } else {
                try {
                    configRepo.getOrDefault(riskConfigKey, nowKst)
                } catch (e: Exception) {
                    if (realMode) {
                        throw RealRiskContextUnavailableException("RISK_CONFIG_UNAVAILABLE:${e.message ?: "UNKNOWN"}")
                    }
                    log.warn("RiskConfig getOrDefault 실패 — defaultFor seed (riskConfigKey={} err={})", riskConfigKey, e.message)
                    kr.spdaewoon.magicjar.domain.risk.RiskConfig
                        .defaultFor(riskConfigKey, nowKst)
                }
            }

        // ADR 0122 §2-C — capital 분기: 페이퍼면 가상 자본(PaperCapitalSnapshotProvider), 아니면 실 KIS 잔고.
        // CapitalSnapshotSelector.forStrategy 가 fail-fast(페이퍼인데 provider 미배선 → PaperCapitalProviderUnavailableException)를
        // 캡슐화 — 실 잔고 silent fallback 금지(헌법 §11 · spec §1-3 격리). 비페이퍼는 realProvider byte 동일.
        val capitalSelector =
            CapitalSnapshotSelector.forStrategy(
                realProvider = capitalSnapshotProvider,
                paperProvider = paperCapitalSnapshotProviderProvider.getIfAvailable(),
            )
        val capital =
            try {
                capitalSelector.capitalFor(signal.userId, isPaper)
            } catch (e: Exception) {
                if (realMode && !isPaper) {
                    throw RealRiskContextUnavailableException("CAPITAL_UNAVAILABLE:${e.message ?: "UNKNOWN"}")
                }
                // 페이퍼 provider 미배선(fail-fast) 또는 MOCK 실패 → capital 0 (POSITION_LIMIT 으로 BUY 차단 fail-safe).
                log.warn(
                    "Capital 산출 실패 (fallback 0): userId={} isPaper={} err={}",
                    signal.userId,
                    isPaper,
                    e.message,
                )
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
            } ?: run {
                log.error("KillSwitchQueryPort 미합류 (fallback true): userId={} signalId={}", signal.userId, signal.id)
                true
            }

        // 전략별 포지션 귀속 (ADR 0110 §2-4) — SELL 시그널에 한해 전략 lot snapshot 조회.
        // 행 부재(분할매수 미사용·과거 보유) 또는 port/조회 실패 → null 유지 → user+code 합산 fallback(하위호환).
        val strategyPosition =
            if (signal.side == SignalSide.SELL && signal.strategyId != 0L) {
                strategyPositionPortProvider.ifAvailable
                    ?.runCatching { findByUserStrategyCode(signal.userId, signal.strategyId, signal.code) }
                    ?.onFailure { e ->
                        log.warn(
                            "StrategyPositionPort 조회 실패 — user+code fallback: userId={} strategyId={} code={} err={}",
                            signal.userId,
                            signal.strategyId,
                            signal.code,
                            e.message,
                        )
                    }?.getOrNull()
            } else {
                null
            }

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
            strategyPosition = strategyPosition,
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

    private companion object {
        val OPEN_STATUSES =
            setOf(OrderStatus.REQUESTED, OrderStatus.ACK, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_RECONCILE)
    }
}
