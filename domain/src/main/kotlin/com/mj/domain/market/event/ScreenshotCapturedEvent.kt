package com.mj.domain.market.event

import java.time.Instant
import java.util.UUID

data class ScreenshotCapturedEvent(
    val captureId: UUID,
    val source: String,
    val url: String,
    val storageKey: String,
    val publicUrl: String,
    val capturedAt: Instant,
    val tradeId: UUID? = null,
    val symbol: String? = null,
    val timeframe: String? = null,
    val width: Int,
    val height: Int,
)
