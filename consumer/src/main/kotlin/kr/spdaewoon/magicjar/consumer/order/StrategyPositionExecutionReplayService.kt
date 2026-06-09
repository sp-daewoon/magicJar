package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionStrategyPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * 이미 저장된 order_execution을 strategy_position으로 재생하는 누락 복구 서비스.
 *
 * WS_PREINSERT race 경로는 처음 체결을 strategyId=0 sentinel 주문에 붙인 뒤, ACK 시점에 원 주문으로
 * order_execution을 재배정한다. 그 첫 저장 시점에는 전략 귀속이 skip되므로, 재배정 후 이 서비스가
 * 원 주문(strategyId 포함) 기준으로 execution을 한 번 재생한다.
 */
@Component
class StrategyPositionExecutionReplayService(
    private val orderExecutionRepositoryProvider: ObjectProvider<OrderExecutionRepository>,
    private val strategyPositionUpdatePortProvider: ObjectProvider<ExecutionStrategyPositionUpdatePort>,
    private val strategyPositionPortProvider: ObjectProvider<StrategyPositionPort>,
) {
    private val log = LoggerFactory.getLogger(StrategyPositionExecutionReplayService::class.java)

    fun replayIfMissing(
        request: OrderRequest,
        reason: String,
    ): Int {
        val orderId = request.id ?: return 0
        if (request.strategyId == 0L) return 0

        val strategyPositionPort = strategyPositionPortProvider.getIfAvailable() ?: return 0
        val updatePort = strategyPositionUpdatePortProvider.getIfAvailable() ?: return 0
        val executionRepository = orderExecutionRepositoryProvider.getIfAvailable() ?: return 0

        val existing =
            try {
                strategyPositionPort.findByUserStrategyCode(request.userId, request.strategyId, request.code)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position replay 기존 row 조회 실패 — skip: orderId={} strategyId={} code={} reason={} err={}",
                    orderId,
                    request.strategyId,
                    request.code,
                    reason,
                    e.message,
                )
                return 0
            }
        if (existing != null) return 0

        val executions =
            try {
                executionRepository.findByOrderId(orderId)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position replay execution 조회 실패 — skip: orderId={} strategyId={} code={} reason={} err={}",
                    orderId,
                    request.strategyId,
                    request.code,
                    reason,
                    e.message,
                )
                return 0
            }
        if (executions.isEmpty()) return 0

        var replayed = 0
        for (execution in executions) {
            runCatching { updatePort.applyExecution(request, execution) }
                .onSuccess { replayed++ }
                .onFailure { e ->
                    log.warn(
                        "strategy_position replay 적용 실패: orderId={} executionId={} strategyId={} code={} reason={} err={}",
                        orderId,
                        execution.id,
                        request.strategyId,
                        request.code,
                        reason,
                        e.message,
                    )
                }
        }

        if (replayed > 0) {
            log.warn(
                "strategy_position replay 완료: orderId={} strategyId={} code={} executions={} replayed={} reason={}",
                orderId,
                request.strategyId,
                request.code,
                executions.size,
                replayed,
                reason,
            )
        }
        return replayed
    }
}
