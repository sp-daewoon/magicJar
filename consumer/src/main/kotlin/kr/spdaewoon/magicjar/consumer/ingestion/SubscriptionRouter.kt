package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 구독 라우터 — KIS WS vs polling 분배 의사결정 (ADR 0048 §2-2·§3-3 정합).
 *
 * - slot 예산: H0STCNT0 30개 (SubscriptionPolicy.PRICE_TICK_MAX)
 * - priority 낮은 종목 eviction (priority ASC 정렬 — 낮은 값 = 높은 우선순위)
 * - SymbolDetail·Strategy 수요는 RealtimeSubscriptionRouter가 별도 owner로 보유
 * - thread safety: ReentrantLock (Kafka listener concurrency=1 + 부팅 reconcile 동시 호출 대응)
 * - stateless decision: 매번 KisWsPort.subscribedCodes() + WatchlistRepository 재계산
 */
@Component
class SubscriptionRouter(
    private val kisWsPort: KisWsPort,
    private val pollingWorker: WatchlistPollingWorker,
    private val watchlistRepository: ConsumerWatchlistRepositoryAdapter,
    private val sourceTracker: RedisSourceTrackerAdapter,
    private val realtimeSubscriptionRouter: RealtimeSubscriptionRouter,
) {
    private val log = LoggerFactory.getLogger(SubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    /**
     * 종목 추가 시 slot 여유 검사 + 분배 (ADR 0048 §2-2 §2-6).
     */
    fun add(
        userId: String,
        code: String,
        priority: Int,
    ) = lock.withLock {
        val subscribed = kisWsPort.subscribedCodes()
        val slotsAvailable = SubscriptionPolicy.priceTickSlotsAvailable(subscribed.size)

        if (code in subscribed) {
            sourceTracker.set(userId, code, "live")
            log.info("WS 이미 구독 중 — watchlist source live 동기화: code={}", code)
        } else if (slotsAvailable > 0) {
            // slot 여유 → WS 구독
            kisWsPort.subscribe(listOf(code))
            sourceTracker.set(userId, code, "live")
            log.info("WS 구독 추가: code={} 남은 slot={}", code, slotsAvailable - 1)
        } else {
            // slot 가득 → priority 비교 후 eviction 또는 polling
            val evictionCandidate = findEvictionCandidate(subscribed, priority)
            if (evictionCandidate != null) {
                // eviction: 기존 low-priority 종목 unsubscribe → polling 이동, 새 종목 WS 등록
                log.info(
                    "WS 슬롯 eviction: victim={} priority_victim=? → new={} priority_new={}",
                    evictionCandidate,
                    code,
                    priority,
                )
                kisWsPort.unsubscribe(listOf(evictionCandidate))
                pollingWorker.add(evictionCandidate)
                // eviction 종목 userId 추적 (단일 테넌트 단순화)
                val victimUserId = watchlistRepository.findAll().find { it.code == evictionCandidate }?.userId
                if (victimUserId != null) sourceTracker.set(victimUserId, evictionCandidate, "polling")

                kisWsPort.subscribe(listOf(code))
                sourceTracker.set(userId, code, "live")
            } else {
                // 이 종목이 가장 낮은 priority → polling
                pollingWorker.add(code)
                sourceTracker.set(userId, code, "polling")
                log.info("polling 등록 (slot 가득 + priority 낮음): code={}", code)
            }
        }
    }

    /**
     * 종목 제거 시 unsubscribe + polling 제거 + promotion 재계산 (ADR 0048 §4-3).
     */
    fun remove(
        userId: String,
        code: String,
    ) = lock.withLock {
        val subscribed = kisWsPort.subscribedCodes()
        if (code in subscribed) {
            if (realtimeSubscriptionRouter.isExternallyDemanded(code)) {
                log.info("watchlist 제거 — DETAIL/STRATEGY 수요로 WS 유지: code={}", code)
            } else {
                kisWsPort.unsubscribe(listOf(code))
                log.info("WS 구독 제거: code={}", code)
                // slot 회수 → polling 종목 중 가장 높은 priority 1건 promotion
                promoteFromPolling(userId)
            }
        } else {
            pollingWorker.remove(code)
            log.info("polling 제거: code={}", code)
        }
        sourceTracker.remove(userId, code)
    }

    /**
     * 부팅 reconcile or 세션 변경 시 priority 상위 N개 일괄 결정.
     */
    fun recompute(userId: String) =
        lock.withLock {
            val watchlist = watchlistRepository.findByUserIdOrderByPriority(userId)
            if (watchlist.isEmpty()) return@withLock

            val codes = watchlist.map { it.code }
            val subscribed = kisWsPort.subscribedCodes().toMutableSet()
            val externalDemandCodes = realtimeSubscriptionRouter.activeCodes()

            // 현재 구독 중이지만 watchlist에 없는 코드 제거
            val toUnsubscribe = subscribed.filter { it !in codes && it !in externalDemandCodes }
            if (toUnsubscribe.isNotEmpty()) {
                kisWsPort.unsubscribe(toUnsubscribe)
                toUnsubscribe.forEach { pollingWorker.remove(it) }
            }

            // watchlist priority 상위 N개 → WS, 나머지 → polling
            val externalOnlyCount = externalDemandCodes.count { it !in codes }
            val wsCapacity = (SubscriptionPolicy.PRICE_TICK_MAX - externalOnlyCount).coerceAtLeast(0)
            val toSubscribeWs = codes.take(wsCapacity)
            val toPolling = codes.drop(wsCapacity)

            val newSubscriptions = toSubscribeWs.filter { it !in kisWsPort.subscribedCodes() }
            if (newSubscriptions.isNotEmpty()) {
                kisWsPort.subscribe(newSubscriptions)
                newSubscriptions.forEach { sourceTracker.set(userId, it, "live") }
            }
            toPolling.forEach { code ->
                pollingWorker.add(code)
                sourceTracker.set(userId, code, "polling")
            }
            log.info(
                "recompute 완료: userId={} ws={}건 polling={}건",
                maskUserId(userId),
                toSubscribeWs.size,
                toPolling.size,
            )
        }

    /**
     * 세션 변경 처리 (ADR 0048 §2-5).
     * Closed 진입 → 모두 unsubscribe + polling stop.
     * Regular 복귀 → recompute.
     */
    fun onSessionChanged(
        prev: MarketSession,
        current: MarketSession,
    ) = lock.withLock {
        when (current) {
            is MarketSession.Closed -> {
                log.info("장 마감 — WS 전체 구독 해제")
                val allCodes = kisWsPort.subscribedCodes().toList()
                if (allCodes.isNotEmpty()) kisWsPort.unsubscribe(allCodes)
                pollingWorker.stop()
                // 모든 source idle 처리
                watchlistRepository.findAll().forEach { entry ->
                    sourceTracker.set(entry.userId, entry.code, "idle")
                }
            }
            is MarketSession.Regular -> {
                if (prev is MarketSession.Closed) {
                    log.info("장 개시 — recompute 전체 watchlist")
                    val allUsers = watchlistRepository.findAll().map { it.userId }.distinct()
                    allUsers.forEach { recompute(it) }
                    pollingWorker.start()
                }
            }
            else -> {
                // PreMarket·PostMarket: 1차 단순화 — Regular와 동일 처리 (spec §3-1)
            }
        }
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /**
     * WS 구독 중인 종목 중 새 종목보다 priority 낮은(숫자 큰) 후보 탐색.
     * priority ASC = 낮은 숫자가 더 높은 우선순위.
     */
    private fun findEvictionCandidate(
        subscribed: Set<String>,
        newPriority: Int,
    ): String? {
        val allWatchlist = watchlistRepository.findAll()
        val subscribedEntries =
            allWatchlist
                .filter { it.code in subscribed && !realtimeSubscriptionRouter.isExternallyDemanded(it.code) }
                .sortedByDescending { it.priority } // priority 높은(숫자 큰) 것이 eviction 후보
        val candidate = subscribedEntries.firstOrNull { it.priority > newPriority }
        return candidate?.code
    }

    /**
     * slot 회수 시 polling 중인 종목 중 가장 높은 priority 1건 WS promotion.
     */
    private fun promoteFromPolling(userId: String) {
        val pollingCodes = pollingWorker.poolCodes()
        if (pollingCodes.isEmpty()) return
        val watchlist = watchlistRepository.findByUserIdOrderByPriority(userId)
        val candidate = watchlist.firstOrNull { it.code in pollingCodes } ?: return
        if (kisWsPort.subscribedCodes().size >= SubscriptionPolicy.PRICE_TICK_MAX) return
        pollingWorker.remove(candidate.code)
        kisWsPort.subscribe(listOf(candidate.code))
        sourceTracker.set(userId, candidate.code, "live")
        log.info("polling → WS promotion: code={}", candidate.code)
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
