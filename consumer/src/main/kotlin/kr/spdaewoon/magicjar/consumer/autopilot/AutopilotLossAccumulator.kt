package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * magicjar-autopilot **실현 손실 누적 hook** (spec 2026-06-22 §2-0-A 손실 추적 · Phase 0 Task 3).
 *
 * owner=AUTOPILOT 전략이 SELL 체결로 **실현 손실**(realizedDelta < 0)을 낼 때 그 절대값을
 * `autopilot_state.cum_realized_loss`·`daily_realized_loss` 에 누적한다([AutopilotStateStore.accumulateRealizedLoss]).
 * **이게 있어야 [kr.spdaewoon.magicjar.strategy.autopilot.AutonomousTradingGuard] 의 누적 손실 kill 이 실제로
 * 발동한다**(누적이 0 이면 kill 영원히 안 터짐).
 *
 * ## 호출점 (체결/청산 PnL 경로)
 * [kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter.applySell] 이 SELL 체결마다
 * `realizedDelta = (fillPrice − avgPrice) × sellQty` 를 산정한다. 그 시점에 본 hook 을 호출하면, 같은 transaction
 * boundary 에서 손실이 누적된다(체결 1회 = 누적 1회 — 멱등성은 호출 측 event_id dedup 이 보장).
 *
 * ## 격리·게이트 (zero-regression)
 * - [MagicjarAutopilotProperties.enabled]=false → 즉시 no-op(기존 체결 경로 byte 동일).
 * - 실현 손익이 **이익·무손실**(realizedDelta ≥ 0) → no-op(손실만 카운트 — 누적 의미 정합).
 * - 전략 owner ≠ AUTOPILOT → no-op(**사용자 수동 전략·후성 체결은 누적 0** — 격리 LOCK).
 * - 어떤 실패도 graceful(체결 권위 흐름 무손상 — 손실 누적은 부가 기록이지 체결 자체가 아님).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 hook (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotLossAccumulator(
    private val properties: MagicjarAutopilotProperties,
    private val stateStore: AutopilotStateStore,
    private val strategyRepository: ConsumerStrategyJpaRepository,
) {
    private val log = LoggerFactory.getLogger(AutopilotLossAccumulator::class.java)

    /**
     * SELL 체결의 실현 손익 delta 를 받아 owner=AUTOPILOT + 손실일 때만 누적한다.
     *
     * @param strategyId    체결 주문의 전략 FK(0=MANUAL sentinel — 무시).
     * @param realizedDelta 이번 체결의 실현 손익(양수=이익·음수=손실). 음수일 때만 절대값을 손실로 누적.
     * @return 누적 반영 후 상태(테스트·로깅용). 비대상이면 null.
     */
    fun onSellRealized(
        strategyId: Long,
        realizedDelta: BigDecimal,
    ): AutopilotState? {
        if (!properties.enabled) return null
        if (strategyId == 0L) return null
        // 손실만 카운트 — 이익·무손실 체결은 누적 대상 아님.
        if (realizedDelta >= BigDecimal.ZERO) return null

        val isAutopilot =
            try {
                strategyRepository.countAutopilotStrategyById(strategyId) > 0
            } catch (e: Exception) {
                log.warn("autopilot 손실누적 owner 조회 실패 — skip(체결 권위 무손상): strategyId={} err={}", strategyId, e.message)
                return null
            }
        if (!isAutopilot) return null

        val loss = realizedDelta.abs().setScale(4, RoundingMode.HALF_UP)
        return try {
            val state = stateStore.accumulateRealizedLoss(loss)
            log.info(
                "autopilot 실현손실 누적: strategyId={} loss={} cumRealizedLoss={} dailyRealizedLoss={}",
                strategyId,
                loss.toPlainString(),
                state.cumRealizedLoss.toPlainString(),
                state.dailyRealizedLoss.toPlainString(),
            )
            state
        } catch (e: Exception) {
            log.error("autopilot 실현손실 누적 실패 — skip(체결 권위 무손상): strategyId={} loss={} err={}", strategyId, loss, e.message)
            null
        }
    }
}
