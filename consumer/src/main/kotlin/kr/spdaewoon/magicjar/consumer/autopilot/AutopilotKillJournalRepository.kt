package kr.spdaewoon.magicjar.consumer.autopilot

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * `autopilot_kill_journal` Spring Data JPA Repository (consumer · spec 2026-06-22 §2-0-D · Task 4).
 *
 * 헌법 §6 — abstract fun(method-name derivable) + 명시 `@Query` 만. body 가진 default fun 0건.
 * - [save]/[findById] : JpaRepository 상속.
 * - [findFirstByResumedAtIsNullOrderByOccurredAtDesc] : 최신 **미해제**(active) 행 — resume 시 갱신 대상 lookup
 *   (method-name 파생 — resumedAt IS NULL ORDER BY occurredAt DESC LIMIT 1).
 * - [markResumed] : 미해제 행에 resumed_at·resume_reason·corrected_limits 채움(명시 @Query).
 */
interface AutopilotKillJournalRepository : JpaRepository<AutopilotKillJournalEntity, Long> {
    /** 최신 미해제(active) kill 행 — resume 시 갱신 대상. 없으면 null. */
    fun findFirstByResumedAtIsNullOrderByOccurredAtDesc(): AutopilotKillJournalEntity?

    /**
     * 특정 저널 행을 resume 처리(미해제 → 해제). 이미 해제(resumed_at NOT NULL)면 갱신 0(멱등).
     *
     * @return 갱신 행 수(1 = 처리됨 / 0 = 행 부재 또는 이미 해제됨).
     */
    @Modifying
    @Query(
        value =
            "UPDATE autopilot_kill_journal " +
                "SET resumed_at = :resumedAt, resume_reason = :resumeReason, corrected_limits_json = :correctedLimitsJson " +
                "WHERE id = :id AND resumed_at IS NULL",
        nativeQuery = true,
    )
    fun markResumed(
        @Param("id") id: Long,
        @Param("resumedAt") resumedAt: Instant,
        @Param("resumeReason") resumeReason: String,
        @Param("correctedLimitsJson") correctedLimitsJson: String?,
    ): Int
}
