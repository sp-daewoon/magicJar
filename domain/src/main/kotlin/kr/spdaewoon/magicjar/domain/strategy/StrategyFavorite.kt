package kr.spdaewoon.magicjar.domain.strategy

import java.time.Instant

/** 사용자별 전략 즐겨찾기 (localStorage 승격 — spec 2026-06-18 §5.2). */
data class StrategyFavorite(
    val userId: Long,
    val strategyId: Long,
    val createdAt: Instant,
)
