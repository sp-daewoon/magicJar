package com.mj.consumer.infrastructure.persistence.guard

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Entity
@Table(name = "kill_switch_state")
class KillSwitchEntity(
    @Id
    @Column(name = "scope", length = 16)
    val scope: String,
    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,
    @Column(name = "toggled_by", length = 64)
    val toggledBy: String? = null,
    @Column(name = "toggled_at", nullable = false)
    val toggledAt: OffsetDateTime = OffsetDateTime.now(),
)

interface KillSwitchJpaRepository : JpaRepository<KillSwitchEntity, String> {
    @Modifying
    @Query(
        """UPDATE KillSwitchEntity k SET k.isActive = :active, k.reason = :reason,
                k.toggledBy = :who, k.toggledAt = CURRENT_TIMESTAMP WHERE k.scope = :scope""",
    )
    fun toggle(
        @Param("scope") scope: String,
        @Param("active") active: Boolean,
        @Param("reason") reason: String?,
        @Param("who") who: String,
    ): Int
}

@Component
@Transactional
class KillSwitchJpaAdapter(
    private val repo: KillSwitchJpaRepository,
) {
    fun isActive(scope: String = "GLOBAL"): Boolean = repo.findById(scope).orElse(null)?.isActive ?: true // 미존재면 보수적으로 ON

    fun toggle(
        scope: String,
        active: Boolean,
        reason: String?,
        who: String,
    ) {
        repo.toggle(scope, active, reason, who)
    }
}
