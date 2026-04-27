package com.mj.domain.news.event

import java.time.Instant
import java.util.UUID

/** Kafka 토픽 news.analyzed.v1 메시지 스키마 — AI 감성분석 완료 뉴스 */
data class NewsAnalyzedEvent(
    val newsId: UUID,
    val source: String,
    val publishedAt: Instant,
    val title: String,
    val sentiment: String,
    val sentimentScore: Double,
    val summary: String?,
    val instruments: List<String> = emptyList(),
    val analyzedAt: Instant,
)
