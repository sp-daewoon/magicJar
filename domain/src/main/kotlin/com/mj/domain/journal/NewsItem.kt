package com.mj.domain.journal

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
