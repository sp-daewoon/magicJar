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

    /**
     * 보유 중(quantity>0) 전 strategy_position — ADR 0114 R2-C2b 인메모리 보유 인덱스 60초 refresh·HOLDING owner 등록.
     * @Query 명시 — 헌법 §6 default-fun 함정 회피(method-name 추론 대신 명시 JPQL).
     */
    @Query("SELECT sp FROM StrategyPositionJpaEntity sp WHERE sp.quantity > 0")
    fun findAllHolding(): List<StrategyPositionJpaEntity>

    /**
     * 특정 종목 보유(quantity>0) lot — ADR 0114 R2-C2b 인덱스 miss 시 즉시 재확인·drift 가드용.
     * tick hot path는 인메모리 인덱스를 1차로 쓰고 본 쿼리는 등록/refresh·검증 경로에서만 호출(매 tick DB 조회 금지).
     */
    @Query("SELECT sp FROM StrategyPositionJpaEntity sp WHERE sp.code = :code AND sp.quantity > 0")
    fun findHoldingByCode(
        @Param("code") code: String,
    ): List<StrategyPositionJpaEntity>
}
