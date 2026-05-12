package kr.spdaewoon.magicjar.api.service.risk

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.api.persistence.risk.RiskConfigJpaAdapter
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * RiskConfig CRUD 서비스 — T-RISK Phase 2 (ADR 0067 §5-1 H-1).
 *
 * ### H-1 getOrDefault 정합
 * userId 행 부재 시 [RiskConfig.defaultFor] seed를 자동 INSERT 후 반환.
 *
 * ### 사용자 결정 정합
 * - A-1: maxPositionAmount(절대) + maxPositionPercent(%) min() — 둘 다 필수
 * - B-1: dailyLossLimitAmount XOR dailyLossLimitPercent (둘 다 null=DLL 비활성 OK)
 * - C-1: maxConcurrentPositions default 5
 * - D-1: maxTradesPerMinute default 3 + maxTradesPerDay default 50
 * - F-1: autoPauseThreshold default 10
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Service
class RiskConfigService(
    private val riskConfigJpaAdapter: RiskConfigJpaAdapter,
) {
    private val log = LoggerFactory.getLogger(RiskConfigService::class.java)

    /**
     * 사용자 risk 설정 조회 — 부재 시 H-1 default seed INSERT 후 반환.
     */
    @Transactional
    fun getOrDefault(userId: String): RiskConfig {
        val now = Clock.System.now()
        val config = riskConfigJpaAdapter.getOrDefault(userId, now)
        log.debug("risk_config 조회: userId={} maxPositionAmount={}", userId, config.maxPositionAmount)
        return config
    }

    /**
     * risk 설정 저장 — UQ user_id UPSERT.
     *
     * domain RiskConfig 도메인 init 블록이 유효성 검증 담당 (A-1·B-1·C-1·D-1·F-1).
     */
    @Transactional
    fun save(
        userId: String,
        request: RiskConfigUpdateRequest,
    ): RiskConfig {
        val now = Clock.System.now()
        val existing = riskConfigJpaAdapter.findByUserId(userId) ?: RiskConfig.defaultFor(userId, now)
        val updated =
            existing.copy(
                maxPositionAmount = request.maxPositionAmount ?: existing.maxPositionAmount,
                maxPositionPercent = request.maxPositionPercent ?: existing.maxPositionPercent,
                dailyLossLimitAmount =
                    request.dllAbs.takeIf { request.dllAbs != null || request.dllPct != null }
                        ?: if (request.dllAbs == null && request.dllPct == null) existing.dailyLossLimitAmount else null,
                dailyLossLimitPercent =
                    request.dllPct.takeIf { request.dllAbs != null || request.dllPct != null }
                        ?: if (request.dllAbs == null && request.dllPct == null) existing.dailyLossLimitPercent else null,
                maxConcurrentPositions = request.maxConcurrentPositions ?: existing.maxConcurrentPositions,
                maxTradesPerMinute = request.maxTradesPerMinute ?: existing.maxTradesPerMinute,
                maxTradesPerDay = request.maxTradesPerDay ?: existing.maxTradesPerDay,
                autoPauseThreshold = request.autoPauseThreshold ?: existing.autoPauseThreshold,
                updatedAt = now,
            )
        val saved = riskConfigJpaAdapter.save(updated)
        log.info("risk_config 저장: userId={}", userId)
        return saved
    }

    /**
     * risk 설정 default seed로 reset.
     */
    @Transactional
    fun reset(userId: String): RiskConfig {
        val now = Clock.System.now()
        val default = RiskConfig.defaultFor(userId, now)
        val saved = riskConfigJpaAdapter.save(default)
        log.info("risk_config reset: userId={}", userId)
        return saved
    }
}

/**
 * RiskConfig 갱신 요청 DTO — null이면 기존 값 유지.
 */
data class RiskConfigUpdateRequest(
    val maxPositionAmount: BigDecimal? = null,
    val maxPositionPercent: BigDecimal? = null,
    val dllAbs: BigDecimal? = null,
    val dllPct: BigDecimal? = null,
    val maxConcurrentPositions: Int? = null,
    val maxTradesPerMinute: Int? = null,
    val maxTradesPerDay: Int? = null,
    val autoPauseThreshold: Int? = null,
)
