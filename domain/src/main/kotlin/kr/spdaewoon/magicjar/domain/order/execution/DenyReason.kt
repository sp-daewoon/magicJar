package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 거절 사유 (ADR 0068 §14-1 sealed class LOCK).
 *
 * 매트릭스:
 *   RealGateDenied(cond) — 3중 게이트 (§14-3) 한 조건 미충족
 *     cond ∈ {"MAGICJAR_ALLOW_REAL=false", "USER_NOT_FOUND", "CONSENT_NOT_GIVEN", "CREDENTIAL_NOT_FOUND"}
 *   IdempotencyDuplicate — Redis SETNX false (§14-2 step 2)
 *   KillSwitchOn         — KillSwitchPort.isOn() true (§14-2 step 4)
 *   KisRejected(rt, msg) — KIS REST rt_cd != "0" (§14-2 step 7)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
sealed class DenyReason {
    data class RealGateDenied(
        val condition: String,
    ) : DenyReason()

    data object IdempotencyDuplicate : DenyReason()

    data object KillSwitchOn : DenyReason()

    data class KisRejected(
        val rtCd: String,
        val msg: String,
    ) : DenyReason()

    data class KisException(
        val cause: String,
    ) : DenyReason()
}
