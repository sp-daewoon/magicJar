package kr.spdaewoon.magicjar.core.domain

import java.time.Instant

data class AiInsight(
    // "sentiment" | "strategy-explain" | ...
    val topic: String,
    val payloadJson: String,
    val reasoning: String,
    val createdAt: Instant,
)
