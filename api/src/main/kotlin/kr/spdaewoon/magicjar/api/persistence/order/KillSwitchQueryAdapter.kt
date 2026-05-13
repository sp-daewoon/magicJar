package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * KillSwitchQueryPort JPA 어댑터 (api 모듈 — ADR 0068 §14-2 step 4 LOCK).
 *
 * kill_switch_state.enabled = true 이면 ORDER_ROUTER step 4에서 즉시 REJECTED.
 * api 모듈에서 read-only 조회만 수행.
 *
 * 헌법 §6 (ADR 0051): body 0건 [KillSwitchQueryJpaRepository] 위임.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class KillSwitchQueryAdapter(
    private val repo: KillSwitchQueryJpaRepository,
) : KillSwitchQueryPort {
    @Transactional(readOnly = true)
    override fun isOn(userId: String): Boolean = repo.findById(userId).map { it.enabled }.orElse(false)
}
