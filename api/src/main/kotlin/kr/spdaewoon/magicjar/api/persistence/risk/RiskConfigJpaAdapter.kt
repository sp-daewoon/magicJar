package kr.spdaewoon.magicjar.api.persistence.risk

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlinx.datetime.Instant as KInstant

/**
 * RiskConfigRepository port JPA 어댑터 — Flyway V16 정합 (ADR 0067 §5-1 H-1).
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * ### H-1 getOrDefault 정합
 * user_id 행 부재 시 [RiskConfig.defaultFor] seed 반환 (DB INSERT 0 — 조회 전용).
 * 실제 저장은 [RiskConfigService.save] 또는 [RiskConfigService.getOrDefault] 명시 호출 시.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class RiskConfigJpaAdapter(
    private val riskConfigJpaRepository: RiskConfigJpaRepository,
) : RiskConfigRepository {
    @Transactional(readOnly = true)
    override fun findByUserId(userId: String): RiskConfig? = riskConfigJpaRepository.findByUserId(userId)?.let { toDomain(it) }

    @Transactional
    override fun getOrDefault(
        userId: String,
        now: KInstant,
    ): RiskConfig {
        val entity = riskConfigJpaRepository.findByUserId(userId)
        return if (entity != null) {
            toDomain(entity)
        } else {
            val default = RiskConfig.defaultFor(userId, now)
            val saved = riskConfigJpaRepository.save(toEntity(default))
            toDomain(saved)
        }
    }

    @Transactional
    override fun save(config: RiskConfig): RiskConfig {
        val existing = riskConfigJpaRepository.findByUserId(config.userId)
        val entity =
            if (existing != null) {
                toEntityWithId(config, existing.id)
            } else {
                toEntity(config)
            }
        return toDomain(riskConfigJpaRepository.save(entity))
    }

    // --- private helper ---

    private fun toEntity(config: RiskConfig): RiskConfigJpaEntity =
        RiskConfigJpaEntity(
            userId = config.userId,
            maxPositionAbs = config.maxPositionAmount,
            maxPositionPct = config.maxPositionPercent,
            dllAbs = config.dailyLossLimitAmount,
            dllPct = config.dailyLossLimitPercent,
            maxConcurrentPositions = config.maxConcurrentPositions,
            tradeFreqPerMin = config.maxTradesPerMinute,
            tradeFreqPerDay = config.maxTradesPerDay,
            autoPauseThreshold = config.autoPauseThreshold,
            createdAt = config.createdAt.toJavaInstant(),
            updatedAt = config.updatedAt.toJavaInstant(),
        )

    private fun toEntityWithId(
        config: RiskConfig,
        id: Long?,
    ): RiskConfigJpaEntity =
        RiskConfigJpaEntity(
            id = id,
            userId = config.userId,
            maxPositionAbs = config.maxPositionAmount,
            maxPositionPct = config.maxPositionPercent,
            dllAbs = config.dailyLossLimitAmount,
            dllPct = config.dailyLossLimitPercent,
            maxConcurrentPositions = config.maxConcurrentPositions,
            tradeFreqPerMin = config.maxTradesPerMinute,
            tradeFreqPerDay = config.maxTradesPerDay,
            autoPauseThreshold = config.autoPauseThreshold,
            createdAt = config.createdAt.toJavaInstant(),
            updatedAt = config.updatedAt.toJavaInstant(),
        )

    private fun toDomain(entity: RiskConfigJpaEntity): RiskConfig =
        RiskConfig(
            userId = entity.userId,
            maxPositionAmount = entity.maxPositionAbs ?: BigDecimal("1000000"),
            maxPositionPercent = entity.maxPositionPct ?: BigDecimal("10.00"),
            dailyLossLimitAmount = entity.dllAbs,
            dailyLossLimitPercent = entity.dllPct,
            maxConcurrentPositions = entity.maxConcurrentPositions,
            maxTradesPerMinute = entity.tradeFreqPerMin,
            maxTradesPerDay = entity.tradeFreqPerDay,
            autoPauseThreshold = entity.autoPauseThreshold,
            createdAt = entity.createdAt.toKotlinInstant(),
            updatedAt = entity.updatedAt.toKotlinInstant(),
        )
}
