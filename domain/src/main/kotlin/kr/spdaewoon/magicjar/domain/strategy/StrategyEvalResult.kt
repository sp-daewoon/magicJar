package kr.spdaewoon.magicjar.domain.strategy

import java.math.BigDecimal
import java.time.Instant

/**
 * 전략 평가 결과 — JvmStrategyEvaluator.evaluate() 반환 타입.
 *
 * ADR 0022 §2-4 + ADR 0047 §2-2 정합.
 *
 * @param signal 시그널 (BUY·SELL·HOLD)
 * @param strength 시그널 강도 (0.0~1.0)
 * @param reason 평가 근거 맵 (trigger·lhs·rhs·lhsValue·rhsValue 등)
 * @param evaluatedAt 평가 시각
 * @param evalTimeMs 평가 소요 시간 ms
 */
data class StrategyEvalResult(
    val signal: Signal,
    val strength: BigDecimal,
    val reason: Map<String, Any>,
    val evaluatedAt: Instant,
    val evalTimeMs: Long,
)
