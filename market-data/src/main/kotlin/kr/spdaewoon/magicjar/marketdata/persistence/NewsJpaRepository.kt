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
        """
        SELECT n FROM NewsJpa n
         WHERE n.publishedAt BETWEEN :from AND :to
           AND n.instrumentsJson LIKE CONCAT('%', :code, '%')
         ORDER BY n.publishedAt DESC
        """,
    )
    fun searchByCode(
        @Param("code") code: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<NewsJpa>
}
