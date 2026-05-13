package kr.spdaewoon.magicjar.domain.order.execution.port

/**
 * KillSwitch 조회 포트 (ADR 0024·0043 + ADR 0068 §14-2 step 4 LOCK).
 *
 * OrderRouterKafkaListener step 4 — `isOn(userId)` true 시 즉시 REJECTED + audit KILLSWITCH_DENIED.
 *
 * 기존 [kr.spdaewoon.magicjar.domain.risk.port.KillSwitchRepository] 와 구분 — 본 포트는 단일 boolean 검사 책임.
 * 구현 시 KillSwitchRepository.findByUserId(userId)?.enabled ?: false 위임 (Phase 2 또는 본 Phase 1 어댑터).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface KillSwitchQueryPort {
    fun isOn(userId: String): Boolean
}
