package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * position Spring Data JPA Repository (consumer 모듈 — ADR 0042).
 */
interface PositionJpaRepository : JpaRepository<PositionJpaEntity, Long> {
    fun findByUserId(userId: String): List<PositionJpaEntity>

    /**
     * upsert — ON CONFLICT (user_id, code) DO UPDATE.
     * idempotency 보장 (ADR 0042 §5-6 — 부팅 N회 반복 안전).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO position (user_id, code, quantity, avg_price, updated_at)
            VALUES (:userId, :code, :quantity, :avgPrice, now())
            ON CONFLICT (user_id, code) DO UPDATE
                SET quantity = EXCLUDED.quantity,
                    avg_price = EXCLUDED.avg_price,
                    updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsert(
        userId: String,
        code: String,
        quantity: Int,
        avgPrice: java.math.BigDecimal,
    )
}
