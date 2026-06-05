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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
    private val signalJpaRepository: ConsumerSignalJpaRepository,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
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

        verifyDrift(request)
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
     * drift 검증 — `sum(strategy_position.quantity WHERE user+code) != position.quantity` 면 WARN SystemEvent.
     * 보정은 하지 않는다(KIS 잔고 = position 이 상위 권위 — spec §3-1 · ADR 0110 §2-2).
     */
    private fun verifyDrift(request: OrderRequest) {
        val strategySum =
            try {
                strategyPositionJpaRepository.sumQuantityByUserAndCode(request.userId, request.code)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position drift sum 조회 실패 — 검증 skip: userId={} code={} err={}",
                    maskUserId(request.userId),
                    request.code,
                    e.message,
                )
                return
            }
        val positionQty =
            try {
                positionJpaRepository
                    .findByUserIdAndCodeAndSource(request.userId, request.code, request.mode.name)
                    ?.quantity ?: 0
            } catch (e: Exception) {
                log.warn(
                    "strategy_position drift position 조회 실패 — 검증 skip: userId={} code={} err={}",
                    maskUserId(request.userId),
                    request.code,
                    e.message,
                )
                return
            }

        if (strategySum != positionQty) {
            log.warn(
                "STRATEGY_POSITION_DRIFT: userId={} code={} strategySum={} positionQty={} (KIS/position 권위 우선 — 보정 안 함)",
                maskUserId(request.userId),
                request.code,
                strategySum,
                positionQty,
            )
            systemEventLoggerProvider.ifAvailable?.let { logger ->
                runCatching {
                    logger.emit(
                        SystemEvent(
                            category = SystemEventCategory.RECONCILE,
                            severity = SystemEventSeverity.WARN,
                            actor = SYSTEM_EVENT_ACTOR,
                            action = "STRATEGY_POSITION_DRIFT",
                            userId = request.userId,
                            detailJson =
                                """{"code":"${request.code}","strategySum":$strategySum,"positionQty":$positionQty}""",
                        ),
                    )
                }.onFailure { e ->
                    log.error("SystemEventLogger.emit 실패: action=STRATEGY_POSITION_DRIFT err={}", e.message)
                }
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private companion object {
        const val SYSTEM_EVENT_ACTOR = "ConsumerExecutionStrategyPositionAdapter"
        val PARTIAL_TP_REASON = Regex("PARTIAL_TAKE_PROFIT#(\\d+)")
    }
}
