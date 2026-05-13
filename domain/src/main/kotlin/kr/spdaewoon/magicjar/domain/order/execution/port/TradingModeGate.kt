package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode

/**
 * 3중 게이트 포트 (ADR 0068 §14-1 LOCK · §14-3 정합 · 의제 A-1).
 *
 * 자본 보호 핵심 — 3 조건 모두 통과해야 REAL 모드 진입:
 *   1. env MAGICJAR_MODE == "REAL"  (false → MOCK fallback, 안전 default)
 *   2. env MAGICJAR_ALLOW_REAL == "true"
 *   3. user_account.real_trading_consent_at != null (사용자 동의 토글)
 *
 * 한 조건이라도 false 시 → [OrderResult.Left] DenyReason.RealGateDenied(cond) 즉시 반환 +
 * audit `REAL_GATE_DENIED:{which condition}` (호출자 책임).
 *
 * Phase 1 consumer TradingModeGateImpl이 구현.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface TradingModeGate {
    fun resolveMode(userId: String): OrderResult<DenyReason.RealGateDenied, TradingMode>
}
