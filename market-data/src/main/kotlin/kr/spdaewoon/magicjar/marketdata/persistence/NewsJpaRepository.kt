package kr.spdaewoon.magicjar.marketdata.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface NewsJpaRepository : JpaRepository<NewsJpa, UUID> {
    fun findByPublishedAtBetweenOrderByPublishedAtDesc(
        from: Instant,
        to: Instant,
    ): List<NewsJpa>

    @Query(
        value = """
        SELECT * FROM news_item
         WHERE published_at BETWEEN :from AND :to
           AND instruments::text LIKE CONCAT('%', :code, '%')
         ORDER BY published_at DESC
        """,
        nativeQuery = true,
    )
    fun searchByCode(
        @Param("code") code: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<NewsJpa>
}
