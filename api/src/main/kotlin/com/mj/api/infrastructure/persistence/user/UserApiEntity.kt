package com.mj.api.infrastructure.persistence.user

import com.mj.domain.user.TradingMode
import com.mj.domain.user.User
import com.mj.domain.user.UserTradingConfig
import com.mj.domain.user.port.UserRepository
import com.mj.domain.user.port.UserTradingConfigRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

// ─── users ───────────────────────────────────────────────────────────────────

@Entity
@Table(name = "users")
class UserApiEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    @Column(name = "username", nullable = false, unique = true, length = 64)
    val username: String,
    @Column(name = "display_name", length = 128)
    val displayName: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime,
) {
    fun toDomain(): User = User(userId, username, displayName, createdAt, updatedAt)

    companion object {
        fun from(u: User): UserApiEntity = UserApiEntity(u.userId, u.username, u.displayName, u.createdAt, u.updatedAt)
    }
}

interface UserApiJpaRepository : JpaRepository<UserApiEntity, UUID>

@Component
class UserApiJpaAdapter(
    private val repo: UserApiJpaRepository,
) : UserRepository {
    override fun findById(userId: UUID): User? = repo.findById(userId).orElse(null)?.toDomain()

    override fun save(user: User): User = repo.save(UserApiEntity.from(user)).toDomain()
}

// ─── user_trading_config ──────────────────────────────────────────────────────

@Entity
@Table(name = "user_trading_config")
class UserTradingConfigApiEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "trading_mode", nullable = false, length = 16)
    val tradingMode: TradingMode,
    @Column(name = "account_no", nullable = false, length = 20)
    val accountNo: String,
    @Column(name = "account_product_code", nullable = false, length = 2)
    val accountProductCode: String,
    @Column(name = "kis_app_key_encrypted", nullable = false)
    val kisAppKeyEncrypted: String,
    @Column(name = "kis_app_secret_encrypted", nullable = false)
    val kisAppSecretEncrypted: String,
    @Column(name = "hts_id", nullable = false, length = 64)
    val htsId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime,
) {
    fun toDomain(): UserTradingConfig =
        UserTradingConfig(
            userId,
            tradingMode,
            accountNo,
            accountProductCode,
            kisAppKeyEncrypted,
            kisAppSecretEncrypted,
            htsId,
            createdAt,
            updatedAt,
        )

    companion object {
        fun from(c: UserTradingConfig): UserTradingConfigApiEntity =
            UserTradingConfigApiEntity(
                c.userId,
                c.tradingMode,
                c.accountNo,
                c.accountProductCode,
                c.kisAppKeyEncrypted,
                c.kisAppSecretEncrypted,
                c.htsId,
                c.createdAt,
                c.updatedAt,
            )
    }
}

interface UserTradingConfigApiJpaRepository : JpaRepository<UserTradingConfigApiEntity, UUID>

@Component
class UserTradingConfigApiJpaAdapter(
    private val repo: UserTradingConfigApiJpaRepository,
) : UserTradingConfigRepository {
    override fun findByUserId(userId: UUID): UserTradingConfig? = repo.findById(userId).orElse(null)?.toDomain()

    override fun save(config: UserTradingConfig): UserTradingConfig = repo.save(UserTradingConfigApiEntity.from(config)).toDomain()
}
