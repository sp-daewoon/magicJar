package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.AutopilotPlannedLotProjection
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanSerde
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStopType
import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineGeometry
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlinePricePort
import kr.spdaewoon.magicjar.strategy.MinHoldGuard
import kr.spdaewoon.magicjar.strategy.TradePlanEvaluator
import kr.spdaewoon.magicjar.strategy.TrendlineStopRestamp
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
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
    // 감사 §6 P2-3 · 부록 A-6 — MAX_HOLD elapsedBars 를 거래시간(정규장 09:00~15:30 KST) 기준으로 산출.
    //   영업일 판정 권위 = MarketSessionResolver(RealtimeSignalGenerator 와 동일 출처 — 캘린더 이중 구현 0).
    //   bean 부재(일부 테스트 컨텍스트) 시 null → 벽시계 fallback(기존 동작).
    private val marketSessionResolver: MarketSessionResolver? = null,
    // 감사 §6 P2-4 — 트레일링 고점(highWatermark) trade_plan JSONB 영속 write 스로틀(초).
    @Value("\${magicjar.autopilot.planned-entry.high-watermark-persist-interval-seconds:60}")
    private val highWatermarkPersistIntervalSeconds: Long = 60,
    // ADR 0140 §3-4 R2b — 추세선 손절 봉 경계 재박제용 선 anchor 조회 port(persistence-shared 합류·optional).
    //   bean 부재(일부 테스트 컨텍스트) 시 재박제 skip + ERROR 로그(§11-2 silent 금지). 재박제 미수행은 마지막 박제값 보호 유지.
    private val trendlinePricePortProvider: ObjectProvider<TrendlinePricePort>? = null,
) {
    private val log = LoggerFactory.getLogger(PlanExecutor::class.java)

    /** code → 평가 대상 lot 스냅샷. 게이트 off 면 비어 있음(refresh no-op). */
    private val planIndex = ConcurrentHashMap<String, List<PlannedLot>>()

    /** lot 키(user:strategy:code) → 진입 후 관측 최고가(트레일링 고점). 전량 청산·refresh 부재 시 정리. */
    private val highWatermarks = ConcurrentHashMap<String, BigDecimal>()

    /** lot 키 → 발행 cooldown(같은 lot 상태로 임계 내 재발행 차단). */
    private val intentCooldown = ConcurrentHashMap<String, FiredIntent>()

    /** lot 키 → 마지막 영속 고점(값 + 시각) — 감사 §6 P2-4 highWatermark write 스로틀 상태. */
    private val highWatermarkPersists = ConcurrentHashMap<String, PersistedHigh>()

    /** stale tick WARN 1회 가드(code 단위 — 감사 §6 P2-4). fresh tick 복귀 시 해제(다음 stale 에피소드에 재경고). */
    private val staleWarnedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
            // 감사 §6 P2-4 — 재기동/refresh 시 trade_plan 에 영속된 트레일링 고점을 rehydrate(인메모리 고점과 max 병합).
            //   종전에는 재기동마다 고점이 리셋돼 트레일링이 재무장 대기 상태로 되돌아갔다.
            lot.plan.trailing?.highWatermark?.let { hw ->
                highWatermarks.merge(lotKey(lot), hw) { prev, now -> if (now > prev) now else prev }
            }
        }
        planIndex.keys.retainAll(next.keys)
        next.forEach { (code, lots) -> planIndex[code] = lots }
        // 사라진 lot(전량 청산·게이트 변경)의 고점·cooldown 정리(메모리 누수 방지).
        highWatermarks.keys.retainAll(liveKeys)
        intentCooldown.keys.retainAll(liveKeys)
        highWatermarkPersists.keys.retainAll(liveKeys)
        log.debug("PlanExecutor refresh: codes={} lots={}", next.size, next.values.sumOf { it.size })
    }

    @KafkaListener(topics = [KafkaTopics.Market.TICK], groupId = GROUP)
    fun onTick(record: ConsumerRecord<String, String>) {
        if (!enabled) return
        val tick = parse(record) ?: return
        if (tick.price <= BigDecimal.ZERO) return
        if (tick.stale) {
            // 감사 §6 P2-4 — stale tick 전면 무시로 degraded feed 에서 TP/TRAIL/MAX_HOLD 평가가 조용히 정지하던 것을
            //   최소 가시화한다(code 당 stale 에피소드 1회 WARN). SL 은 PositionStopLossTickWatcher 의 fallback 가격
            //   경로가 별도 커버한다. plan 평가의 fallback 가격 직결(전면 개편)은 변경 폭이 커 후속 트랙으로 보류.
            if (planIndex.containsKey(tick.code) && staleWarnedCodes.add(tick.code)) {
                log.warn("PlanExecutor plan 평가 tick stale — TP/TRAIL/MAX_HOLD 평가 지연: code={}", tick.code)
            }
            return
        }
        staleWarnedCodes.remove(tick.code)
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
        // 감사 §6 P2-4 — 고점 영속(스로틀 write · 재기동 rehydrate 용). 실패는 인메모리 고점 무손상 흡수.
        persistHighWatermarkIfDue(lot, high)

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

    /**
     * 추세선 손절 봉 경계 재박제 — ADR 0140 §3-4 R2b (선-기반 동적 SL · [TrendlineStopBoundaryListener] 진입점).
     *
     * ## 왜 봉 경계인가 (ADR 0140 §3-4 보수안 LOCK)
     * 기울어진 추세선은 시간에 따라 기준 가격이 변한다. tick 수준 선형보간(매 tick 선 가격 재계산 즉시 손절)은 후속
     * 과제로 명시 보류하고(§4·§5), **봉 경계(BoundaryClosed)마다** 선형보간 가격을 [TradePlan.stop] 의 slPrice 로 재박제한다.
     * tick 평가([evaluate] → [TradePlanEvaluator.stopPriceOf])는 변경 없이 **박제된 slPrice 상수**만 보므로, 기존 손절 구조·
     * 우선순위(SL>MAX_HOLD>exitConditions>TP>TRAIL)는 그대로 재사용된다. 재박제 산식·안전 가드는 strategy-engine
     * 순수 함수 [TrendlineStopRestamp] 가 격리(단위 테스트로 상향/하향/가드/부재 증명).
     *
     * ## 대상 격리 (zero-regression)
     * planIndex(owner=AUTOPILOT + trade_plan 박제 lot)에서 **`interval` 이 이 봉 경계와 일치**하고 손절이
     * `type=TRENDLINE`+trendlineId 를 참조하는 lot 만 대상. FIXED/ATR 손절·타 interval lot 은 무영향(선-기반 아님).
     * 게이트 off([enabled]==false) 면 planIndex 가 비어 자연 no-op.
     *
     * ## 갱신 반영 (in-memory + DB 이중)
     * 재박제 값은 (1) DB trade_plan JSONB 에 upsert 해 재기동/refresh rehydrate 하고, (2) 인메모리 planIndex lot 도
     * 즉시 교체해 다음 tick 평가가 새 slPrice 를 곧바로 본다(refresh(≤30s) 지연 회피). status≠OPEN(청산 중/완료) lot 은 skip.
     *
     * @param code 봉 경계가 마감된 종목.
     * @param interval 마감된 봉 interval — 이 interval 로 진입 박제된 lot 만 재박제(봉 단위 정합).
     * @param boundaryTs 마감 봉 ts(kotlinx Instant) — [TrendlineGeometry.priceAt] 평가 시점.
     */
    fun restampTrendlineStops(
        code: String,
        interval: CandleInterval,
        boundaryTs: kotlinx.datetime.Instant,
    ) {
        if (!enabled) return
        val lots = planIndex[code] ?: return
        val targets =
            lots.filter {
                it.interval == interval &&
                    it.plan.stop?.let { s -> s.type == TradePlanStopType.TRENDLINE && s.trendlineId != null } == true
            }
        if (targets.isEmpty()) return

        val port = trendlinePricePortProvider?.ifAvailable
        if (port == null) {
            // §11-2 silent fallback 금지 — port 미합류 가시화. 재박제 미수행이나 마지막 박제 slPrice 로 보호는 유지된다.
            log.error(
                "PlanExecutor 추세선 손절 재박제 skip: TrendlinePricePort 미합류 — code={} interval={} lots={}",
                code,
                interval.code,
                targets.size,
            )
            return
        }

        targets.forEach { lot ->
            runCatching { restampOne(lot, port, boundaryTs) }
                .onFailure { e ->
                    log.warn(
                        "PlanExecutor 추세선 손절 재박제 실패(다음 봉 경계 재시도): userId={} strategyId={} code={} err={}",
                        lot.userId,
                        lot.strategyId,
                        lot.code,
                        e.message,
                    )
                }
        }
    }

    /** 단일 lot 재박제 — 선 조회 → [TrendlineStopRestamp.decide] → 채택 시 DB+인메모리 갱신. */
    private fun restampOne(
        lot: PlannedLot,
        port: TrendlinePricePort,
        boundaryTs: kotlinx.datetime.Instant,
    ) {
        val stop = lot.plan.stop ?: return
        val trendlineId = stop.trendlineId ?: return
        // 선 anchor 조회(캐시) → 선형보간. anchor 부재/범위 밖(priceAt null) → linePrice=null(유지 경로).
        val anchors = port.anchorsOf(trendlineId)
        val linePrice = anchors?.let { TrendlineGeometry.priceAt(it, boundaryTs) }

        val decision =
            TrendlineStopRestamp.decide(
                entryPrice = lot.plan.entry.entryPrice,
                currentSlPrice = stop.slPrice,
                linePrice = linePrice,
            )

        when (decision) {
            is TrendlineStopRestamp.Decision.NewPrice -> {
                if (persistRestampedSl(lot, decision.slPrice)) {
                    replaceIndexedLotStop(lot, decision.slPrice)
                    log.info(
                        "추세선 손절 재박제(봉 경계): userId={} strategyId={} code={} interval={} trendlineId={} prevSl={} newSl={} lineTs={}",
                        lot.userId,
                        lot.strategyId,
                        lot.code,
                        lot.interval.code,
                        trendlineId,
                        stop.slPrice?.toPlainString(),
                        decision.slPrice.toPlainString(),
                        boundaryTs,
                    )
                }
            }
            is TrendlineStopRestamp.Decision.Keep ->
                when (decision.reason) {
                    // ② 선 부재/범위 밖/무효 — §11-2 가시화(마지막 박제값 유지·보호 지속).
                    TrendlineStopRestamp.KeepReason.LINE_UNAVAILABLE,
                    TrendlineStopRestamp.KeepReason.INVALID_LINE_PRICE,
                    ->
                        log.warn(
                            "추세선 손절 재박제 유지(선 부재/무효 — 마지막 손절가 보호 지속): userId={} strategyId={} code={} trendlineId={} reason={} keptSl={}",
                            lot.userId,
                            lot.strategyId,
                            lot.code,
                            trendlineId,
                            decision.reason,
                            stop.slPrice?.toPlainString(),
                        )
                    // 래칫 가드(이익 잠금)·무변 — 정상 유지(debug).
                    TrendlineStopRestamp.KeepReason.LOCKED_ABOVE_ENTRY,
                    TrendlineStopRestamp.KeepReason.NO_CHANGE,
                    ->
                        log.debug(
                            "추세선 손절 재박제 유지: userId={} strategyId={} code={} reason={} keptSl={}",
                            lot.userId,
                            lot.strategyId,
                            lot.code,
                            decision.reason,
                            stop.slPrice?.toPlainString(),
                        )
                }
        }
    }

    /**
     * 재박제 slPrice 를 DB trade_plan JSONB 에 반영(권위 영속). status≠OPEN(청산 중/완료) lot 은 skip(재발화 방지).
     * @return true = write 성공(인메모리 교체 진행), false = skip/실패(인메모리 불변 — 다음 봉 경계 재시도).
     */
    private fun persistRestampedSl(
        lot: PlannedLot,
        newSl: java.math.BigDecimal,
    ): Boolean {
        val port = strategyPositionPortProvider.ifAvailable ?: return false
        return runCatching {
            val current = port.findByUserStrategyCode(lot.userId, lot.strategyId, lot.code) ?: return false
            val plan = current.tradePlan ?: return false
            if (plan.status != TradePlanStatus.OPEN) return false
            val curStop = plan.stop ?: return false
            if (curStop.slPrice == newSl) return false // 경합 중 이미 동일 — write 절약.
            port.upsert(
                current.copy(
                    tradePlan = plan.copy(stop = curStop.copy(slPrice = newSl)),
                    updatedAt = current.updatedAt,
                ),
            )
            true
        }.getOrElse { e ->
            log.warn(
                "추세선 손절 재박제 write 실패(인메모리 불변·다음 봉 경계 재시도): userId={} strategyId={} code={} err={}",
                lot.userId,
                lot.strategyId,
                lot.code,
                e.message,
            )
            false
        }
    }

    /** 인메모리 planIndex 의 해당 lot 손절 slPrice 를 즉시 교체(다음 tick 평가가 새 손절가를 곧바로 봄). */
    private fun replaceIndexedLotStop(
        lot: PlannedLot,
        newSl: java.math.BigDecimal,
    ) {
        val stop = lot.plan.stop ?: return
        val updated = lot.copy(plan = lot.plan.copy(stop = stop.copy(slPrice = newSl)))
        planIndex.computeIfPresent(lot.code) { _, lots ->
            lots.map { if (lotKey(it) == lotKey(lot)) updated else it }
        }
    }

    /**
     * 진입 후 경과 봉 수 — 거래시간(정규장 09:00~15:30 KST) 누적 초 / interval 초. MAX_HOLD 판정 입력.
     *
     * 감사 §6 P2-3 · 부록 A-6 — 종전 벽시계(now − openedAt) 산식은 오버나잇 보유 시 개장 직후 MAX_HOLD 를 즉시
     * 초과시키는 시한폭탄이었다(백테스트 [JvmBacktestEngine]는 거래봉 index 기준 — 정합 어긋남). 현재 라이브 plan 은
     * maxHold 를 박제하지 않아([TradePlanFactory] hold=minHold 만) 실효 발화는 없지만, 미래 maxHold 도입 대비
     * 거래시간 기준으로 교정한다. 영업일 판정은 [MarketSessionResolver] 권위(bean 부재 시 벽시계 fallback — 기존 동작).
     */
    private fun elapsedBars(
        lot: PlannedLot,
        @Suppress("UNUSED_PARAMETER") tickPrice: BigDecimal,
    ): Int {
        val intervalSeconds = MinHoldGuard.intervalSeconds(lot.interval)
        if (intervalSeconds <= 0) return 0
        val elapsed = tradingSessionElapsedSeconds(lot.openedAtEpoch, Instant.now().epochSecond)
        if (elapsed <= 0) return 0
        return (elapsed / intervalSeconds).toInt()
    }

    /**
     * [fromEpoch, toEpoch] 구간에서 정규장(09:00~15:30 KST · MarketSession.Regular 창 — ADR 0013) overlap 누적 초.
     * 영업일 판정은 [MarketSessionResolver.isBusinessDay](조회 실패 시 영업일 취급 — 보수). resolver 부재면 벽시계 fallback.
     */
    private fun tradingSessionElapsedSeconds(
        fromEpoch: Long,
        toEpoch: Long,
    ): Long {
        if (toEpoch <= fromEpoch) return 0L
        val resolver = marketSessionResolver ?: return toEpoch - fromEpoch // 벽시계 fallback(기존 동작).
        var total = 0L
        var date = Instant.ofEpochSecond(fromEpoch).atZone(SEOUL_ZONE).toLocalDate()
        val endDate = Instant.ofEpochSecond(toEpoch).atZone(SEOUL_ZONE).toLocalDate()
        var scannedDays = 0
        while (!date.isAfter(endDate) && scannedDays < MAX_SESSION_SCAN_DAYS) {
            scannedDays++
            if (runCatching { resolver.isBusinessDay(date) }.getOrDefault(true)) {
                val open = date.atTime(REGULAR_SESSION_OPEN_KST).atZone(SEOUL_ZONE).toEpochSecond()
                val close = date.atTime(REGULAR_SESSION_CLOSE_KST).atZone(SEOUL_ZONE).toEpochSecond()
                val overlapFrom = maxOf(fromEpoch, open)
                val overlapTo = minOf(toEpoch, close)
                if (overlapTo > overlapFrom) total += overlapTo - overlapFrom
            }
            date = date.plusDays(1)
        }
        return total
    }

    /**
     * 트레일링 고점 영속 (감사 §6 P2-4) — 인메모리 전용이던 highWatermark 를 trade_plan JSONB(trailing.highWatermark)에
     * 스로틀 write 해 재기동 시 [refresh]가 rehydrate 한다. trailing 미설정/비활성 lot 은 대상 아님(고점은 트레일링
     * 평가에만 쓰임 — write 0). write 실패는 권위 흐름 무손상 흡수([markPlanClosing] 패턴).
     */
    private fun persistHighWatermarkIfDue(
        lot: PlannedLot,
        high: BigDecimal,
    ) {
        val trailing = lot.plan.trailing ?: return
        if (!trailing.enabled) return
        val key = lotKey(lot)
        val nowEpoch = Instant.now().epochSecond
        val last = highWatermarkPersists[key]
        if (last != null && last.value >= high) return // 고점 미갱신 — write 0.
        if (last != null && nowEpoch - last.persistedAtEpoch < highWatermarkPersistIntervalSeconds) return // 스로틀.
        val persistedBase = trailing.highWatermark
        if (last == null && persistedBase != null && persistedBase >= high) {
            // 영속 고점이 이미 최신(재기동 직후) — write 0, 스로틀 상태만 초기화.
            highWatermarkPersists[key] = PersistedHigh(persistedBase, nowEpoch)
            return
        }
        val port = strategyPositionPortProvider.ifAvailable ?: return
        runCatching {
            val current = port.findByUserStrategyCode(lot.userId, lot.strategyId, lot.code) ?: return
            val plan = current.tradePlan ?: return
            val currentTrailing = plan.trailing ?: return
            val storedHigh = currentTrailing.highWatermark
            if (storedHigh != null && storedHigh >= high) {
                highWatermarkPersists[key] = PersistedHigh(storedHigh, nowEpoch)
                return
            }
            port.upsert(
                current.copy(
                    tradePlan = plan.copy(trailing = currentTrailing.copy(highWatermark = high)),
                    updatedAt = current.updatedAt,
                ),
            )
            highWatermarkPersists[key] = PersistedHigh(high, nowEpoch)
        }.onFailure { e ->
            log.warn(
                "PlanExecutor highWatermark 영속 실패(인메모리 고점 유지 — 다음 주기 재시도): userId={} strategyId={} code={} err={}",
                lot.userId,
                lot.strategyId,
                lot.code,
                e.message,
            )
        }
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

    /** 마지막 영속 고점 상태 (감사 §6 P2-4 — write 스로틀). */
    private data class PersistedHigh(
        val value: BigDecimal,
        val persistedAtEpoch: Long,
    )

    companion object {
        const val GROUP = "magicjar-consumer-plan-executor"

        /** KST 타임존 — 거래시간 경과 산출(감사 §6 P2-3). */
        private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** 정규장 시작/종료 KST — MarketSession.Regular 창(ADR 0013) echo. */
        private val REGULAR_SESSION_OPEN_KST: LocalTime = LocalTime.of(9, 0)
        private val REGULAR_SESSION_CLOSE_KST: LocalTime = LocalTime.of(15, 30)

        /** 거래시간 경과 스캔 상한(일) — 병리적 장기 보유에서 무한 루프 방지(초과분은 미가산 — MAX_HOLD 미발화 방향 보수). */
        private const val MAX_SESSION_SCAN_DAYS = 60
    }
}
