package com.mj.api.infrastructure.persistence.market

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

/**
 * 뉴스/공시 영속 엔티티.
 *
 * `instruments` 컬럼은 jsonb. Hibernate 6 의 SqlTypes.JSON 으로 String 으로 저장 후
 * 도메인 변환 시점에 ObjectMapper 로 List<String> 으로 환원한다.
 */
@Entity
@Table(
    name = "news_item",
    indexes = [
        Index(name = "idx_news_published_at", columnList = "published_at"),
    ],
)
class NewsJpa(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,
    @Column(name = "source", length = 32, nullable = false)
    var source: String,
    @Column(name = "published_at", nullable = false)
    var publishedAt: Instant,
    @Column(name = "title", length = 512, nullable = false)
    var title: String,
    @Column(name = "body", columnDefinition = "text", nullable = false)
    var body: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "instruments", columnDefinition = "jsonb", nullable = false)
    var instrumentsJson: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
)

fun NewsJpa.toDomain(mapper: ObjectMapper): NewsItem =
    NewsItem(
        id = id,
        source = source,
        publishedAt = publishedAt,
        title = title,
        body = body,
        instruments = mapper.readValue(instrumentsJson),
    )

fun NewsItem.toJpa(
    mapper: ObjectMapper,
    createdAt: Instant = Instant.now(),
): NewsJpa =
    NewsJpa(
        id = id,
        source = source,
        publishedAt = publishedAt,
        title = title,
        body = body,
        instrumentsJson = mapper.writeValueAsString(instruments),
        createdAt = createdAt,
    )
