package com.mj.domain.journal

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ChartSnapshotTest {
    @Test
    fun `ChartSnapshot은 모든 필수 필드를 가진다`() {
        val s =
            ChartSnapshot(
                id = UUID.randomUUID(),
                tradeId = UUID.randomUUID(),
                symbol = "005930",
                timeframe = "1m",
                capturedAt = Instant.parse("2026-04-27T00:00:00Z"),
                source = SnapshotSource.CLIENT_LWC,
                storageKey = "snapshots/2026-04-27/abc.png",
                publicUrl = "https://minio.local/snapshots/2026-04-27/abc.png",
            )
        s.symbol shouldBe "005930"
        s.source shouldBe SnapshotSource.CLIENT_LWC
        s.meta.size shouldBe 0
    }
}
