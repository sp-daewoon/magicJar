package kr.spdaewoon.magicjar.domain.risk

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import java.math.BigDecimal

/**
 * Risk 평가 context (ADR 0067 §5-6).
 *
 * RiskGatewayEvaluator.evaluate가 6 룰 평가 시 필요한 모든 입력의 immutable snapshot.
 *
 * ## 전략별 포지션 귀속 (ADR 0110, 분할매수 R2-2)
 * [strategyPosition] 은 SELL 시그널의 전략(strategyId) lot ledger snapshot 이다. SELL 계열 2룰
 * (`SELL_WITHOUT_POSITION`·`SELL_QUANTITY_UNAVAILABLE`)은 이 값이 **non-null(행 존재)** 이면 전략별 기준으로,
 * **null(분할매수 미사용 전략·과거 보유)** 이면 기존 user+code 합산 기준으로 평가한다(하위호환 fallback — zero-regression).
 * KIS 잔고 하드 게이트·POSITION_LIMIT·MAX_CONCURRENT 는 user+code 합산 유지(KIS·자본 노출은 전략 무관 — spec §6-2 LOCK).
 *
 * spec 인용: ADR 0067 §5-6 · ADR 0110 §2.
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
    /**
     * SELL 시그널 전략의 lot ledger snapshot (ADR 0110). non-null 이면 SELL 2룰을 전략별로 평가,
     * null 이면 user+code 합산 fallback(하위호환). BUY·기타 룰과는 무관.
     */
    val strategyPosition: StrategyPosition? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(currentCapital >= BigDecimal.ZERO) { "currentCapital must be >= 0" }
        require(recentTradesMinute >= 0) { "recentTradesMinute must be >= 0" }
        require(recentTradesDay >= 0) { "recentTradesDay must be >= 0" }
    }
}
