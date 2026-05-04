package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisOrderbookWsAdapter
import com.mj.consumer.infrastructure.kis.KisQuoteWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import com.mj.domain.market.event.SubscriptionEvictedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.LinkedHashMap

/**
 * KIS 실시간 시세 구독 LRU 매니저. ADR 0049 — recent-only bootstrap.
 *
 * 정책 요약:
 *   - 한도 18종목 (KIS account 동시 등록 41건 정합).
 *   - **모든 종목 동일 priority** — eviction 면제 없음 (ADR 0049: default5 하드코드 폐기).
 *   - 사용자 진입 시 동적 추가 → 한도 초과면 LRU 가장 오래된 종목을 unsubscribe.
 *   - eviction 시 [SubscriptionEvictedEvent] in-process publish — STOMP fanout listener 후속에서 추가.
 *
 * 동시성:
 *   - 모든 mutating 메서드는 `synchronized` 블록 내에서 실행 — 진입 빈도 낮음 (TPS 1 미만).
 *   - LinkedHashMap access-order 사용해 LRU 순서 자동 관리.
 *
 * Profile dev|prd 한정 — mock profile은 jvm test fixture 전용 (운영 영향 0).
 */
@Component
@Profile("dev | prd")
class SubscriptionManager(
    private val router: KisWsRouter,
    private val quoteListener: KisQuoteWsAdapter,
    private val orderbookListener: KisOrderbookWsAdapter,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${magicjar.market.max-subscriptions:18}")
    private val maxSubscriptions: Int = DEFAULT_MAX,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * access-order LinkedHashMap — 마지막 사용 시각 기준 LRU.
     *
     * 생성자 인자: initialCapacity=32, loadFactor=0.75f, accessOrder=true.
     * accessOrder=true 일 때 get/put 호출이 entry를 끝(MRU)으로 이동 → 첫 entry가 LRU.
     * key: 종목코드, value: 최근 access epoch millis (참고용 로깅).
     */
    private val accessOrder: LinkedHashMap<String, Long> =
        object : LinkedHashMap<String, Long>(32, 0.75f, true) {}

    private val lock = Any()

    /**
     * 부팅 시 초기 구독 합집합을 등록한다 — 멱등 (이미 활성이면 skip).
     * KisQuoteSubscriber.onReady에서 호출.
     */
    fun bootstrap(codes: Collection<String>) {
        synchronized(lock) {
            codes.forEach { code -> internalAdd(code, isBootstrap = true) }
        }
    }

    /**
     * 사용자 진입 시 단일 종목 추가 — 멱등 + LRU touch + 한도 초과 시 eviction.
     * MarketSubscriptionCommandListener에서 호출.
     */
    fun addOnDemand(code: String) {
        if (code.isBlank()) return
        synchronized(lock) {
            internalAdd(code, isBootstrap = false)
        }
    }

    /**
     * 현재 활성 구독 종목 — TickFallbackEmitter가 fallback 발행 대상으로 사용.
     */
    fun activeCodes(): Set<String> =
        synchronized(lock) {
            accessOrder.keys.toSet()
        }

    fun activeSize(): Int = synchronized(lock) { accessOrder.size }

    private fun internalAdd(
        code: String,
        isBootstrap: Boolean,
    ) {
        // 이미 등록된 경우 — accessOrder=true라 get만 호출해도 LRU 갱신
        if (accessOrder.containsKey(code)) {
            accessOrder[code] = System.currentTimeMillis()
            return
        }

        // 신규 — 한도 초과 시 LRU 가장 오래된 종목 evict (ADR 0049 — 모든 종목 동일 priority)
        if (accessOrder.size >= maxSubscriptions) {
            val evicted = accessOrder.entries.first().key
            evict(evicted)
        }

        accessOrder[code] = System.currentTimeMillis()
        try {
            // 종목당 trId 2개 — 체결가(H0UNCNT0) + 호가(H0UNASP0). KisWsRouter가 (trId, code) 단위 멱등.
            router.subscribe(KisQuoteWsAdapter.TR_ID, code, quoteListener)
            router.subscribe(KisOrderbookWsAdapter.TR_ID, code, orderbookListener)
            log.info(
                "[SUBSCRIPTION_MANAGER] add code={} bootstrap={} size={}/{} (quote+orderbook)",
                code,
                isBootstrap,
                accessOrder.size,
                maxSubscriptions,
            )
        } catch (e: Exception) {
            // KIS WS 호출 실패 — 추적 set에서 제거 (멱등 재시도 가능)
            accessOrder.remove(code)
            log.warn("[SUBSCRIPTION_MANAGER] subscribe failed code={}: {}", code, e.message)
        }
    }

    private fun evict(code: String) {
        accessOrder.remove(code)
        try {
            router.unsubscribe(KisQuoteWsAdapter.TR_ID, code)
            router.unsubscribe(KisOrderbookWsAdapter.TR_ID, code)
        } catch (e: Exception) {
            log.warn("[SUBSCRIPTION_MANAGER] unsubscribe failed code={}: {}", code, e.message)
        }
        eventPublisher.publishEvent(SubscriptionEvictedEvent(evictedCode = code))
        log.info("[SUBSCRIPTION_MANAGER] evict code={} (LRU over-limit, quote+orderbook)", code)
    }

    companion object {
        const val DEFAULT_MAX: Int = 18
    }
}
