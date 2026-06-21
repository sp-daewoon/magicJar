package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * strategy repository for consumer-side realtime signal evaluation.
 */
interface ConsumerStrategyJpaRepository : JpaRepository<ConsumerStrategyJpaEntity, Long> {
    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE owner = 'USER' " +
                "AND status IN ('ACTIVE', 'BUY_BLOCKED') " +
                "AND codes @> CAST(:codeJson AS jsonb) " +
                "AND intervals @> CAST(:intervalJson AS jsonb)",
        nativeQuery = true,
    )
    fun findActiveByCodeAndInterval(
        @Param("codeJson") codeJson: String,
        @Param("intervalJson") intervalJson: String,
    ): List<ConsumerStrategyJpaEntity>

    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE owner = 'USER' " +
                "AND status IN ('ACTIVE', 'BUY_BLOCKED') " +
                "AND codes IS NOT NULL",
        nativeQuery = true,
    )
    fun findActiveWithExplicitCodes(): List<ConsumerStrategyJpaEntity>

    /**
     * ADR 0114 §4-2 P2 — 청산(SELL) 평가용 position-driven 폴백: **DISABLED여도 strategy_position 보유분이 있는** 전략 조회.
     *
     * BUY 게이트([findActiveByCodeAndInterval] ACTIVE/BUY_BLOCKED만)와 분리 — "전략이 살아 있어야 청산"이 아니라
     * "포지션이 있으면 청산"으로 불변식 전환. DELETED는 제외(전략 정의 자체 삭제 — §9 U-6은 KillSwitch alert).
     * strategy_position(quantity>0) 조인으로 보유 없는 DISABLED는 평가 대상 0(과도 평가 회피).
     *
     * 헌법 §6 — 명시 @Query(native). default-fun 함정 회피.
     */
    @Query(
        value =
            "SELECT DISTINCT s.* FROM strategy s " +
                "JOIN strategy_position sp ON sp.strategy_id = s.id AND sp.quantity > 0 " +
                "WHERE s.owner = 'USER' " +
                "AND s.status = 'DISABLED' " +
                "AND s.codes @> CAST(:codeJson AS jsonb) " +
                "AND s.intervals @> CAST(:intervalJson AS jsonb)",
        nativeQuery = true,
    )
    fun findDisabledWithPositionByCodeAndInterval(
        @Param("codeJson") codeJson: String,
        @Param("intervalJson") intervalJson: String,
    ): List<ConsumerStrategyJpaEntity>

    @Modifying
    @Query(
        value =
            "UPDATE strategy " +
                "SET status = 'DISABLED', " +
                "disabled_reason = :reason, " +
                "last_disabled_at = CURRENT_TIMESTAMP, " +
                "disabled_at = CURRENT_TIMESTAMP, " +
                "updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :id AND status <> 'DELETED'",
        nativeQuery = true,
    )
    fun autoDisable(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Query(
        value =
            "UPDATE strategy " +
                "SET status = 'BUY_BLOCKED', " +
                "disabled_reason = :reason, " +
                "last_disabled_at = CURRENT_TIMESTAMP, " +
                "updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :id AND status <> 'DELETED'",
        nativeQuery = true,
    )
    fun autoBuyBlock(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int

    /**
     * magicjar-autopilot kill switch — owner='AUTOPILOT' 전략 **전부 PAUSED** (spec 2026-06-22 §2-0-B).
     *
     * 격리 LOCK — `owner='AUTOPILOT'` 조건으로 사용자 수동 전략(owner='USER')·후성 오버나잇과 절대 비간섭한다
     * (spec §2-0-C). 이미 PAUSED·DELETED 인 행은 제외(멱등). [kr.spdaewoon.magicjar.consumer.autopilot.AutopilotKillSwitch]
     * 가 KillTriggered·수동 stop 시 호출. 헌법 §6 — 명시 @Query(native).
     *
     * @return PAUSED 로 전환된 행 수.
     */
    @Modifying
    @Query(
        value =
            "UPDATE strategy " +
                "SET status = 'PAUSED', " +
                "paused_at = CURRENT_TIMESTAMP, " +
                "updated_at = CURRENT_TIMESTAMP " +
                "WHERE owner = 'AUTOPILOT' AND status NOT IN ('PAUSED', 'DELETED')",
        nativeQuery = true,
    )
    fun pauseAllAutopilotStrategies(): Int

    /** owner='AUTOPILOT' 활성(미삭제) 전략 수 — kill 알림·상태 가시화용. */
    @Query(
        value = "SELECT COUNT(*) FROM strategy WHERE owner = 'AUTOPILOT' AND status <> 'DELETED'",
        nativeQuery = true,
    )
    fun countAutopilotStrategies(): Long

    /**
     * 특정 전략 id 가 owner='AUTOPILOT' 인지 (1=autopilot / 0=아님) — [kr.spdaewoon.magicjar.consumer.autopilot.AutopilotLossAccumulator]
     * 가 체결 손실 누적 대상 판정에 사용. status 무관(보유분 손실은 PAUSED·DISABLED 여도 누적). 헌법 §6 — 명시 @Query.
     */
    @Query(
        value = "SELECT COUNT(*) FROM strategy WHERE id = :id AND owner = 'AUTOPILOT'",
        nativeQuery = true,
    )
    fun countAutopilotStrategyById(
        @Param("id") id: Long,
    ): Long
}
