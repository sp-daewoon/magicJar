package com.mj.consumer.infrastructure.persistence.journal

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.domain.journal.NewsItem
import com.mj.domain.journal.port.NewsRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Primary
@Transactional
class NewsRepositoryAdapter(
    private val jpa: NewsJpaRepository,
    private val mapper: ObjectMapper,
) : NewsRepository {
    override fun save(items: List<NewsItem>) {
        if (items.isEmpty()) return
        jpa.saveAll(items.map { it.toJpa(mapper) })
    }

    @Transactional(readOnly = true)
    override fun search(
        code: String?,
        from: Instant,
        to: Instant,
    ): List<NewsItem> =
        jpa
            .findByPublishedAtBetween(from, to)
            .filter { code == null || mapper.readValue(it.instrumentsJson, List::class.java).contains(code) }
            .map { it.toDomain(mapper) }
}
