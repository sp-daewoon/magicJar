package com.mj.domain.news.event

import java.time.Instant
import java.util.UUID

/** Kafka 토픽 news.raw.v1 메시지 스키마 — 크롤러/pyworker가 발행하는 원시 뉴스 */
data class NewsRawEvent(
    val newsId: UUID,
    val source: String,
    val publishedAt: Instant,
    val title: String,
    val body: String,
    val instruments: List<String> = emptyList(),
)
