package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionStrategyPositionUpdatePort
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.strategy.integrity.DriftCorrectionInput
import kr.spdaewoon.magicjar.strategy.integrity.DriftCorrectionResult
import kr.spdaewoon.magicjar.strategy.integrity.LedgerAttributedDriftCorrection
import kr.spdaewoon.magicjar.strategy.integrity.LedgerAttribution
import kr.spdaewoon.magicjar.strategy.integrity.LedgerDriftInput
import kr.spdaewoon.magicjar.strategy.integrity.PositionDriftCorrection
import kr.spdaewoon.magicjar.strategy.integrity.StrategyPositionLeg
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 체결 직후 전략별 포지션 귀속(strategy_position) upsert 어댑터 (ADR 0110, 분할매수 R2-2).
 *
 * 기존 [ConsumerExecutionPositionUpdateAdapter](user+code 합산 position)와 **대칭** — [ExecutionPersistenceService]
 * 의 같은 transaction boundary 에서 호출되어 strategy_position lot ledger 를 동반 갱신한다.
 *
 * ## 합류점 결정 (spec §5 흐름)
 * `KisExecutionWsListener.handleExecution` → [ExecutionPersistenceService.saveAndApplyPosition]
 * (order_execution INSERT + position upsert 묶음, event_id dedup 뒤) 에 본 어댑터를 추가 호출한다.
 * 멱등성은 그 호출 측의 event_id dedup 가드가 보장(같은 체결 1회만 도달).
 *
 * ## 평단/실현손익 수식 (spec §3-1 — [ConsumerExecutionPositionUpdateAdapter] BUY/SELL 수식 정합)
 * - BUY: `avgPrice = (avgPrice*qty + fillPrice*fillQty)/(qty+fillQty)`, `quantity += fillQty`, `filledLots += 1`.
 * - SELL: `realizedPnl += (fillPrice - avgPrice)*sellQty`, `quantity -= sellQty`.
 *   reason `PARTIAL_TAKE_PROFIT#{k}` → `executedExitStages += k`(체결 시점 마킹).
 *   `quantity == 0` → `avgPrice`·`filledLots`·`executedExitStages` reset(행 유지).
 *
 * ## skip 조건
 * - `strategyId == 0`(MANUAL sentinel — 전략 귀속 없음) / 체결 수량 ≤ 0.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 어댑터 (ADR 0023 §2-4 정합)
 */
