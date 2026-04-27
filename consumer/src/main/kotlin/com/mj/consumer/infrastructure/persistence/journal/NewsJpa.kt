package com.mj.consumer.infrastructure.persistence.journal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mj.domain.journal.NewsItem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** consumer 전용 NewsItem 엔티티 (NewsAnalyzerListener → news.analyzed → DB 영속). */
@Entity
@Table(
    name = "news_item",
    indexes = [Index(name = "idx_news_published_at", columnList = "published_at")],
)
class NewsJpa(
    @Id @Column(name = "id") var id: UUID,
    @Column(name = "source", length = 32) var source: String,
    @Column(name = "published_at") var publishedAt: Instant,
    @Column(name = "title", length = 512) var title: String,
    @Column(name = "body", columnDefinition = "text") var body: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "instruments", columnDefinition = "jsonb") var instrumentsJson: String,
    @Column(name = "created_at") var createdAt: Instant,
)

fun NewsJpa.toDomain(mapper: ObjectMapper): NewsItem = NewsItem(id, source, publishedAt, title, body, mapper.readValue(instrumentsJson))

fun NewsItem.toJpa(
    mapper: ObjectMapper,
    createdAt: Instant = Instant.now(),
): NewsJpa = NewsJpa(id, source, publishedAt, title, body, mapper.writeValueAsString(instruments), createdAt)
