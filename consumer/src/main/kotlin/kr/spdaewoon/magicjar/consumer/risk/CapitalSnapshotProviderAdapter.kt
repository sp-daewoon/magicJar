package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * [CapitalSnapshotProvider] default no-op bean (ADR 0067 §7-1 — Phase 2 합류 전 graceful fallback).
 *
 * Phase 2 backend-core가 ADR 0042 `balance:user:{userId}` Redis cache 활용한 실제 어댑터를 발행하기 전,
 * 본 default bean이 0 반환 — RiskGatewayEvaluator POSITION_LIMIT 룰이 보수적으로 BLOCKED 처리.
 *
 * spec 인용: ADR 0067 §5-5 + §7-1 CapitalSnapshotProvider.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Configuration
class CapitalSnapshotProviderAdapter {
    private val log = LoggerFactory.getLogger(CapitalSnapshotProviderAdapter::class.java)

    @Bean
    @ConditionalOnMissingBean(CapitalSnapshotProvider::class)
    fun defaultCapitalSnapshotProvider(): CapitalSnapshotProvider =
        object : CapitalSnapshotProvider {
            override fun getCapital(userId: String): BigDecimal {
                log.debug(
                    "CapitalSnapshotProvider Phase 2 미합류 — fallback 0 (userId={})",
                    userId,
                )
                return BigDecimal.ZERO
            }
        }
}
