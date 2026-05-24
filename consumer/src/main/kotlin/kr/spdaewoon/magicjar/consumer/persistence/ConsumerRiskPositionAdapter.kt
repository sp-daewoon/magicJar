package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlinx.datetime.Instant as KInstant

/**
 * Consumer-side RiskPositionPort adapter.
 *
 * SignalGeneratedKafkaListener runs in the consumer JVM, so api module adapters are outside its bean scan.
 * This adapter keeps T-RISK current-position checks wired on the actual auto-trading path.
 */
@Component
class ConsumerRiskPositionAdapter(
    private val positionJpaRepository: PositionJpaRepository,
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : RiskPositionPort {
    @Transactional(readOnly = true)
    override fun findCurrentByUser(userId: String): List<Position> =
        positionJpaRepository
            .findByUserIdAndQuantityGreaterThan(userId, 0)
            .map { toDomain(it) }

    @Transactional(readOnly = true)
    override fun countCurrentByUser(userId: String): Int = positionJpaRepository.countByUserIdAndQuantityGreaterThan(userId, 0)

    @Transactional(readOnly = true)
    override fun sumDailyRealizedPnl(
        userId: String,
        kstMidnight: KInstant,
    ): BigDecimal =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(oe.realized_pnl), 0)
              FROM order_execution oe
              JOIN order_request req ON req.id = oe.order_id
             WHERE req.user_id = :userId
               AND oe.executed_at >= :since
               AND oe.realized_pnl IS NOT NULL
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", kstMidnight.toJavaInstant()),
            BigDecimal::class.java,
        ) ?: BigDecimal.ZERO

    private fun toDomain(entity: PositionJpaEntity): Position =
        Position(
            id = entity.id,
            userId = entity.userId,
            code = entity.code,
            quantity = entity.quantity,
            avgPrice = entity.avgPrice,
            updatedAt = entity.updatedAt.toKotlinInstant(),
        )
}
