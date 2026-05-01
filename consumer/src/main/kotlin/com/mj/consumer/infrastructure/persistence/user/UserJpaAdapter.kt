package com.mj.consumer.infrastructure.persistence.user

import com.mj.domain.user.User
import com.mj.domain.user.port.UserRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(
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
    fun toDomain(): User =
        User(
            userId = userId,
            username = username,
            displayName = displayName,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    companion object {
        fun from(u: User): UserEntity = UserEntity(u.userId, u.username, u.displayName, u.createdAt, u.updatedAt)
    }
}

interface UserJpaRepository : JpaRepository<UserEntity, UUID>

@Component
class UserJpaAdapter(
    private val repo: UserJpaRepository,
) : UserRepository {
    override fun findById(userId: UUID): User? = repo.findById(userId).orElse(null)?.toDomain()

    override fun save(user: User): User = repo.save(UserEntity.from(user)).toDomain()
}
