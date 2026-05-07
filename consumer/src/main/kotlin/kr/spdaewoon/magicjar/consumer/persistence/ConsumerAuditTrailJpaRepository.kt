package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * audit_trail Spring Data JPA Repository (consumer 모듈).
 */
interface ConsumerAuditTrailJpaRepository : JpaRepository<ConsumerAuditTrailJpaEntity, Long>
