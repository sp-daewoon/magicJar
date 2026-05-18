package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * order_request Spring Data JPA Repository — consumer 모듈.
 *
 * 헌법 §6 (ADR 0051): Repository interface 내부 body 가진 fun 0건.
 * 헬퍼는 [ConsumerOrderRequestRepositoryAdapter] private fun 위치.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface ConsumerOrderRequestJpaRepository : JpaRepository<ConsumerOrderRequestJpaEntity, Long> {
    fun findByKisOrderNo(kisOrderNo: String): ConsumerOrderRequestJpaEntity?

    fun findByIdempotencyKey(idempotencyKey: String): ConsumerOrderRequestJpaEntity?

    @Query(
        "SELECT e FROM ConsumerOrderRequestJpaEntity e WHERE e.userId = :userId " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED')",
    )
    fun findOpenByUserId(
        @Param("userId") userId: String,
    ): List<ConsumerOrderRequestJpaEntity>
}
