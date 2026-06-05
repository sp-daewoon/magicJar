package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * strategy_position Spring Data JPA Repository (consumer 모듈 — ADR 0110, 분할매수 R2-2).
 *
 * ### 헌법 §6 정합
 * abstract fun + 명시 `@Query` 만 — body 가진 default fun 0건 (Spring Data query inference 함정 회피).
 * 평단/실현손익/exit stage 갱신 수식은 어댑터([ConsumerExecutionStrategyPositionUpdateAdapter])의 helper 책임.
 */
interface StrategyPositionJpaRepository : JpaRepository<StrategyPositionJpaEntity, Long> {
    fun findByUserIdAndStrategyIdAndCode(
        userId: String,
        strategyId: Long,
        code: String,
    ): StrategyPositionJpaEntity?

    fun findByUserIdAndCode(
        userId: String,
        code: String,
    ): List<StrategyPositionJpaEntity>

    /** drift 검증용 — user+code 전략별 보유 수량 합(spec §3-1 불변식: == position.quantity). */
    @Query(
        "SELECT COALESCE(SUM(sp.quantity), 0) FROM StrategyPositionJpaEntity sp " +
            "WHERE sp.userId = :userId AND sp.code = :code",
    )
    fun sumQuantityByUserAndCode(
        @Param("userId") userId: String,
        @Param("code") code: String,
    ): Int
}
