package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * strategy repository for consumer-side realtime signal evaluation.
 */
interface ConsumerStrategyJpaRepository : JpaRepository<ConsumerStrategyJpaEntity, Long> {
    /**
     * 실시간 BUY 신호 구동 평가 대상 — ACTIVE/BUY_BLOCKED 전략(종목·인터벌 매칭).
     *
     * ## owner 집합 파라미터화 (magicjar-autopilot — spec 2026-06-22 §2 Phase 1 wiring)
     * 기존 `owner = 'USER'` 하드필터를 **owner 집합**으로 연다. 호출자([ConsumerStrategyLookupJpaAdapter])가
     * `magicjar.autopilot.enabled`에 연동해 전달:
     * - enabled=false → `['USER']` → AUTOPILOT 평가 0 = **v3.14.0 byte 동일(zero-regression)**.
     * - enabled=true  → `['USER','AUTOPILOT']` → 루프 생성 전략(owner=AUTOPILOT)도 BUY 평가 진입.
     *
     * `owner IN (:owners)` — Spring Data 가 `List<String>` 을 IN 컬렉션으로 확장 바인딩(native query 지원).
     * owners 는 항상 최소 `['USER']`(non-empty)이므로 빈 IN 위험 0. 헌법 §6 — 명시 @Query(native). default-fun 함정 회피.
     */
    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE owner IN (:owners) " +
                "AND status IN ('ACTIVE', 'BUY_BLOCKED') " +
                "AND codes @> CAST(:codeJson AS jsonb) " +
                "AND intervals @> CAST(:intervalJson AS jsonb)",
        nativeQuery = true,
    )
    fun findActiveByCodeAndInterval(
        @Param("owners") owners: List<String>,
        @Param("codeJson") codeJson: String,
        @Param("intervalJson") intervalJson: String,
    ): List<ConsumerStrategyJpaEntity>

    /**
     * 시세·캔들 구독 universe 산정용 ACTIVE/BUY_BLOCKED 전략 — owner 집합 파라미터화(autopilot wiring 정합).
     *
     * [findActiveByCodeAndInterval]과 동일 owner 연동 원칙: enabled=false → `['USER']`(byte 동일), enabled=true →
     * `['USER','AUTOPILOT']`. AUTOPILOT 전략 종목이 universe에 빠지면 시세 tick 자체가 안 들어와 BUY 평가가 트리거되지
     * 않으므로 BUY 구동 쿼리와 owner 집합을 일치시킨다.
     */
    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE owner IN (:owners) " +
                "AND status IN ('ACTIVE', 'BUY_BLOCKED') " +
                "AND codes IS NOT NULL",
        nativeQuery = true,
    )
    fun findActiveWithExplicitCodes(
        @Param("owners") owners: List<String>,
    ): List<ConsumerStrategyJpaEntity>

    /**
     * 청산(SELL) 평가용 position-driven 폴백: **보유분(strategy_position.quantity>0)이 있는** 전략 조회.
     *
     * ADR 0114 §4-2 P2 — BUY 게이트([findActiveByCodeAndInterval])와 분리: "전략이 살아 있어야 청산"이 아니라
     * "포지션이 있으면 청산"으로 불변식 전환. strategy_position(quantity>0) 조인으로 보유 없는 행은 평가 대상 0.
     * DELETED는 제외(전략 정의 자체 삭제 — §9 U-6은 KillSwitch alert).
     *
     * ## owner별 비대칭 (magicjar-autopilot — spec 2026-06-22 §2 BUY/청산 비대칭 · 롱온리)
     * 청산은 보유분을 닫는 행위이므로 **enabled·status와 무관하게 항상 가능해야** 한다(kill/rollback 정합 — 루프를
     * 꺼도 이미 산 보유분은 닫혀야 함). owner별로 청산 status 조건을 달리한다:
     * - `owner='USER'`  : 현행 그대로 **DISABLED-with-position** (ACTIVE/BUY_BLOCKED는 [findActiveByCodeAndInterval]가 이미 잡음 — zero-regression).
     * - `owner='AUTOPILOT'`: **status 무관**(DELETED 제외) with-position. enabled=false면 active 쿼리에서 AUTOPILOT이
     *   빠지므로(owners=['USER']), ACTIVE AUTOPILOT 보유분의 청산이 누락되는 갭을 이 쿼리가 메운다 → 청산 항상 보장.
     *
     * 호출자가 두 쿼리(active + 본 쿼리) 결과를 id로 dedup 합집합하므로 USER ACTIVE 중복은 자연 제거된다.
     * 헌법 §6 — 명시 @Query(native). default-fun 함정 회피.
     */
    @Query(
        value =
            "SELECT DISTINCT s.* FROM strategy s " +
                "JOIN strategy_position sp ON sp.strategy_id = s.id AND sp.quantity > 0 " +
                "WHERE s.status <> 'DELETED' " +
                "AND ( (s.owner = 'USER' AND s.status = 'DISABLED') OR s.owner = 'AUTOPILOT' ) " +
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
