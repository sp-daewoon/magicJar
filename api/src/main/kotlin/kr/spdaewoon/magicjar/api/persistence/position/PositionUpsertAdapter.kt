package kr.spdaewoon.magicjar.api.persistence.position

import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding
import kr.spdaewoon.magicjar.domain.reconcile.port.PositionUpsertPort
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * position native upsert JPA 리포지터리 (T-RECON Phase 2 — reconcile 전용 write 포트).
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건 (모든 @Query는 interface method).
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
interface PositionReconJpaRepository : JpaRepository<PositionJpaEntity, Long> {
    /**
     * userId + code로 포지션 단건 조회 — upsert 존재 여부 판단용.
     */
    fun findByUserIdAndCode(
        userId: String,
        code: String,
    ): PositionJpaEntity?

    /**
     * userId + code로 포지션 갱신 (qty·avgPrice·entryTs·source) — KIS 신뢰원 갱신.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE position
               SET qty = :qty,
                   avg_price = :avgPrice,
                   entry_ts = :entryTs,
                   source = :source
             WHERE user_id = :userId AND code = :code
        """,
        nativeQuery = true,
    )
    fun updateByUserIdAndCode(
        @Param("userId") userId: String,
        @Param("code") code: String,
        @Param("qty") qty: Long,
        @Param("avgPrice") avgPrice: BigDecimal,
        @Param("entryTs") entryTs: Instant,
        @Param("source") source: String,
    ): Int
}

/**
 * [PositionUpsertPort] JPA 어댑터 — T-RECON Phase 2 (ADR 0041·0070 §2-11 step 2).
 *
 * drift 검출 시 KIS 신뢰원으로 내부 position 갱신 (upsert).
 * 기존 position 테이블 (Flyway V18) reuse.
 *
 * ### upsert 전략
 * - symbol(code) + userId로 기존 행 조회 → qty·avg_price 갱신 또는 신규 INSERT
 * - qty=0이면 포지션 청산 (DB 행 유지 — ADR 0042 Position.isClosed 정합)
 * - source = "T-RECON" (origin 표기)
 *
 * 헌법 §6 (ADR 0051): helper 메서드는 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class PositionUpsertAdapter(
    private val positionReconJpaRepository: PositionReconJpaRepository,
) : PositionUpsertPort {
    private val log = LoggerFactory.getLogger(PositionUpsertAdapter::class.java)

    @Transactional
    override fun upsertFromKis(
        userId: String,
        holding: ReconcileHolding,
    ) {
        try {
            val existing = positionReconJpaRepository.findByUserIdAndCode(userId, holding.symbol)
            if (existing != null) {
                // 기존 행 있으면 UPDATE
                val updated =
                    positionReconJpaRepository.updateByUserIdAndCode(
                        userId = userId,
                        code = holding.symbol,
                        qty = holding.quantity.toLong(),
                        avgPrice = holding.avgPrice,
                        entryTs = Instant.now(),
                        source = "T-RECON",
                    )
                log.info(
                    "PositionUpsertAdapter — 갱신: userId={}**** code={} qty={} rows={}",
                    maskUserId(userId),
                    holding.symbol,
                    holding.quantity,
                    updated,
                )
            } else {
                // 신규 행 INSERT
                val entity =
                    PositionJpaEntity(
                        userId = userId,
                        code = holding.symbol,
                        qty = holding.quantity.toLong(),
                        avgPrice = holding.avgPrice,
                        entryTs = Instant.now(),
                        source = "T-RECON",
                        createdAt = Instant.now(),
                    )
                positionReconJpaRepository.save(entity)
                log.info(
                    "PositionUpsertAdapter — 신규 INSERT: userId={}**** code={} qty={}",
                    maskUserId(userId),
                    holding.symbol,
                    holding.quantity,
                )
            }
        } catch (e: Exception) {
            log.error(
                "PositionUpsertAdapter.upsertFromKis 실패: userId={}**** code={} err={}",
                maskUserId(userId),
                holding.symbol,
                e.message,
            )
            throw e
        }
    }

    // --- private helper ---

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) else userId
}
