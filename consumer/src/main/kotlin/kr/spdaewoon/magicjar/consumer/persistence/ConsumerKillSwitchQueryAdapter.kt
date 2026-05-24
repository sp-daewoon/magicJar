package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer-side kill-switch query adapter for the order router and risk gateway.
 */
@Component
class ConsumerKillSwitchQueryAdapter(
    private val killSwitchJpaRepository: KillSwitchJpaRepository,
) : KillSwitchQueryPort {
    @Transactional(readOnly = true)
    override fun isOn(userId: String): Boolean = killSwitchJpaRepository.findById(userId).map { it.enabled }.orElse(false)
}
