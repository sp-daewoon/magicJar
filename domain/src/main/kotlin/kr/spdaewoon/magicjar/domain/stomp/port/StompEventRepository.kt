package kr.spdaewoon.magicjar.domain.stomp.port

import kr.spdaewoon.magicjar.domain.stomp.StompEvent
import java.time.Instant

/**
 * StompEvent 영속 port (헥사고날 — 헌법 §6 정합 / abstract only).
 *
 * 구현체는 `api/persistence/stomp/StompEventRepositoryAdapter` (JPA).
 *
 * ### 메서드
 * - [save]            : publish 직전 INSERT (id null → DB 생성)
 * - [findSince]       : userId + since(>=) + optional channel filter + limit — backfill REST 전용
 * - [deleteOlderThan] : retention 적용 (별도 cron, 본 PR 범위 밖)
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건 — Spring Data query inference 함정 회피.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
interface StompEventRepository {
    fun save(event: StompEvent): StompEvent

    fun findSince(
        userId: Long,
        since: Instant,
        channel: String?,
        limit: Int,
    ): List<StompEvent>

    fun deleteOlderThan(cutoff: Instant): Int
}
