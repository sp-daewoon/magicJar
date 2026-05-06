package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * user_kis_credential Spring Data JPA Repository.
 */
interface UserKisCredentialJpaRepository : JpaRepository<UserKisCredentialJpaEntity, UserKisCredentialId> {
    @Query(
        "SELECT COUNT(c) > 0 FROM UserKisCredentialJpaEntity c WHERE c.id.userId = :userId AND c.id.accountType = :accountType",
    )
    fun existsByUserIdAndAccountType(
        userId: String,
        accountType: String,
    ): Boolean

    @Query("SELECT COUNT(c) > 0 FROM UserKisCredentialJpaEntity c WHERE c.id.accountType = 'MOCK'")
    fun existsMockAccount(): Boolean
}
