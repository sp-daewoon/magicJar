package kr.spdaewoon.magicjar.marketdata.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.core.domain.NewsItem
import kr.spdaewoon.magicjar.core.port.NewsRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class NewsRepositoryAdapter(
    private val repository: NewsJpaRepository,
    private val mapper: ObjectMapper,
) : NewsRepository {
    @Transactional
    override fun save(items: List<NewsItem>) {
        if (items.isEmpty()) return
        repository.saveAll(items.map { it.toJpa(mapper) })
    }

    @Transactional(readOnly = true)
    override fun search(
        code: String?,
        from: Instant,
        to: Instant,
    ): List<NewsItem> {
        val rows =
            if (code.isNullOrBlank()) {
                repository.findByPublishedAtBetweenOrderByPublishedAtDesc(from, to)
            } else {
                repository.searchByCode(code, from, to)
            }
        return rows.map { it.toDomain(mapper) }
    }
}
