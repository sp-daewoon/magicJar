package com.mj.domain.journal.port

import com.mj.domain.journal.ChartSnapshot
import java.util.UUID

/**
 * 차트 스크린샷 저장소 포트 (ADR 0026 — 객체 저장소 연동).
 * 구현체(ChartSnapshotAdapter)는 api/infrastructure/persistence 에 위치.
 */
interface ChartSnapshotRepository {
    fun save(snapshot: ChartSnapshot)

    fun findByTradeId(tradeId: UUID): List<ChartSnapshot>

    fun findById(id: UUID): ChartSnapshot?
}
