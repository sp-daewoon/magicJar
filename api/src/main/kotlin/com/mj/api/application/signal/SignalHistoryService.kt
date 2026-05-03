package com.mj.api.application.signal

import com.mj.domain.strategy.signal.event.SignalGeneratedEvent
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Signal 이력 in-memory store (트랙11 R1).
 *
 * Kafka `signal.generated.v1` listener가 record 호출 → 메모리 ring buffer 누적.
 * R2에서 DB 영속 (`signal_history` 테이블 + Flyway) + 페이지네이션 + Redis stream 등 본격.
 *
 * 동시성: ConcurrentLinkedDeque + size cap (FIFO eviction).
 *
 * 결정론:
 *   - 같은 publish 순서 → 같은 history 순서.
 *   - id·timestamp는 SignalGeneratedEvent에 이미 stamping됨.
 */
@Service
class SignalHistoryService(
    private val maxEntries: Int = 1000,
) {
    private val store: ConcurrentLinkedDeque<SignalGeneratedEvent> = ConcurrentLinkedDeque()

    fun record(event: SignalGeneratedEvent) {
        store.addLast(event)
        while (store.size > maxEntries) store.pollFirst()
    }

    /**
     * 이력 조회.
     *
     * @param userId 사용자 필터 (null 시 전체. null userId 이벤트는 매칭 안 됨)
     * @param from   시작 시각 inclusive (null 시 처음부터)
     * @param to     끝 시각 exclusive (null 시 끝까지)
     * @param limit  최대 N건. default 100, max 1000
     * @return 시간 내림차순(최신 우선)
     */
    fun query(
        userId: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 100,
    ): List<SignalGeneratedEvent> {
        val effectiveLimit = limit.coerceIn(1, maxEntries)
        // 최신부터 역순 traverse — 일찍 limit 도달 시 short-circuit.
        val out = ArrayList<SignalGeneratedEvent>(effectiveLimit)
        val it = store.descendingIterator()
        while (it.hasNext() && out.size < effectiveLimit) {
            val ev = it.next()
            if (userId != null && ev.userId != userId) continue
            if (from != null && ev.generatedAt.isBefore(from)) continue
            if (to != null && !ev.generatedAt.isBefore(to)) continue
            out += ev
        }
        return out
    }

    /** 테스트용 reset. */
    fun clear() {
        store.clear()
    }

    /** 디버그용. */
    fun size(): Int = store.size
}
