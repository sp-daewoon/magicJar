package kr.spdaewoon.magicjar.api.persistence.signal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * 시그널 Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [SignalJpaAdapter] private fun 위치.
 *
 * V19 추가: risk_status 컬럼 UPDATE — SignalRiskStatusJpaAdapter 호출 (ADR 0067 §5-5 F-1).
 *
 * // AI 호출 0 — T-SIG 트랙 정책
 */
interface SignalJpaRepository :
    JpaRepository<SignalJpaEntity, Long>,
    JpaSpecificationExecutor<SignalJpaEntity> {
    fun findByStrategyIdAndCodeAndIntervalAndTs(
        strategyId: Long,
        code: String,
        interval: String,
        ts: Instant,
    ): SignalJpaEntity?

    @Modifying
    @Query("UPDATE SignalJpaEntity s SET s.riskStatus = :status WHERE s.id = :id")
    fun updateRiskStatus(
        @Param("id") id: Long,
        @Param("status") status: String,
    ): Int

    /**
     * 오늘(KST) 시그널이 ≥1건 발생한 전략 목록 — 콤보박스용 "오늘 활동 전략" 조회 (읽기 전용).
     *
     * 정의(사용자 결재): side/risk_status 무관(시그널 발생 자체 기준), `created_at >= :fromInstant`(KST 자정),
     * DELETED 전략 제외, id 오름차순. name = strategy.name_ko 우선, 없으면 name.
     *
     * signal.user_id(String)로 그 사용자 시그널만 추리고 strategy(id)와 join 한다. native query — DISTINCT 로
     * 전략당 1행. 헌법 §6 (ADR 0051): @Query 명시 — query inference 함정 회피, body 0 abstract fun.
     */
    @Query(
        value =
            "SELECT DISTINCT st.id AS id, COALESCE(st.name_ko, st.name) AS name " +
                "FROM signal sg " +
                "JOIN strategy st ON st.id = sg.strategy_id " +
                "WHERE sg.user_id = :userId " +
                "AND sg.created_at >= :fromInstant " +
                "AND st.status <> 'DELETED' " +
                "ORDER BY st.id ASC",
        nativeQuery = true,
    )
    fun findTodayActiveStrategies(
        @Param("userId") userId: String,
        @Param("fromInstant") fromInstant: Instant,
    ): List<ActiveStrategyRow>
}

/**
 * 오늘 활동 전략 projection — [SignalJpaRepository.findTodayActiveStrategies] 결과 행.
 * Spring Data interface-based projection (id·name 2컬럼).
 */
interface ActiveStrategyRow {
    val id: Long
    val name: String
}
