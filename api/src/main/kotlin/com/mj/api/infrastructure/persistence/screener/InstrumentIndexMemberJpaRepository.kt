package com.mj.api.infrastructure.persistence.screener

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface InstrumentIndexMemberJpaRepository : JpaRepository<InstrumentIndexMemberJpa, Long> {
    /**
     * 현재 구성 종목 코드 반환 (removed_at IS NULL = 현재 구성원).
     */
    @Query("SELECT m.code FROM InstrumentIndexMemberJpa m WHERE m.indexGroup = :indexGroup AND m.removedAt IS NULL")
    fun findActiveCodesByIndexGroup(indexGroup: String): List<String>
}
