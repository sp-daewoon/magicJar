package com.mj.domain.journal

import java.time.Instant
import java.util.UUID

enum class SnapshotSource { CLIENT_LWC, SERVER_BROWSER }

data class ChartSnapshot(
    val id: UUID,
    val tradeId: UUID,
    val symbol: String,
    val timeframe: String,
    val capturedAt: Instant,
    val source: SnapshotSource,
    val storageKey: String,
    val publicUrl: String,
    val meta: Map<String, String> = emptyMap(),
)
