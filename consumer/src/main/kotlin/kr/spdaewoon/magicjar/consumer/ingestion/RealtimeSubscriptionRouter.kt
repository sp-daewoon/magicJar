package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import kr.spdaewoon.magicjar.domain.market.port.TodayCandleSeedPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
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
    private val todayCandleSeedPort: TodayCandleSeedPort,
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

    /**
     * 구독 수립 시점 today-Hash 시드 전용 executor (단일 스레드).
     *
     * 시드는 공식 KIS REST 1콜(blocking ~최대 3s)이므로 router lock·caller path를 막지 않도록
     * lock 해제 후 비동기로 실행한다(자본/시세 path 지연 0). 시드 자체 실패는 graceful — 1d tick-only 합성 없이 DB/quote fallback.
     */
    private val seedExecutor =
        Executors.newSingleThreadExecutor(
            ThreadFactory { r -> Thread(r, "today-candle-seed").also { it.isDaemon = true } },
        )

    /**
     * 시드 in-flight/최근 완료 dedup — 짧은 시간 내 동일 code 재구독(DETAIL 재진입·STRATEGY 재후보)에
     * 중복 KIS 호출을 막는다. 값 = 마지막 시드 제출 시각(ms). cooldown 내 재제출 skip.
     */
    private val recentSeedMs: MutableMap<String, Long> = ConcurrentHashMap()
    private val seedCooldownMs = 60_000L

    private val detailDebounceMs = 200L
    private val detailTtlMs = 5L * 60L * 1000L
    private val strategyTtlMs = 30L * 60L * 1000L

    // ADR 0114 §4-3 P3 — 보유 종목 구독 TTL. 60초 refresh가 갱신하므로 5분 grace로 일시 refresh 실패에도 끊김 방지.
    private val holdingTtlMs = 5L * 60L * 1000L

    // ADR 0114 §4-3 P3(Codex pending-buy 흡수) — 주문 제출~체결 사이 watch. 체결 이벤트가 release하나 미체결/취소 대비 TTL.
    private val pendingBuyTtlMs = 30L * 60L * 1000L

    fun request(
        owner: RealtimeDemandOwner,
        ownerId: String,
        code: String,
        priority: Int = owner.defaultPriority,
        ttlSeconds: Long? = null,
    ) {
        val newlySubscribed =
            lock.withLock {
                val now = System.currentTimeMillis()
                cleanupExpiredLocked(now)

                if (owner == RealtimeDemandOwner.DETAIL) {
                    pendingDebounce.entries.removeIf { (_, until) -> now > until }
                    val pendingUntil = pendingDebounce[code]
                    if (pendingUntil != null && now < pendingUntil) {
                        log.debug("realtime demand debounce skip: owner={} code={}", owner, code)
                        return@withLock false
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

                val subscribed = ensureSubscribedLocked(code, priority)
                log.info(
                    "realtime demand enter: owner={} ownerId={} code={} owners={}",
                    owner,
                    ownerKey.ownerId,
                    code,
                    owners.size,
                )
                subscribed
            }

        // 신규 subscribe는 KisWebSocketBridge.subscribe()가 tick 송신 전에 seed를 먼저 시도한다.
        // 이미 구독 중인 종목은 watchlist/재연결 등 기존 경로에서 seed가 누락됐을 수 있어 DETAIL·HOLDING·STRATEGY 진입 때 보강한다.
        if (!newlySubscribed) scheduleSeed(code)
    }

    /**
     * today-Hash 시드 비동기 제출. cooldown 내 중복 제출 skip(동일 code 재구독 시 중복 KIS 호출 방지).
     */
    private fun scheduleSeed(code: String) {
        val now = System.currentTimeMillis()
        val last = recentSeedMs[code]
        if (last != null && now - last < seedCooldownMs) {
            log.debug("today-candle seed cooldown skip: code={}", code)
            return
        }
        recentSeedMs[code] = now
        seedExecutor.submit {
            try {
                todayCandleSeedPort.seedTodayCandle(code)
            } catch (e: Exception) {
                log.warn("today-candle seed 비동기 실패(graceful — DB/quote fallback): code={} err={}", code, e.message)
            }
        }
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
        // 만료된 시드 cooldown 정리 — 재구독 시 fresh 시드 허용.
        val now = System.currentTimeMillis()
        recentSeedMs.entries.removeIf { (_, ts) -> now - ts > seedCooldownMs }
    }

    @PreDestroy
    fun shutdown() {
        seedExecutor.shutdown()
    }

    /**
     * @return WS 구독을 새로 발사했으면 true (seed는 KisWsPort.subscribe 내부에서 tick 송신 전 수행). 이미 구독 중·큐잉이면 false.
     */
    private fun ensureSubscribedLocked(
        code: String,
        priority: Int,
    ): Boolean {
        kisWsPort.connect()
        val subscribed = kisWsPort.subscribedCodes()
        if (code in subscribed) return false

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
                return false
            }
            kisWsPort.unsubscribe(listOf(victim))
            demoteWatchlistToPollingLocked(victim)
            log.info("realtime demand evict watchlist: victim={} new={} priority={}", victim, code, priority)
        }

        kisWsPort.subscribe(listOf(code))
        return true
    }

    private fun findEvictionCandidateLocked(
        subscribed: Set<String>,
        newPriority: Int,
    ): String? {
        val demandedCodes = demandsByCode.keys
        val watchlistEntries = watchlistRepository.findAll()
        val watchlistByCode = watchlistEntries.groupBy { it.code }
        // ADR 0114 §4-3 P3 — HOLDING owner code는 evict 절대 금지(보유 종목 구독 끊김 = 청산 재료 공백 = 자본 risk).
        // priority 0 비교만으로는 부족(동일 priority 경합 시 후보화 가능) → 명시적 제외 LOCK.
        val holdingCodes = codesWithHoldingDemandLocked()

        return subscribed
            .filter { it !in demandedCodes && it !in holdingCodes }
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

    /** HOLDING owner demand가 있는 code 집합 (ADR 0114 §4-3 — evict 후보에서 무조건 제외). 호출자가 lock 보유. */
    private fun codesWithHoldingDemandLocked(): Set<String> =
        demandsByCode
            .filterValues { owners -> owners.keys.any { it.owner == RealtimeDemandOwner.HOLDING } }
            .keys

    private fun RealtimeDemandOwner.defaultTtlMs(): Long =
        when (this) {
            RealtimeDemandOwner.DETAIL -> detailTtlMs
            RealtimeDemandOwner.STRATEGY -> strategyTtlMs
            // HOLDING은 보유 동안 refresh로 갱신(60초 주기) — 기본 TTL은 refresh 주기보다 넉넉(grace 포함).
            RealtimeDemandOwner.HOLDING -> holdingTtlMs
            RealtimeDemandOwner.PENDING_BUY -> pendingBuyTtlMs
        }
}

enum class RealtimeDemandOwner(
    val defaultPriority: Int,
) {
    // ADR 0114 §4-3 P3 — 보유 종목(체결 후 quantity>0). 최우선·evict 절대 금지(findEvictionCandidateLocked 제외 LOCK).
    HOLDING(defaultPriority = 0),

    // ADR 0114 §4-3 P3(Codex 흡수) — 주문 제출~체결 사이 pending watch. 체결 전 구독 보장(체결 통보 수신 보장).
    PENDING_BUY(defaultPriority = 5),
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
