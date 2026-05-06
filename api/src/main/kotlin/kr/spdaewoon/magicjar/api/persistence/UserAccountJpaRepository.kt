package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * user_account Spring Data JPA Repository.
 * existsAny() — OnboardingGuardFilter + onboarding status 조회 용.
 */
interface UserAccountJpaRepository : JpaRepository<UserAccountJpaEntity, String> {
    @Query("SELECT COUNT(u) > 0 FROM UserAccountJpaEntity u")
    fun existsAny(): Boolean
}
