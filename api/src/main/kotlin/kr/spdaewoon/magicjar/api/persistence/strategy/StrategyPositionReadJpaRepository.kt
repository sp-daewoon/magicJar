package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * strategy_position **read-only** Repository (api 모듈 — ADR 0110 V64, 분할매수 R3-a).
 *
 * 전략별 포지션 표시용 조회 전용. 쓰기(upsert)는 consumer 모듈 책임.
 * 헌법 §6 (ADR 0051): body 가진 default fun 0건 — query inference 메서드만.
 */
@Repository
interface StrategyPositionReadJpaRepository : JpaRepository<StrategyPositionReadJpaEntity, Long> {
    /**
     * owner=AUTOPILOT 전략이 quantity>0 으로 보유 중인 종목 코드 집합 — ADR 0131 hold-until-exit 가시화(§6-3).
     *
     * universe 재생성 시 "직전 universe ∖ 신규 universe" 회전 종목 중 보유 lot 잔존 종목을 집계하는 입력.
     * strategy_position.strategy_id 를 strategy.id 와 join 해 owner=AUTOPILOT 으로 격리(USER/SYSTEM 보유분 미포함).
     * read-only. 헌법 §6 (ADR 0051): abstract fun(body 0) — 집계 로직은 @Query 위임.
     */
    @Query(
        value =
            "SELECT DISTINCT p.code FROM strategy_position p " +
                "JOIN strategy s ON s.id = p.strategy_id " +
                "WHERE s.owner = 'AUTOPILOT' AND p.quantity > 0",
        nativeQuery = true,
    )
    fun findAutopilotHeldCodes(): List<String>

    /** 전략 단위 보유 행 조회(UQ user+strategy+code라 전략당 종목별 1행 — 전략에 종목 1개면 단건). */
    fun findByStrategyId(strategyId: Long): List<StrategyPositionReadJpaEntity>

    /**
     * ADR 0115 §2-B 키 사슬 추적성 (불변식 #4) — user+strategy+code 단건 보유 조회 (read-only).
     *
     * 시그널 추적 드릴다운에서 "현재 그 전략이 이 종목 몇 주 보유" 노출. UQ라 0~1행.
     */
    fun findByUserIdAndStrategyIdAndCode(
        userId: String,
        strategyId: Long,
        code: String,
    ): StrategyPositionReadJpaEntity?
}
