package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.AutopilotPlannedLotProjection
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanSerde
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus
import kr.spdaewoon.magicjar.strategy.MinHoldGuard
import kr.spdaewoon.magicjar.strategy.TradePlanEvaluator
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 계획 기반 진입(Planned Entry) **PlanExecutor** — ADR 0130 L2 (열린 lot 의 trade_plan 보장 실행 · R2 핵심 신규).
 *
 * ## 책임 (한 줄 원칙 — autopilot-trade-lifecycle.md §1)
 * > "산 전략만 자기 lot 을 청산한다. 진입 시 박제한 계획(trade_plan)을 시스템이 보장 실행한다."
 *
 * owner=AUTOPILOT 으로 박제된 [TradePlan] 을 가진 열린 lot(strategy_position) 을 매 tick 평가해, 계획 청산 사유
 * (TP/SL/trailing/maxHold/exitConditions)가 충족되면 **그 lot 한정** lot-scoped MARKET SELL 을 발행하고
 * plan.status=CLOSING·exitReason 을 기록한다. 전략 DSL 이 SELL 을 안 내도 계획대로의 청산을 보장한다(L2 보장 실행).
 *
 * ## 평가 두뇌는 순수 함수 (헥사고날 LOCK)
 * 청산 판정(우선순위 SL>MAX_HOLD>exitConditions>TP>TRAIL)은 strategy-engine 순수 객체 [TradePlanEvaluator]가 한다
 * ([MinHoldGuard]·[PositionStopLossTickWatcher.stopPriceOf] 패턴 정합). 본 컴포넌트는 tick 구독·plan-index 캐시·
 * 주문 발행·plan.status write 같은 부수효과(Kafka·DB)만 책임진다 → 평가 로직은 단위 테스트로 엄밀 증명.
 *
 * ## 성능 — 매 tick DB 0 ([PositionStopLossTickWatcher]/[HoldingPositionIndex] 패턴 정합)
 * - tick hot path 는 [HoldingPositionIndex.heldBy] 로 1차 게이트 — 보유 0 종목 tick 은 즉시 return(DB 조회 0).
 * - 평가 대상 lot(owner=AUTOPILOT + trade_plan 박제)은 [planIndex] 인메모리 캐시(주기 refresh)에서 hit.
 * - 트레일링 고점(highWatermark)은 lot 단위로 [highWatermarks] 에 보관해 [TradePlanEvaluator]에 주입(순수성 보존).
 *
 * ## 게이트·격리 (zero-regression LOCK — ADR 0130 §4)
 * - **게이트** `magicjar.autopilot.planned-entry.enabled` (default false). off → 구독·refresh·평가 전부 no-op = 행동 변화 0.
 * - **owner=AUTOPILOT 한정** — plan-index 쿼리([StrategyPositionJpaRepository.findAutopilotPlannedHolding])가
 *   `s.owner = 'AUTOPILOT'` + `trade_plan IS NOT NULL` 로 USER/SYSTEM lot 을 구조적으로 제외(2중 격리).
 *
 * ## DSL 청산 병행 dedup (ADR 0130 §6-3)
 * 같은 lot 을 DSL SELL 과 PlanExecutor 가 동시에 청산하려 하면, 발행 직전 [RestingSellOrderCanceller] open SELL 가드 +
 * `auto-lq:` 멱등 키(OrderRouter `idem:order:{key}` 흡수) + 인메모리 cooldown 3중으로 이중 SELL 을 막는다("먼저 오는 쪽이 이긴다").
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4 정합)
 */
