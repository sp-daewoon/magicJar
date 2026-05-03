package com.mj.api.infrastructure.persistence.screener

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ScreenRunJpaRepository : JpaRepository<ScreenRunJpa, Long> {
    fun findByRunId(runId: String): ScreenRunJpa?

    fun findByUserIdOrderByCreatedAtDesc(
        userId: Long?,
        pageable: Pageable,
    ): Page<ScreenRunJpa>
}
