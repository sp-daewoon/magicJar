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

    /**
     * replay TOCTOU 직렬화용 advisory xact lock — (user, strategyId, code) 2개 정수 키.
     *
     * `pg_advisory_xact_lock(key1, key2)` 는 현재 트랜잭션 범위 lock 으로, commit/rollback 시 자동 해제(leak 0).
     * 같은 키의 동시 replay 트랜잭션을 직렬화해 [StrategyPositionExecutionReplayService] 의
     * 존재확인→가산 read-modify-write 이중 진입(over-count)을 차단한다.
     *
     * - key1 = (user_id, strategy_id) 결합 해시 — `('x' || md5(...))::bit(32)::int`.
     * - key2 = code 해시 — 동.
     * 32bit int 충돌은 advisory lock 의 한 키에 한해 (다른 종목이 같은 lock 을 공유) 약간의 직렬화만 유발할 뿐
     * 정합성을 깨지 않는다(보수적 over-lock).
     *
     * @Query nativeQuery — JPQL 로 표현 불가한 Postgres 함수. 반드시 활성 트랜잭션 안에서만 호출(xact-scope).
     */
    @Query(
        value =
            "SELECT pg_advisory_xact_lock(" +
                "('x' || md5(:userId || ':' || cast(:strategyId as text)))::bit(32)::int, " +
                "('x' || md5(:code))::bit(32)::int)",
        nativeQuery = true,
    )
    fun acquireReplayAdvisoryLock(
        @Param("userId") userId: String,
        @Param("strategyId") strategyId: Long,
        @Param("code") code: String,
    )
}
