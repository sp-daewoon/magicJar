package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 체결 row 저장과 내부 position projection 반영을 같은 transaction boundary로 묶는다.
 *
 * position 반영 실패 후 execution row만 남으면 다음 WS/backfill dedup에서 이미 처리된 체결로 오판하므로,
 * 두 작업은 함께 성공하거나 함께 롤백되어야 한다.
 */
@Component
class ExecutionPersistenceService(
    private val orderExecutionRepository: OrderExecutionRepository,
    private val executionPositionUpdatePort: ExecutionPositionUpdatePort,
) {
    @Transactional
    fun saveAndApplyPosition(
        request: OrderRequest,
        execution: OrderExecution,
    ): OrderExecution {
        val saved = orderExecutionRepository.save(execution)
        executionPositionUpdatePort.applyExecution(request, saved)
        return saved
    }
}
