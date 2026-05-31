package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 비-watchlist 실시간 가격 구독 라우터.
 *
 * watchlist는 사용자가 고정해서 보는 목록이고, 종목 상세 진입·전략 후보는 별도 수요다.
 * 이 라우터는 DETAIL/STRATEGY 수요를 owner 단위로 추적해 단순 합집합으로 섞이지 않게 한다.
 */
@Component
class RealtimeSubscriptionRouter(
    private val kisWsPort: KisWsPort,
    private val pollingWorker: WatchlistPollingWorker,
    private val watchlistRepository: ConsumerWatchlistRepositoryAdapter,
    private val sourceTracker: RedisSourceTrackerAdapter,
) {
    private val log = LoggerFactory.getLogger(RealtimeSubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    private data class OwnerKey(
        val owner: RealtimeDemandOwner,
        val ownerId: String,
    )

    private data class Demand(
        val priority: Int,
        var expiresAtMs: Long,
        var lastAccessMs: Long,
    )

    private val demandsByCode: MutableMap<String, MutableMap<OwnerKey, Demand>> = linkedMapOf()
    private val pendingDebounce: MutableMap<String, Long> = mutableMapOf()

    private val detailDebounceMs = 200L
    private val detailTtlMs = 5L * 60L * 1000L
    private val strategyTtlMs = 30L * 60L * 1000L

    fun request(
        owner: RealtimeDemandOwner,
        ownerId: String,
        code: String,
        priority: Int = owner.defaultPriority,
        ttlSeconds: Long? = null,
    ) = lock.withLock {
        val now = System.currentTimeMillis()
        cleanupExpiredLocked(now)

        if (owner == RealtimeDemandOwner.DETAIL) {
            pendingDebounce.entries.removeIf { (_, until) -> now > until }
            val pendingUntil = pendingDebounce[code]
            if (pendingUntil != null && now < pendingUntil) {
                log.debug("realtime demand debounce skip: owner={} code={}", owner, code)
                return@withLock
            }
            pendingDebounce[code] = now + detailDebounceMs
        }

        val ownerKey = OwnerKey(owner, ownerId.ifBlank { owner.name })
        val ttlMs = (ttlSeconds?.coerceAtLeast(1L)?.times(1000L)) ?: owner.defaultTtlMs()
        val owners = demandsByCode.getOrPut(code) { linkedMapOf() }
        owners[ownerKey] =
            Demand(
                priority = priority,
                expiresAtMs = now + ttlMs,
                lastAccessMs = now,
            )

        ensureSubscribedLocked(code, priority)
        log.info("realtime demand enter: owner={} ownerId={} code={} owners={}", owner, ownerKey.ownerId, code, owners.size)
    }

    fun release(
        owner: RealtimeDemandOwner,
        ownerId: String,
        code: String,
    ) = lock.withLock {
        val now = System.currentTimeMillis()
        cleanupExpiredLocked(now)

        val ownerKey = OwnerKey(owner, ownerId.ifBlank { owner.name })
        val owners = demandsByCode[code] ?: return@withLock
        val demand = owners[ownerKey] ?: return@withLock

        if (owner == RealtimeDemandOwner.DETAIL) {
            demand.expiresAtMs = now + detailTtlMs
            demand.lastAccessMs = now
            log.info(
                "realtime demand grace: owner={} ownerId={} code={} ttlMs={}",
                owner,
                ownerKey.ownerId,
                code,
                detailTtlMs,
            )
            return@withLock
        }

        owners.remove(ownerKey)
        if (owners.isEmpty()) {
            demandsByCode.remove(code)
            pendingDebounce.remove(code)
            releaseIfUnownedLocked(code)
        }
        log.info("realtime demand exit: owner={} ownerId={} code={} remaining={}", owner, ownerKey.ownerId, code, owners.size)
    }

    fun isExternallyDemanded(code: String): Boolean =
        lock.withLock {
            cleanupExpiredLocked(System.currentTimeMillis())
            demandsByCode[code]?.isNotEmpty() == true
        }

    fun activeCodes(): Set<String> =
        lock.withLock {
            cleanupExpiredLocked(System.currentTimeMillis())
            demandsByCode.keys.toSet()
        }

    fun priorityOf(code: String): Int? =
        lock.withLock {
            cleanupExpiredLocked(System.currentTimeMillis())
            demandsByCode[code]?.values?.minOfOrNull { it.priority }
        }

    @Scheduled(fixedRate = 60_000)
    fun evictExpiredDemands() {
        lock.withLock {
            val expiredCodes = cleanupExpiredLocked(System.currentTimeMillis())
            expiredCodes.forEach { releaseIfUnownedLocked(it) }
        }
    }

    private fun ensureSubscribedLocked(
        code: String,
        priority: Int,
    ) {
        kisWsPort.connect()
        val subscribed = kisWsPort.subscribedCodes()
        if (code in subscribed) return

        if (subscribed.size >= SubscriptionPolicy.PRICE_TICK_MAX) {
            val victim = findEvictionCandidateLocked(subscribed, priority)
            if (victim == null) {
                log.warn(
                    "realtime demand queued: code={} priority={} subscribed={} max={}",
                    code,
                    priority,
                    subscribed.size,
                    SubscriptionPolicy.PRICE_TICK_MAX,
                )
                return
            }
            kisWsPort.unsubscribe(listOf(victim))
            demoteWatchlistToPollingLocked(victim)
            log.info("realtime demand evict watchlist: victim={} new={} priority={}", victim, code, priority)
        }

        kisWsPort.subscribe(listOf(code))
    }

    private fun findEvictionCandidateLocked(
        subscribed: Set<String>,
        newPriority: Int,
    ): String? {
        val demandedCodes = demandsByCode.keys
        val watchlistEntries = watchlistRepository.findAll()
        val watchlistByCode = watchlistEntries.groupBy { it.code }

        return subscribed
            .filter { it !in demandedCodes }
            .map { code ->
                val watchlistPriority = watchlistByCode[code]?.maxOfOrNull { it.priority } ?: Int.MAX_VALUE
                code to watchlistPriority
            }.filter { (_, priority) -> priority > newPriority }
            .maxByOrNull { (_, priority) -> priority }
            ?.first
    }

    private fun demoteWatchlistToPollingLocked(code: String) {
        val entries = watchlistRepository.findAll().filter { it.code == code }
        if (entries.isEmpty()) return

        pollingWorker.add(code)
        entries.forEach { sourceTracker.set(it.userId, code, "polling") }
    }

    private fun releaseIfUnownedLocked(code: String) {
        if (demandsByCode[code]?.isNotEmpty() == true) return
        if (isWatchlistCodeLocked(code)) return

        if (code in kisWsPort.subscribedCodes()) {
            kisWsPort.unsubscribe(listOf(code))
            log.info("realtime demand unsubscribed: code={}", code)
        }
    }

    private fun cleanupExpiredLocked(now: Long): Set<String> {
        val changedCodes = mutableSetOf<String>()
        val iterator = demandsByCode.iterator()
        while (iterator.hasNext()) {
            val (code, owners) = iterator.next()
            val before = owners.size
            owners.entries.removeIf { (_, demand) -> now > demand.expiresAtMs }
            if (owners.size != before) changedCodes.add(code)
            if (owners.isEmpty()) {
                iterator.remove()
                pendingDebounce.remove(code)
                changedCodes.add(code)
            }
        }
        return changedCodes
    }

    private fun isWatchlistCodeLocked(code: String): Boolean = watchlistRepository.findAll().any { it.code == code }

    private fun RealtimeDemandOwner.defaultTtlMs(): Long =
        when (this) {
            RealtimeDemandOwner.DETAIL -> detailTtlMs
            RealtimeDemandOwner.STRATEGY -> strategyTtlMs
        }
}

enum class RealtimeDemandOwner(
    val defaultPriority: Int,
) {
    DETAIL(defaultPriority = 10),
    STRATEGY(defaultPriority = 40),
    ;

    companion object {
        fun from(value: String?): RealtimeDemandOwner? =
            value
                ?.trim()
                ?.uppercase()
                ?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}
