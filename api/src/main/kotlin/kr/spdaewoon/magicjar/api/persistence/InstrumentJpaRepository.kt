package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * instrument Spring Data JPA Repository.
 */
interface InstrumentJpaRepository : JpaRepository<InstrumentJpaEntity, String> {
    fun existsByCode(code: String): Boolean
}
