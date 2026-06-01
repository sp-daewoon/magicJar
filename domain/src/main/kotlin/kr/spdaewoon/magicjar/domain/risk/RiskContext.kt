package kr.spdaewoon.magicjar.domain.risk

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.position.Position
import java.math.BigDecimal

/**
 * Risk 평가 context (ADR 0067 §5-6).
 *
 * RiskGatewayEvaluator.evaluate가 6 룰 평가 시 필요한 모든 입력의 immutable snapshot.
 *
 * spec 인용: ADR 0067 §5-6.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
data class RiskContext(
    val userId: String,
    val config: RiskConfig,
    val currentCapital: BigDecimal,
    val currentPositions: List<Position>,
    val openOrders: List<OrderRequest> = emptyList(),
    val dailyRealizedPnl: BigDecimal,
    val recentTradesMinute: Int,
    val recentTradesDay: Int,
    val killSwitchOn: Boolean,
    val nowKst: Instant,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(currentCapital >= BigDecimal.ZERO) { "currentCapital must be >= 0" }
        require(recentTradesMinute >= 0) { "recentTradesMinute must be >= 0" }
        require(recentTradesDay >= 0) { "recentTradesDay must be >= 0" }
    }
}
