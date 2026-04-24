package kr.spdaewoon.magicjar.core.domain

import java.time.Instant
import java.util.UUID

data class NewsItem(
    val id: UUID,
    // "DART" | "NAVER" | ...
    val source: String,
    val publishedAt: Instant,
    val title: String,
    val body: String,
    val instruments: List<String>,
)
