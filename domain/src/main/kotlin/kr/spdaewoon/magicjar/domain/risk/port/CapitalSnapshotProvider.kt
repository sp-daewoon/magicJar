package kr.spdaewoon.magicjar.domain.risk.port

import java.math.BigDecimal

/**
 * 사용자 자본(잔고) snapshot provider (ADR 0067 §5-5 + ADR 0042 정합). 헌법 §6 abstract만.
 *
 * Phase 2 어댑터는 ADR 0042 Redis cache `balance:user:{userId}` 우선 활용.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface CapitalSnapshotProvider {
    fun getCapital(userId: String): BigDecimal
}
