package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * kill_switch_state Spring Data JPA Repository (consumer 모듈).
 */
interface KillSwitchJpaRepository : JpaRepository<KillSwitchJpaEntity, String> {
    /**
     * KillSwitch enable upsert — 부팅 실패 시 auto-ON (ADR 0042 §2-5 + ADR 0043).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO kill_switch_state (user_id, enabled, reason, updated_at, updated_by)
            VALUES (:userId, true, :reason, now(), :updatedBy)
            ON CONFLICT (user_id) DO UPDATE
                SET enabled = true,
                    reason = EXCLUDED.reason,
                    updated_at = now(),
                    updated_by = EXCLUDED.updated_by
        """,
        nativeQuery = true,
    )
    fun enableKillSwitch(
        userId: String,
        reason: String?,
        updatedBy: String,
    )
}
