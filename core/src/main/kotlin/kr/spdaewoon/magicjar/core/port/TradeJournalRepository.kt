package kr.spdaewoon.magicjar.core.port

import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.Side
import kr.spdaewoon.magicjar.core.domain.TradeJournalEntry
import java.time.Instant
import java.util.UUID

/**
 * 매매일지 저장소 포트. 구현체는 strategy 모듈이 제공한다 (ADR 0007).
 * 매매 진입/청산 시 `TradeJournalRecorder`가 이 포트를 호출해 기록한다.
 */
interface TradeJournalRepository {
    fun save(entry: TradeJournalEntry)

    fun find(id: UUID): TradeJournalEntry?

    fun search(filter: TradeJournalFilter): List<TradeJournalEntry>
}

data class TradeJournalFilter(
    val mode: Mode? = null,
    val code: String? = null,
    val side: Side? = null,
    val strategyId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val tag: String? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)
