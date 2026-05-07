package kr.spdaewoon.magicjar.domain.market.tick

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * KIS WS H0STCNT0 체결가 tick 이벤트 도메인 모델 (ADR 0048 §3-2).
 * - PR-9b market-data가 생성 (dispatch §4-1 LOCK)
 * - producer: KisWebSocketBridge (live) / WatchlistPollingWorker (polling)
 * - consumer: TickToMinuteAggregator (Redis candle 갱신), Kafka market.tick.v1 publish
 */
data class TickEvent(
    val code: String,
    val price: BigDecimal,
    val volume: Long,
    val ts: Instant,
    val source: String = "live",
    val stale: Boolean = false,
)