@Component
class ConsumerExecutionStrategyPositionAdapter(
    private val strategyPositionPort: StrategyPositionPort,
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
    private val positionJpaRepository: PositionJpaRepository,
    private val orderRequestJpaRepository: ConsumerOrderRequestJpaRepository,
    private val signalJpaRepository: ConsumerSignalJpaRepository,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    @Value("\${magicjar.position.drift-auto-correct.enabled:true}")
    private val driftAutoCorrectEnabled: Boolean = true,
    private val clock: Clock = Clock.System,
) : ExecutionStrategyPositionUpdatePort {
    private val log = LoggerFactory.getLogger(ConsumerExecutionStrategyPositionAdapter::class.java)

    @Transactional
    override fun applyExecution(
        request: OrderRequest,
        execution: OrderExecution,
    ) {
        // MANUAL sentinel(0L) 또는 미귀속 주문은 전략 lot ledger 대상 아님.
        if (request.strategyId == 0L) {
            log.debug("strategy_position upsert skip — MANUAL sentinel(strategyId=0): orderId={}", execution.orderId)
            return
        }
        val fillQty = execution.executedQuantity.coerceAtLeast(0)
        if (fillQty == 0) return

        val current =
            strategyPositionPort.findByUserStrategyCode(request.userId, request.strategyId, request.code)
        val now = clock.now()

        val next =
            when (request.side) {
                OrderSide.BUY -> applyBuy(request, execution, current, fillQty, now)
                OrderSide.SELL -> applySell(request, execution, current, fillQty, now)
            }
        strategyPositionPort.upsert(next)

        log.info(
            "strategy_position upsert: userId={} strategyId={} code={} side={} fillQty={} nextQty={} avgPrice={} filledLots={} realizedPnl={} exitStages={}",
            maskUserId(request.userId),
            request.strategyId,
            request.code,
            request.side,
            fillQty,
            next.quantity,
            next.avgPrice.toPlainString(),
            next.filledLots,
            next.realizedPnl.toPlainString(),
            next.executedExitStages,
        )

        reconcileDrift(request.userId, request.code, request.mode.name)
    }

    /**
     * 외부(주기 reconcile worker) 진입점 — 체결 없이도 strategy_position drift 정합 (ADR 0115 §P1-1 amend 2026-06-10).
     *
     * 2026-06-10 incident: replay-skip 으로 [applyExecution] 경로가 우회되면 그 strategy_position 의 drift 가
     * **다음 체결 전까지 영구 잔존**한다(qty=0 vs KIS 3주 → 시그널 BLOCKED). 본 메서드를
     * [kr.spdaewoon.magicjar.consumer.reconcile.StrategyPositionReconcileWorker] 가 BalanceReconcile 주기에 호출해
     * 체결 이벤트와 무관하게 정합을 회복한다. 정정 로직은 체결 직후 경로와 동일([reconcileDrift]).
     *
     * 모드 무관(REAL/MOCK 동일 — position 권위 공통). DB 귀속 정렬만(주문 발행 0).
     */
    @Transactional
    fun reconcileStrategyPositionDrift(
        userId: String,
        code: String,
        modeName: String,
    ) {
        reconcileDrift(userId, code, modeName)
    }

    private fun applyBuy(
        request: OrderRequest,
        execution: OrderExecution,
        current: StrategyPosition?,
        fillQty: Int,
        now: kotlinx.datetime.Instant,
    ): StrategyPosition {
        val currentQty = current?.quantity ?: 0
        val currentAvg = current?.avgPrice ?: BigDecimal.ZERO
        val nextQty = currentQty + fillQty
        val currentNotional = currentAvg.multiply(BigDecimal(currentQty))
        val fillNotional = execution.executedPrice.multiply(BigDecimal(fillQty))
        val nextAvg =
            currentNotional
                .add(fillNotional)
                .divide(BigDecimal(nextQty), 4, RoundingMode.HALF_UP)
        return baseOf(request, current, now).copy(
            quantity = nextQty,
            avgPrice = nextAvg,
            filledLots = (current?.filledLots ?: 0) + 1,
        )
    }

    private fun applySell(
        request: OrderRequest,
        execution: OrderExecution,
        current: StrategyPosition?,
        fillQty: Int,
        now: kotlinx.datetime.Instant,
    ): StrategyPosition {
        val currentQty = current?.quantity ?: 0
        val currentAvg = current?.avgPrice ?: BigDecimal.ZERO
        val sellQty = fillQty.coerceAtMost(currentQty)
        if (fillQty > currentQty) {
            log.warn(
                "strategy_position SELL overflow (KIS 권위 우선 — clamp): userId={} strategyId={} code={} currentQty={} fillQty={}",
                maskUserId(request.userId),
                request.strategyId,
                request.code,
                currentQty,
                fillQty,
            )
        }
        val realizedDelta =
            execution.executedPrice
                .subtract(currentAvg)
                .multiply(BigDecimal(sellQty))
        val nextQty = (currentQty - sellQty).coerceAtLeast(0)
        val nextRealized = (current?.realizedPnl ?: BigDecimal.ZERO).add(realizedDelta)

        // 체결 확정 시 PartialExit 단계 마킹 (발행 아닌 체결 시점 — spec §3-1).
        val markedStages = markPartialExitStage(request, current?.executedExitStages ?: emptySet())

        return if (nextQty == 0) {
            // 전량 청산 — avgPrice·filledLots·executedExitStages reset (행 유지, 재진입 새 사이클).
            baseOf(request, current, now).copy(
                quantity = 0,
                avgPrice = BigDecimal.ZERO,
                filledLots = 0,
                realizedPnl = nextRealized,
                executedExitStages = emptySet(),
            )
        } else {
            baseOf(request, current, now).copy(
                quantity = nextQty,
                avgPrice = currentAvg,
                realizedPnl = nextRealized,
                executedExitStages = markedStages,
            )
        }
    }

    /**
     * SELL 체결의 시그널 reason 이 `PARTIAL_TAKE_PROFIT#{k}` 면 단계 k 마킹(체결 시점).
     * signalId=0(sentinel) 또는 reason 미매칭이면 기존 집합 그대로.
     */
    private fun markPartialExitStage(
        request: OrderRequest,
        existing: Set<Int>,
    ): Set<Int> {
        if (request.signalId == 0L) return existing
        val reason =
            try {
                signalJpaRepository.findById(request.signalId).orElse(null)?.reason
            } catch (e: Exception) {
                log.warn("strategy_position partial-exit reason 조회 실패 — 단계 마킹 skip: signalId={} err={}", request.signalId, e.message)
                null
            } ?: return existing
        val stage =
            PARTIAL_TP_REASON
                .find(reason)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: return existing
        return existing + stage
    }

    private fun baseOf(
        request: OrderRequest,
        current: StrategyPosition?,
        now: kotlinx.datetime.Instant,
    ): StrategyPosition =
        StrategyPosition(
            id = current?.id,
            userId = request.userId,
            strategyId = request.strategyId,
            code = request.code,
            quantity = current?.quantity ?: 0,
            avgPrice = current?.avgPrice ?: BigDecimal.ZERO,
            filledLots = current?.filledLots ?: 0,
            realizedPnl = current?.realizedPnl ?: BigDecimal.ZERO,
            executedExitStages = current?.executedExitStages ?: emptySet(),
            openedAt = current?.openedAt ?: now,
            updatedAt = now,
        )

    /**
     * drift 정합 — `sum(strategy_position.quantity WHERE user+code)` 를 `position.quantity`(KIS 잔고 권위)에 정렬한다.
     *
     * ADR 0115 §P1-1 (R2 배선). R1 순수 함수 [PositionDriftCorrection.evaluate]가 보정 수량을 산정하고,
     * 본 메서드가 그 결과를 strategy_position DB 에 반영한다(수량만 — realized_pnl·avgPrice 불변 LOCK).
     *
     * - [DriftCorrectionResult.Adjust]: 초과 leg 를 권위 수량에 맞춰 내림 UPDATE + `STRATEGY_POSITION_DRIFT_CORRECTED`
     *   SystemEvent(WARN, before/after qty). 수량==0 도달 leg 는 avgPrice·filledLots·executedExitStages reset(applySell 정합).
     * - [DriftCorrectionResult.WarnUnderflow]: 부족 방향 — 보정하지 않고 현행 `STRATEGY_POSITION_DRIFT` WARN 유지(없는 물량 인식 회피).
     * - [DriftCorrectionResult.NoChange]/[DriftCorrectionResult.Disabled]: 무동작(Disabled 는 ENV off → 현행 WARN-only fallback).
     *
     * 모드 무관(REAL/MOCK 동일 — position 권위 공통, ADR 0115 §P1-1). 실주문 액션 게이트는 본 경로와 무관(수량 정렬만).
     */
    private fun reconcileDrift(
        userId: String,
        code: String,
        modeName: String,
    ) {
        val legs =
            try {
                strategyPositionPort.findByUserCode(userId, code)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position drift leg 조회 실패 — 정합 skip: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
                return
            }
        val positionQty =
            try {
                positionJpaRepository
                    .findByUserIdAndCodeAndSource(userId, code, modeName)
                    ?.quantity ?: 0
            } catch (e: Exception) {
                log.warn(
                    "strategy_position drift position 조회 실패 — 정합 skip: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
                return
            }

        val result =
            PositionDriftCorrection.evaluate(
                DriftCorrectionInput(
                    code = code,
                    authorityQuantity = positionQty,
                    legs =
                        legs.map { leg ->
                            StrategyPositionLeg(
                                strategyId = leg.strategyId,
                                quantity = leg.quantity,
                                updatedAt = leg.updatedAt,
                            )
                        },
                    enabled = driftAutoCorrectEnabled,
                ),
            )

        when (result) {
            is DriftCorrectionResult.Adjust -> applyDriftCorrection(userId, code, legs, result)
            // 부족 방향 — 귀속 ledger 로 정정 가능한지 먼저 시도(ADR 0115 §P1-1 amend 2026-06-10).
            // 명확한 귀속(전략 FILLED execution)으로 authority 재구성 가능하면 정정, 모호하면 WARN.
            is DriftCorrectionResult.WarnUnderflow ->
                reconcileUnderflowByLedger(userId, code, legs, positionQty, result)
            // Disabled(ENV off) — 현행 WARN-only 로 fallback. 일치/정합이면 무동작.
            is DriftCorrectionResult.Disabled -> warnIfDisabledDrift(userId, code, positionQty)
            DriftCorrectionResult.NoChange -> Unit
        }
    }

    /**
     * 부족(strategySum < authority) 방향 drift — 귀속 ledger 기반 자동 정정 시도 (ADR 0115 §P1-1 amend, 2026-06-10).
     *
     * 2026-06-10 incident: WS 체결통보 유실→REST backfill 흡수된 FILLED execution 이 replay-skip 으로
     * strategy_position 에 미반영(qty=0 vs KIS 3주). 외부 주문이 아니라 **우리 전략이 체결시킨 주문의 ledger 미반영**이므로
     * order_request.strategy_id 라는 명확한 귀속 근거가 있다 → [LedgerAttributedDriftCorrection] 3중 게이트로 좁게 정정.
     *
     * - [DriftCorrectionResult.Adjust]: ledger 로 authority 정확히 재구성 → leg 수량·평단 정정 + `STRATEGY_POSITION_DRIFT_CORRECTED`.
     * - 그 외(모호 — ledger 합 ≠ authority): 현행 [emitDriftWarn] WARN-only (운영 알림·수동 판단).
     */
    private fun reconcileUnderflowByLedger(
        userId: String,
        code: String,
        legs: List<StrategyPosition>,
        positionQty: Int,
        underflow: DriftCorrectionResult.WarnUnderflow,
    ) {
        val ledgerByStrategy =
            try {
                aggregateLedgerAttribution(userId, code)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position underflow ledger 집계 실패 — WARN-only fallback: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
                emitDriftWarn(userId, code, underflow.strategySum, underflow.authorityQuantity)
                return
            }

        val result =
            LedgerAttributedDriftCorrection.evaluate(
                LedgerDriftInput(
                    code = code,
                    authorityQuantity = positionQty,
                    legs =
                        legs.map { leg ->
                            StrategyPositionLeg(
                                strategyId = leg.strategyId,
                                quantity = leg.quantity,
                                updatedAt = leg.updatedAt,
                            )
                        },
                    attributions = ledgerByStrategy.values.map { it.attribution },
                    enabled = driftAutoCorrectEnabled,
                ),
            )

        when (result) {
            is DriftCorrectionResult.Adjust ->
                applyLedgerUnderflowCorrection(userId, code, legs, ledgerByStrategy, result)
            // ledger 로 정정 불가(모호) — 현행 WARN-only.
            else -> emitDriftWarn(userId, code, underflow.strategySum, underflow.authorityQuantity)
        }
    }

    /**
     * user+code 의 전략별 귀속 ledger 집계 — (strategyId → net 순매수량 + BUY 가중평단).
     *
     * `aggregateFilledByStrategyAndSide`(strategyId,side)→(qtySum,notionalSum) 행을 전략별로 묶어
     * net = BUY qty - SELL qty, avgBuyPrice = BUY notional / BUY qty 산정. AUTO·strategyId≠0 만 집계(JPA WHERE).
     */
    private fun aggregateLedgerAttribution(
        userId: String,
        code: String,
    ): Map<Long, LedgerStrategyAggregate> {
        val rows = orderRequestJpaRepository.aggregateFilledByStrategyAndSide(userId, code)
        val buyQty = mutableMapOf<Long, Int>()
        val sellQty = mutableMapOf<Long, Int>()
        val buyNotional = mutableMapOf<Long, BigDecimal>()
        for (row in rows) {
            val qty = row.qtySum.setScale(0, RoundingMode.DOWN).toInt()
            when (row.side) {
                OrderSide.BUY -> {
                    buyQty[row.strategyId] = (buyQty[row.strategyId] ?: 0) + qty
                    buyNotional[row.strategyId] =
                        (buyNotional[row.strategyId] ?: BigDecimal.ZERO).add(row.notionalSum)
                }
                OrderSide.SELL -> sellQty[row.strategyId] = (sellQty[row.strategyId] ?: 0) + qty
            }
        }
        val strategyIds = buyQty.keys + sellQty.keys
        return strategyIds.associateWith { strategyId ->
            val bq = buyQty[strategyId] ?: 0
            val sq = sellQty[strategyId] ?: 0
            val net = bq - sq
            val avgBuy =
                if (bq > 0) {
                    (buyNotional[strategyId] ?: BigDecimal.ZERO).divide(BigDecimal(bq), 4, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }
            LedgerStrategyAggregate(
                attribution = LedgerAttribution(strategyId = strategyId, netFilledQuantity = net),
                avgBuyPrice = avgBuy,
            )
        }
    }

    /**
     * 부족 보정 적용 — leg 수량을 ledger 순매수량으로 set + 평단 복원(ledger BUY 가중평단) + 감사.
     *
     * incident 경로: 기존 leg(qty=0·avgPrice=0 reset)를 ledger net(예: 3)·avgBuy 로 복원. realized_pnl 보존(체결만 변동).
     * leg 부재(신규 전략) 시 새 row 생성. over-count 0 — newQuantity 는 절대 목표(멱등).
     */
    private fun applyLedgerUnderflowCorrection(
        userId: String,
        code: String,
        legs: List<StrategyPosition>,
        ledgerByStrategy: Map<Long, LedgerStrategyAggregate>,
        result: DriftCorrectionResult.Adjust,
    ) {
        val legById = legs.associateBy { it.strategyId }
        val now = clock.now()
        for (correction in result.corrections) {
            val avgBuy = ledgerByStrategy[correction.strategyId]?.avgBuyPrice ?: BigDecimal.ZERO
            val existing = legById[correction.strategyId]
            val corrected =
                if (existing != null) {
                    // 평단은 ledger BUY 가중평단으로 복원(reset 된 0 → 실제 진입가). realized_pnl·openedAt 보존.
                    existing.copy(
                        quantity = correction.newQuantity,
                        avgPrice = avgBuy,
                        updatedAt = now,
                    )
                } else {
                    // leg 부재(귀속 전략의 row 자체 없음) — 신규 생성. realized_pnl 0(체결 손익은 청산 시 누적).
                    StrategyPosition(
                        id = null,
                        userId = userId,
                        strategyId = correction.strategyId,
                        code = code,
                        quantity = correction.newQuantity,
                        avgPrice = avgBuy,
                        filledLots = 0,
                        realizedPnl = BigDecimal.ZERO,
                        executedExitStages = emptySet(),
                        openedAt = now,
                        updatedAt = now,
                    )
                }
            try {
                strategyPositionPort.upsert(corrected)
            } catch (e: Exception) {
                log.error(
                    "STRATEGY_POSITION_DRIFT_CORRECTED(underflow) upsert 실패: userId={} strategyId={} code={} err={}",
                    maskUserId(userId),
                    correction.strategyId,
                    code,
                    e.message,
                )
            }
        }
        log.warn(
            "STRATEGY_POSITION_DRIFT_CORRECTED(underflow): userId={} code={} strategySum={}->{} corrections={} (귀속 ledger 정정)",
            maskUserId(userId),
            code,
            result.strategySum,
            result.authorityQuantity,
            result.corrections.joinToString { "${it.strategyId}:${it.previousQuantity}->${it.newQuantity}" },
        )
        emitSystemEvent(
            userId = userId,
            action = "STRATEGY_POSITION_DRIFT_CORRECTED",
            detailJson =
                """{"code":"$code","direction":"underflow","strategySum":${result.strategySum},""" +
                    """"authorityQuantity":${result.authorityQuantity},""" +
                    """"corrections":[${result.corrections.joinToString(",") {
                        """{"strategyId":${it.strategyId},"prev":${it.previousQuantity},"next":${it.newQuantity}}"""
                    }}]}""",
        )
    }

    /**
     * [DriftCorrectionResult.Adjust] 적용 — 보정 leg 별 strategy_position 수량 UPDATE + 감사 이벤트(디바운스 1건).
     * realized_pnl 절대 미변경. quantity==0 도달 leg 는 전량 청산 reset(applySell nextQty==0 분기 정합).
     */
    private fun applyDriftCorrection(
        userId: String,
        code: String,
        legs: List<StrategyPosition>,
        result: DriftCorrectionResult.Adjust,
    ) {
        val legById = legs.associateBy { it.strategyId }
        val now = clock.now()
        for (correction in result.corrections) {
            val leg = legById[correction.strategyId] ?: continue
            val corrected =
                if (correction.willBeClosed) {
                    leg.copy(
                        quantity = 0,
                        avgPrice = BigDecimal.ZERO,
                        filledLots = 0,
                        executedExitStages = emptySet(),
                        updatedAt = now,
                    )
                } else {
                    leg.copy(quantity = correction.newQuantity, updatedAt = now)
                }
            try {
                strategyPositionPort.upsert(corrected)
            } catch (e: Exception) {
                log.error(
                    "STRATEGY_POSITION_DRIFT_CORRECTED upsert 실패: userId={} strategyId={} code={} err={}",
                    maskUserId(userId),
                    correction.strategyId,
                    code,
                    e.message,
                )
            }
        }
        log.warn(
            "STRATEGY_POSITION_DRIFT_CORRECTED: userId={} code={} strategySum={}->{} corrections={} (position 권위 정렬)",
            maskUserId(userId),
            code,
            result.strategySum,
            result.authorityQuantity,
            result.corrections.joinToString { "${it.strategyId}:${it.previousQuantity}->${it.newQuantity}" },
        )
        // 보정 1건당 감사 이벤트 1건(디바운스 — 보정은 정합 회복 즉시 1회만 발생).
        emitSystemEvent(
            userId = userId,
            action = "STRATEGY_POSITION_DRIFT_CORRECTED",
            detailJson =
                """{"code":"$code","strategySum":${result.strategySum},"authorityQuantity":${result.authorityQuantity},""" +
                    """"corrections":[${result.corrections.joinToString(",") {
                        """{"strategyId":${it.strategyId},"prev":${it.previousQuantity},"next":${it.newQuantity}}"""
                    }}]}""",
        )
    }

    /** 부족 방향 drift — 귀속 ledger 로도 정정 불가(모호)할 때 현행 WARN 유지(보정 안 함). */
    private fun emitDriftWarn(
        userId: String,
        code: String,
        strategySum: Int,
        positionQty: Int,
    ) {
        log.warn(
            "STRATEGY_POSITION_DRIFT: userId={} code={} strategySum={} positionQty={} (부족 방향 — 귀속 근거 모호, position 권위 우선, 보정 안 함)",
            maskUserId(userId),
            code,
            strategySum,
            positionQty,
        )
        emitSystemEvent(
            userId = userId,
            action = "STRATEGY_POSITION_DRIFT",
            detailJson = """{"code":"$code","strategySum":$strategySum,"positionQty":$positionQty}""",
        )
    }

    /** ENV 보정 off(Disabled) 시 — 정합 차이가 있으면 현행 WARN-only fallback(보정 안 함). */
    private fun warnIfDisabledDrift(
        userId: String,
        code: String,
        positionQty: Int,
    ) {
        val strategySum =
            try {
                strategyPositionJpaRepository.sumQuantityByUserAndCode(userId, code)
            } catch (e: Exception) {
                return
            }
        if (strategySum != positionQty) {
            emitDriftWarn(userId, code, strategySum, positionQty)
        }
    }

    private fun emitSystemEvent(
        userId: String,
        action: String,
        detailJson: String,
    ) {
        systemEventLoggerProvider.ifAvailable?.let { logger ->
            runCatching {
                logger.emit(
                    SystemEvent(
                        category = SystemEventCategory.RECONCILE,
                        severity = SystemEventSeverity.WARN,
                        actor = SYSTEM_EVENT_ACTOR,
                        action = action,
                        userId = userId,
                        detailJson = detailJson,
                    ),
                )
            }.onFailure { e ->
                log.error("SystemEventLogger.emit 실패: action={} err={}", action, e.message)
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /** 전략 1개의 귀속 ledger 집계 — 순매수량(보정 산정) + BUY 가중평단(부족 보정 시 평단 복원). */
    private data class LedgerStrategyAggregate(
        val attribution: LedgerAttribution,
        val avgBuyPrice: BigDecimal,
    )

    private companion object {
        const val SYSTEM_EVENT_ACTOR = "ConsumerExecutionStrategyPositionAdapter"
        val PARTIAL_TP_REASON = Regex("PARTIAL_TAKE_PROFIT#(\\d+)")
    }
}
