package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * `strategy_trendline_ref` Spring Data 리포지터리 (ADR 0140 §3-3 · R2).
 *
 * 헌법 §6 (ADR 0051): interface 내부 body 가진 fun 0건 — derived query + @Query 만.
 * 참조 동기화·삭제 가드 로직은 [kr.spdaewoon.magicjar.api.service.strategy.StrategyTrendlineReferenceService].
 */
interface StrategyTrendlineRefJpaRepository : JpaRepository<StrategyTrendlineRefJpaEntity, Long> {
    fun findByStrategyId(strategyId: Long): List<StrategyTrendlineRefJpaEntity>

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    fun deleteByStrategyId(strategyId: Long): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    fun deleteByStrategyIdAndTrendlineId(
        strategyId: Long,
        trendlineId: Long,
    ): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    fun deleteByTrendlineId(trendlineId: Long): Int

    /**
     * 특정 추세선을 참조하는 **살아있는** 전략(id·name)을 조회한다 — 삭제 가드·강제삭제 근거.
     *
     * status 가 DELETED·ARCHIVED 인 전략은 이미 매매 대상이 아니므로 제외한다(ADR 0140 §3-3 참조 행 정리 정책).
     * 크로스 조인 + WHERE 로 참조 테이블과 strategy 테이블을 연결(연관 매핑 없이 id 조인).
     */
    @Query(
        """
        SELECT s.id AS strategyId, s.name AS name, s.status AS status
        FROM StrategyJpaEntity s, StrategyTrendlineRefJpaEntity r
        WHERE r.trendlineId = :trendlineId
          AND r.strategyId = s.id
          AND s.status NOT IN ('DELETED', 'ARCHIVED')
        ORDER BY s.id
        """,
    )
    fun findLiveReferencingStrategies(
        @Param("trendlineId") trendlineId: Long,
    ): List<ReferencingStrategyView>
}

/**
 * 선 삭제 가드용 참조 전략 투영 — 409 사유 메시지·강제삭제 PAUSED 대상 id.
 */
interface ReferencingStrategyView {
    val strategyId: Long
    val name: String
    val status: String
}
