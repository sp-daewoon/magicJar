package kr.spdaewoon.magicjar.consumer.autopilot

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * autopilot 페이퍼 섀도 트레이드 Repository (개선 G).
 *
 * abstract fun + 명시 `@Query` 만 — body 가진 default fun 0(헌법 §6 Spring Data query inference 함정 회피).
 */
interface AutopilotPaperShadowJpaRepository : JpaRepository<AutopilotPaperShadowJpaEntity, Long> {
    /**
     * 미결(OPEN) 섀도 행을 진입순(ASC)으로 limit 개 조회 — 스코어러가 한 주기에 처리할 batch.
     * V93 부분 인덱스(idx_autopilot_paper_shadow_open) 활용.
     */
    @Query(
        "SELECT s FROM AutopilotPaperShadowJpaEntity s WHERE s.status = 'OPEN' ORDER BY s.entryTs ASC",
    )
    fun findOpen(pageable: Pageable): List<AutopilotPaperShadowJpaEntity>

    /**
     * 같은 cycle 종목 중복 섀도 방지 — 같은 (strategyId, code) 의 미결 OPEN 섀도가 이미 있는지.
     * 실 dedup(AutopilotCodeBuyDedupeGuard)과 별개로 섀도 장부의 중복 누적을 막는다(같은 가설·같은 종목 OPEN 1건).
     */
    @Query(
        "SELECT COUNT(s) FROM AutopilotPaperShadowJpaEntity s " +
            "WHERE s.strategyId = :strategyId AND s.code = :code AND s.status = 'OPEN'",
    )
    fun countOpenByStrategyAndCode(
        @Param("strategyId") strategyId: Long,
        @Param("code") code: String,
    ): Long

    /**
     * 전략별 페이퍼 점수 rollup — 가설(전략)별 (resolved 건수, 승, 손, net PnL 합)을 한 행으로 집계.
     *
     * 결과 컬럼 순서: [strategyId, resolvedCount, winCount, lossCount, netPnlSum]. autopilot 루프/저널이
     * "어느 가설이 먹히나"(승률·기대값)를 읽는 학습 신호 단일 쿼리. OPEN(미결)은 집계 제외(승패 미확정).
     */
    @Query(
        "SELECT s.strategyId, " +
            "COUNT(s), " +
            "SUM(CASE WHEN s.status = 'WIN' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN s.status = 'LOSS' THEN 1 ELSE 0 END), " +
            "COALESCE(SUM(s.realizedPnl), 0) " +
            "FROM AutopilotPaperShadowJpaEntity s " +
            "WHERE s.status <> 'OPEN' " +
            "GROUP BY s.strategyId",
    )
    fun scoreByStrategy(): List<Array<Any>>
}
