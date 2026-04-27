package com.mj.api.infrastructure.persistence.snapshot

import com.mj.domain.journal.ChartSnapshot
import com.mj.domain.journal.SnapshotSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "chart_snapshot",
    indexes = [
        Index(name = "idx_chart_snapshot_trade_id", columnList = "trade_id"),
    ],
)
class ChartSnapshotJpa(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,
    @Column(name = "trade_id", nullable = false)
    var tradeId: UUID,
    @Column(name = "symbol", length = 20, nullable = false)
    var symbol: String,
    @Column(name = "timeframe", length = 16, nullable = false)
    var timeframe: String,
    @Column(name = "captured_at", nullable = false)
    var capturedAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    var source: SnapshotSource,
    @Column(name = "storage_key", length = 512, nullable = false)
    var storageKey: String,
    @Column(name = "public_url", length = 1024, nullable = false)
    var publicUrl: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", columnDefinition = "jsonb", nullable = false)
    var metaJson: String = "{}",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

fun ChartSnapshotJpa.toDomain(mapper: com.fasterxml.jackson.databind.ObjectMapper): ChartSnapshot =
    ChartSnapshot(
        id = id,
        tradeId = tradeId,
        symbol = symbol,
        timeframe = timeframe,
        capturedAt = capturedAt,
        source = source,
        storageKey = storageKey,
        publicUrl = publicUrl,
        meta = mapper.readValue(metaJson, mapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)),
    )

fun ChartSnapshot.toJpa(mapper: com.fasterxml.jackson.databind.ObjectMapper): ChartSnapshotJpa =
    ChartSnapshotJpa(
        id = id,
        tradeId = tradeId,
        symbol = symbol,
        timeframe = timeframe,
        capturedAt = capturedAt,
        source = source,
        storageKey = storageKey,
        publicUrl = publicUrl,
        metaJson = mapper.writeValueAsString(meta),
        createdAt = Instant.now(),
    )
