package com.mj.consumer.infrastructure.persistence.journal

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface NewsJpaRepository : JpaRepository<NewsJpa, UUID> {
    fun findByPublishedAtBetween(
        from: Instant,
        to: Instant,
    ): List<NewsJpa>
}
