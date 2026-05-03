package com.mj.api.infrastructure.persistence.screener

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ScreenMatchJpaRepository : JpaRepository<ScreenMatchJpa, Long> {
    fun findByScreenRunId(
        screenRunId: Long,
        pageable: Pageable,
    ): Page<ScreenMatchJpa>

    fun findByScreenRunId(screenRunId: Long): List<ScreenMatchJpa>
}
