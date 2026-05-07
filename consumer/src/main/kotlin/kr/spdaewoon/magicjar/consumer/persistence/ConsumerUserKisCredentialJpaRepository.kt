package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * consumer 모듈의 user_kis_credential JPA Repository.
 * Bootstrap 활성 사용자 조회 전용 (ADR 0042 §2-5).
 */
interface ConsumerUserKisCredentialJpaRepository : JpaRepository<ConsumerUserKisCredentialJpaEntity, ConsumerUserKisCredentialId> {
    @Query("SELECT c FROM ConsumerUserKisCredentialJpaEntity c WHERE c.id.accountType = :accountType")
    fun findAllByAccountType(accountType: String): List<ConsumerUserKisCredentialJpaEntity>
}
