package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 호가 구독 라우터 — KIS WS H0STASP0 LRU 10 slot (ADR 0048 §2-1).
 *
 * 동작:
 * - 사용자 SymbolDetail 진입 → onSymbolDetailEntered(code) → LRU slot sub
 * - 사용자 SymbolDetail 이탈 → onSymbolDetailExited(code) → slot 회수
 * - 10 slot 가득 시 가장 오래된(LRU) 종목 evict + 새 종목 sub
 * - 200ms debounce — 빠른 전환 시 burst 차단 (종목별)
 * - 5분 idle timeout — lastAccess 5분 초과 종목 자동 unsub (backend slot 누수 방지)
 *
 * Thread safety: ReentrantLock
 */
@Component
class OrderbookSubscriptionRouter(
    private val kisWsPort: KisWsPort,
) {
    private val log = LoggerFactory.getLogger(OrderbookSubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    // LRU Map: code → lastAccessMs (insertion-order LinkedHashMap)
    private val lruMap: LinkedHashMap<String, Long> = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = false
    }

    // 200ms debounce: code → pendingUntilMs
    private val pendingDebounce: MutableMap<String, Long> = mutableMapOf()

    // 5분 idle timeout 기준
    private val idleTimeoutMs = 5L * 60L * 1000L // 5분

    // LRU slot 최대 10
    private val maxSlots = 10

    /**
     * 사용자가 SymbolDetail 페이지에 진입 — 호가 sub 요청.
     * 200ms debounce (빠른 전환 burst 차단).
     */
    fun onSymbolDetailEntered(code: String) {
        lock.withLock {
            val now = System.currentTimeMillis()

            // 200ms debounce 체크 — 동일 종목 200ms 내 재진입이면 무시
            val pendingUntil = pendingDebounce[code]
            if (pendingUntil != null && now < pendingUntil) {
                log.debug("orderbook sub debounce skip: code={}", code)
                return
            }
            pendingDebounce[code] = now + 200L

            // 이미 구독 중이면 accessTime 갱신만
            if (lruMap.containsKey(code)) {
                lruMap[code] = now
                log.debug("orderbook sub 재진입 (이미 구독 중): code={}", code)
                return
            }

            // slot 가득 → LRU evict
            if (lruMap.size >= maxSlots) {
                val eldest = lruMap.entries.minByOrNull { it.value }
                if (eldest != null) {
                    lruMap.remove(eldest.key)
                    kisWsPort.unsubscribeOrderbook(listOf(eldest.key))
                    log.info("orderbook LRU evict: evicted={} new={} slot={}/{}", eldest.key, code, lruMap.size, maxSlots)
                }
            }

            // 새 종목 sub
            lruMap[code] = now
            kisWsPort.subscribeOrderbook(listOf(code))
            log.info("orderbook subscribe: code={} slot={}/{}", code, lruMap.size, maxSlots)
        }
    }

    /**
     * 사용자가 SymbolDetail 페이지에서 이탈 — 호가 unsub.
     */
    fun onSymbolDetailExited(code: String) {
        lock.withLock {
            if (lruMap.containsKey(code)) {
                lruMap.remove(code)
                kisWsPort.unsubscribeOrderbook(listOf(code))
                log.info("orderbook unsubscribe: code={} slot={}/{}", code, lruMap.size, maxSlots)
            }
        }
    }

    /**
     * 현재 호가 구독 중인 종목코드 목록.
     */
    fun current(): Set<String> = lock.withLock { lruMap.keys.toSet() }

    /**
     * 5분 idle timeout 체크 — @Scheduled 1분마다.
     * 마지막 접근 후 5분 초과 종목 자동 unsub (frontend 탭 닫기 등 cleanup 실패 보완).
     */
    @Scheduled(fixedRate = 60_000)
    fun evictIdleSubscriptions() {
        val now = System.currentTimeMillis()
        lock.withLock {
            val idleCodes = lruMap.entries
                .filter { (_, lastAccessMs) -> (now - lastAccessMs) > idleTimeoutMs }
                .map { it.key }

            if (idleCodes.isNotEmpty()) {
                idleCodes.forEach { code ->
                    lruMap.remove(code)
                    kisWsPort.unsubscribeOrderbook(listOf(code))
                    log.info("orderbook idle timeout unsub: code={} idle>5min", code)
                }
            }
        }
    }
}
