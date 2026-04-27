package com.mj.domain.strategy.event

import com.mj.domain.common.Side
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Kafka 토픽 signal.v1 메시지 스키마 — 전략 DSL 평가 결과 */
data class SignalEvent(
    val signalId: UUID,
    val strategyId: String,
    val code: String,
    val side: Side,
    val strength: BigDecimal,
    val reason: String,
    val snapshot: Map<String, BigDecimal>,
    val generatedAt: Instant,
)
