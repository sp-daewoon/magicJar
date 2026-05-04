package com.mj.consumer.application.market

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
 * KIS 실시간 시세 구독 LRU 매니저. plan T2-R2-A3 §T21, §2-C 정책.
 *
 * 정책 요약:
 *   - 한도 18종목 (KIS account 동시 등록 41건 중 시세 5종 × 종목, 호가·지수 등 안배 후 여유).
 *   - default5(005930·000660·035720·005380·051910)는 **eviction 면제**.
 *   - 사용자 진입 시 동적 추가 → 한도 초과면 default5 제외 LRU 가장 오래된 종목을 unsubscribe.
 *   - touch(code) — 호출 시 access order 갱신 (사용자 재진입 시 LRU 갱신용).
 *   - eviction 시 [SubscriptionEvictedEvent] in-process publish — STOMP fanout listener 후속에서 추가.
 *
 * 동시성:
 *   - 모든 mutating 메서드는 `synchronized` 블록 내에서 실행 — 진입 빈도 낮음 (TPS 1 미만).
 *   - LinkedHashMap access-order 사용해 LRU 순서 자동 관리.
 *
 * Profile dev|prd 한정 — mock 환경은 MockTickGenerator가 default5만 발행, 본 매니저 미적용.
 */
@Component
@Profile("dev | prd")
class SubscriptionManager(
    private val router: KisWsRouter,
    private val quoteListener: KisQuoteWsAdapter,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${magicjar.market.default-codes:005930,000660,035720,005380,051910}")
    private val defaultCodesRaw: String,
    @Value("\${magicjar.market.max-subscriptions:18}")
    private val maxSubscriptions: Int = DEFAULT_MAX,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** eviction 면제 종목 — Dashboard 카드 가시화 보장. */
    val defaultCodes: Set<String> by lazy {
        defaultCodesRaw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

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

        // 신규 — 한도 초과 검사 후 eviction
        if (accessOrder.size >= maxSubscriptions) {
            val evicted = chooseEviction()
            if (evicted == null) {
                log.warn(
                    "[SUBSCRIPTION_MANAGER] cannot evict (all in default5) — refuse add code={}",
                    code,
                )
                return
            }
            evict(evicted)
        }

        accessOrder[code] = System.currentTimeMillis()
        try {
            router.subscribe(KisQuoteWsAdapter.TR_ID, code, quoteListener)
            log.info(
                "[SUBSCRIPTION_MANAGER] add code={} bootstrap={} size={}/{}",
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

    /**
     * default5 외 LRU 가장 오래된 종목 1개 선택. 모두 default5면 null.
     */
    private fun chooseEviction(): String? {
        // accessOrder iteration은 LRU 순서 (가장 오래된 것이 먼저)
        for (entry in accessOrder) {
            if (entry.key !in defaultCodes) return entry.key
        }
        return null
    }

    private fun evict(code: String) {
        accessOrder.remove(code)
        try {
            router.unsubscribe(KisQuoteWsAdapter.TR_ID, code)
        } catch (e: Exception) {
            log.warn("[SUBSCRIPTION_MANAGER] unsubscribe failed code={}: {}", code, e.message)
        }
        eventPublisher.publishEvent(SubscriptionEvictedEvent(evictedCode = code))
        log.info("[SUBSCRIPTION_MANAGER] evict code={} (LRU over-limit)", code)
    }

    companion object {
        const val DEFAULT_MAX: Int = 18
    }
}