@Component
class PlanExecutor(
    private val objectMapper: ObjectMapper,
    private val holdingPositionIndex: HoldingPositionIndex,
    private val strategyPositionRepository: StrategyPositionJpaRepository,
    private val strategyPositionPortProvider: ObjectProvider<StrategyPositionPort>,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val restingSellOrderCanceller: RestingSellOrderCanceller,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    @Value("\${magicjar.autopilot.planned-entry.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${magicjar.autopilot.planned-entry.tick-cooldown-seconds:30}")
    private val tickCooldownSeconds: Long = 30,
) {
    private val log = LoggerFactory.getLogger(PlanExecutor::class.java)

    /** code → 평가 대상 lot 스냅샷. 게이트 off 면 비어 있음(refresh no-op). */
    private val planIndex = ConcurrentHashMap<String, List<PlannedLot>>()

    /** lot 키(user:strategy:code) → 진입 후 관측 최고가(트레일링 고점). 전량 청산·refresh 부재 시 정리. */
    private val highWatermarks = ConcurrentHashMap<String, BigDecimal>()

    /** lot 키 → 발행 cooldown(같은 lot 상태로 임계 내 재발행 차단). */
    private val intentCooldown = ConcurrentHashMap<String, FiredIntent>()

    /**
     * 평가 대상 lot 스냅샷 — owner=AUTOPILOT + 보유 + trade_plan 박제 lot.
     *
     * @param plan 역직렬화된 [TradePlan]. status≠OPEN(이미 CLOSING/CLOSED)이면 평가에서 자연 skip([TradePlanEvaluator]).
     * @param openedAtEpoch 진입(박제) 시각 epoch — MAX_HOLD 경과 봉 산출 base.
     * @param interval plan.entry.bar 코드 파싱 결과(없으면 1m default — 경과 봉 산정 단위).
     */
    data class PlannedLot(
        val userId: String,
        val strategyId: Long,
        val code: String,
        val quantity: Int,
        val avgPrice: BigDecimal,
        val plan: TradePlan,
        val openedAtEpoch: Long,
        val updatedAtEpoch: Long,
        val interval: CandleInterval,
    )

    /**
     * plan-index 주기 refresh — owner=AUTOPILOT 박제 lot 을 인메모리에 채운다(매 tick DB 0).
     * 게이트 off 면 no-op + 인덱스 비움(행동 변화 0). [HoldingPositionIndex.refresh] 60초 패턴 정합.
     */
    @Scheduled(
        fixedDelayString = "\${magicjar.autopilot.planned-entry.index-refresh-ms:30000}",
        initialDelay = 15_000,
    )
    fun refresh() {
        if (!enabled) {
            if (planIndex.isNotEmpty()) planIndex.clear()
            if (highWatermarks.isNotEmpty()) highWatermarks.clear()
            return
        }
        val rows =
            runCatching { strategyPositionRepository.findAutopilotPlannedHolding() }
                .onFailure { e -> log.warn("PlanExecutor refresh 실패(기존 인덱스 유지): err={}", e.message) }
                .getOrNull() ?: return

        val next = HashMap<String, MutableList<PlannedLot>>()
        val liveKeys = HashSet<String>()
        for (row in rows) {
            val lot = toPlannedLot(row) ?: continue
            next.getOrPut(lot.code) { mutableListOf() }.add(lot)
            liveKeys.add(lotKey(lot))
        }
        planIndex.keys.retainAll(next.keys)
        next.forEach { (code, lots) -> planIndex[code] = lots }
        // 사라진 lot(전량 청산·게이트 변경)의 고점·cooldown 정리(메모리 누수 방지).
        highWatermarks.keys.retainAll(liveKeys)
        intentCooldown.keys.retainAll(liveKeys)
        log.debug("PlanExecutor refresh: codes={} lots={}", next.size, next.values.sumOf { it.size })
    }

    @KafkaListener(topics = [KafkaTopics.Market.TICK], groupId = GROUP)
    fun onTick(record: ConsumerRecord<String, String>) {
        if (!enabled) return
        val tick = parse(record) ?: return
        if (tick.stale || tick.price <= BigDecimal.ZERO) return
        evaluateLots(tick.code, tick.price)
    }

    private fun evaluateLots(
        code: String,
        price: BigDecimal,
    ) {
        // ★ tick hot path 1차 게이트 — 보유 0 종목은 즉시 종료(DB 0, StopLoss watcher 정합).
        if (holdingPositionIndex.heldBy(code).isEmpty()) return
        val lots = planIndex[code] ?: return
        if (lots.isEmpty()) return

        lots.forEach { lot ->
            runCatching { evaluate(lot, price) }
                .onFailure { e ->
                    log.warn(
                        "PlanExecutor lot 평가 실패(다음 tick 재평가): userId={} strategyId={} code={} err={}",
                        lot.userId,
                        lot.strategyId,
                        lot.code,
                        e.message,
                    )
                }
        }
    }

    internal fun evaluate(
        lot: PlannedLot,
        tickPrice: BigDecimal,
    ) {
        // 트레일링 고점 갱신(상승만) — 평가 전에 먼저 반영해 같은 tick 에서 고점 대비 하락도 즉시 판정.
        val key = lotKey(lot)
        val high = highWatermarks.merge(key, tickPrice) { prev, now -> if (now > prev) now else prev } ?: tickPrice

        val state =
            TradePlanEvaluator.PlanEvalState(
                price = tickPrice,
                avgPrice = lot.avgPrice,
                elapsedBars = elapsedBars(lot, tickPrice),
                highWatermark = high,
            )
        val decision = TradePlanEvaluator.evaluate(lot.plan, state) ?: return

        // cooldown — 같은 lot 상태로 임계 내 이미 발행했으면 skip(폭주 차단·StopLoss watcher 패턴).
        if (isCoolingDown(lot)) {
            log.debug(
                "PlanExecutor cooldown skip: userId={} strategyId={} code={} reason={}",
                lot.userId,
                lot.strategyId,
                lot.code,
                decision.reason,
            )
            return
        }

        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.error(
                "PlanExecutor fail-closed: OrderRequestRepository 미합류 — 계획 청산 보류 userId={} strategyId={} code={}",
                lot.userId,
                lot.strategyId,
                lot.code,
            )
            return
        }

        // 같은 lot 의 open SELL(자기 auto-lq 제외) 선취소 — 이중 SELL 회피(RestingSellOrderCanceller 공용).
        val cancelResult =
            restingSellOrderCanceller.cancelRestingSells(
                orderRepo = orderRepo,
                userId = lot.userId,
                strategyId = lot.strategyId,
                code = lot.code,
                excludeIdempotencyPrefix = AutoOrderIdempotencyKeys.PREFIX_AUTOPILOT_LIQUIDATION,
            )
        if (!cancelResult.canProceed) return

        val idempotencyKey =
            AutoOrderIdempotencyKeys.autopilotLiquidation(
                lot.userId,
                lot.strategyId,
                lot.code,
                lot.quantity,
                decision.reason.name,
                lot.updatedAtEpoch,
            )

        orderIntentKafkaProducer.publishExitIntent(
            userId = lot.userId,
            strategyId = lot.strategyId,
            code = lot.code,
            intervalCode = lot.interval.code,
            side = OrderSide.SELL,
            priceType = OrderPriceType.MARKET,
            quantity = lot.quantity,
            limitPrice = null,
            priceAtSignal = tickPrice,
            idempotencyKey = idempotencyKey,
            orderTrigger = OrderTrigger.AUTO_EXIT,
            triggerRefId = lot.strategyId,
        )
        intentCooldown[key] = FiredIntent(Instant.now().epochSecond, stateSignatureOf(lot))
        markPlanClosing(lot, decision.reason)
        log.warn(
            "PlanExecutor MARKET intent (계획 청산 보장 실행): userId={} strategyId={} code={} reason={} trigger={} qty={} tick={} idemKey={}",
            lot.userId,
            lot.strategyId,
            lot.code,
            decision.reason,
            decision.triggerPrice.toPlainString(),
            lot.quantity,
            tickPrice.toPlainString(),
            idempotencyKey,
        )
    }

    /**
     * plan.status=CLOSING + exitReason 기록 — 청산 발행 직후 lot 상태 전이(ADR 0130 §2 status).
     * 전량 청산 체결(quantity→0) 시 R1 체결 listener 가 trade_plan 을 reset 한다(여기선 CLOSING 까지).
     * write 실패는 권위 흐름(주문 intent 는 이미 발행됨) 무손상으로 흡수(2차 ledger 격리 — plan 박제 정합).
     */
    private fun markPlanClosing(
        lot: PlannedLot,
        reason: TradePlanEvaluator.ExitReason,
    ) {
        val port = strategyPositionPortProvider.ifAvailable ?: return
        runCatching {
            val current = port.findByUserStrategyCode(lot.userId, lot.strategyId, lot.code) ?: return
            val plan = current.tradePlan ?: return
            if (plan.status != TradePlanStatus.OPEN) return // 이미 전이됨(멱등)
            port.upsert(
                current.copy(
                    tradePlan = plan.copy(status = TradePlanStatus.CLOSING, exitReason = reason.name),
                    updatedAt = current.updatedAt,
                ),
            )
        }.onFailure { e ->
            log.warn(
                "PlanExecutor plan.status=CLOSING write 실패(권위 흐름 무손상): userId={} strategyId={} code={} err={}",
                lot.userId,
                lot.strategyId,
                lot.code,
                e.message,
            )
        }
    }

    /** 진입 후 경과 봉 수 — (now − openedAt) / interval 초. MAX_HOLD 판정 입력. */
    private fun elapsedBars(
        lot: PlannedLot,
        @Suppress("UNUSED_PARAMETER") tickPrice: BigDecimal,
    ): Int {
        val intervalSeconds = MinHoldGuard.intervalSeconds(lot.interval)
        if (intervalSeconds <= 0) return 0
        val elapsed = Instant.now().epochSecond - lot.openedAtEpoch
        if (elapsed <= 0) return 0
        return (elapsed / intervalSeconds).toInt()
    }

    private fun isCoolingDown(lot: PlannedLot): Boolean {
        val fired = intentCooldown[lotKey(lot)] ?: return false
        if (fired.stateSignature != stateSignatureOf(lot)) return false
        return Instant.now().epochSecond - fired.firedAtEpoch < tickCooldownSeconds
    }

    private fun toPlannedLot(row: AutopilotPlannedLotProjection): PlannedLot? {
        val plan = deserializePlan(row.getTradePlanJson()) ?: return null
        if (row.getQuantity() <= 0 || row.getAvgPrice() <= BigDecimal.ZERO) return null
        val interval =
            plan.entry.bar
                ?.let { runCatching { CandleInterval.fromCode(it) }.getOrNull() }
                ?: CandleInterval.ONE_MIN
        return PlannedLot(
            userId = row.getUserId(),
            strategyId = row.getStrategyId(),
            code = row.getCode(),
            quantity = row.getQuantity(),
            avgPrice = row.getAvgPrice(),
            plan = plan,
            openedAtEpoch = row.getOpenedAt().epochSecond,
            updatedAtEpoch = row.getUpdatedAt().epochSecond,
            interval = interval,
        )
    }

    private fun deserializePlan(json: String?): TradePlan? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
            TradePlanSerde.fromMap(map)
        }.getOrNull()
    }

    private fun parse(record: ConsumerRecord<String, String>): TickPayload? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            TickPayload(
                code = raw["code"]?.toString() ?: return null,
                price = decimalOrNull(raw["price"]) ?: return null,
                stale = boolOrFalse(raw["stale"]),
            )
        } catch (e: Exception) {
            log.warn("PlanExecutor tick parse skip: offset={} err={}", record.offset(), e.message)
            null
        }
    }

    private fun decimalOrNull(raw: Any?): BigDecimal? =
        when (raw) {
            is Number -> BigDecimal.valueOf(raw.toDouble())
            is String -> raw.toBigDecimalOrNull()
            else -> null
        }

    private fun boolOrFalse(raw: Any?): Boolean =
        when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }

    private fun lotKey(lot: PlannedLot): String = "${lot.userId}:${lot.strategyId}:${lot.code}"

    private fun stateSignatureOf(lot: PlannedLot): String = "${lot.quantity}:${lot.updatedAtEpoch}"

    /** 테스트 전용 — plan-index 직접 주입(DB 우회). */
    internal fun replaceIndexForTest(snapshot: Map<String, List<PlannedLot>>) {
        planIndex.clear()
        planIndex.putAll(snapshot)
    }

    private data class TickPayload(
        val code: String,
        val price: BigDecimal,
        val stale: Boolean,
    )

    private data class FiredIntent(
        val firedAtEpoch: Long,
        val stateSignature: String,
    )

    companion object {
        const val GROUP = "magicjar-consumer-plan-executor"
    }
}
