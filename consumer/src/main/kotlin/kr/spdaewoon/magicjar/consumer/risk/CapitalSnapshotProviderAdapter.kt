package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * [CapitalSnapshotProvider] default no-op bean (ADR 0067 §7-1 — graceful fallback only).
 *
 * **현행 정합 (T-ACCT Phase 3 머지 — 2026-05-18 ADR 0081 §2-5)**: 실제 어댑터
 * [KisCapitalSnapshotProviderAdapter] (`@Component`) 가 등록되면 본 default bean은
 * `@ConditionalOnMissingBean(CapitalSnapshotProvider::class)` 로 자동 비활성. 본 bean은 다음 케이스만 활성:
 * 1. consumer 모듈 단독 부팅 시 `KisCapitalSnapshotProviderAdapter` import 부재 (테스트 fixture 등)
 * 2. 본 어댑터 revert 시 자동 fallback (자본 영향 0 — 0 반환 → POSITION_LIMIT BLOCKED → 매수 차단)
 *
 * spec 인용: ADR 0067 §5-5·§7-1 CapitalSnapshotProvider + ADR 0081 §2-5 supersede.
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
                    "CapitalSnapshotProvider default no-op — fallback 0 (KisCapitalSnapshotProviderAdapter 미등록, userId={})",
                    userId,
                )
                return BigDecimal.ZERO
            }
        }
}
