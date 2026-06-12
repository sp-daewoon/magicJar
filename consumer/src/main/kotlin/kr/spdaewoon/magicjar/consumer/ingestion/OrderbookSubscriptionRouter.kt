package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 호가 구독 라우터 — KIS WS H0STASP0 (ADR 0048 §2-1 + F11 STRATEGY owner 차원).
 *
 * ## owner 분리 (F11 — 2026-06-12 라이브 incident 수선)
 * 호가 수요는 두 owner로 나뉜다(현재가 [RealtimeSubscriptionRouter] DETAIL/STRATEGY 분리와 대칭):
 * - **DETAIL** — 사용자가 종목 상세 화면 진입(`onSymbolDetailEntered`). LRU 10 slot 예산 + 200ms debounce +
 *   5분 idle timeout(탭 닫기 cleanup 보완). 사용자가 "지금 보는" 종목.
 * - **STRATEGY** — 전략 ACTIVE 시 평가/진입 대상 종목(`onStrategyActivated`). marketable limit 진입가 산정에
 *   ask1/bid1이 필요하므로 호가 WS가 공급돼야 한다. TTL 기반(refresh 갱신) — debounce/LRU evict 없음
 *   (전략 종목은 사용자 페이지 전환과 무관, registrar가 명시 release).
 *
 * 한 종목에 DETAIL·STRATEGY demand가 공존하면 **단 1개 H0STASP0 구독**으로 둘 다 충족하고,
 * 모든 owner demand가 빠질 때만 unsubscribe(단일 합집합으로 섞이지 않게 owner 단위 추적).
 *
 * ## 배경 결함 (F11)
 * 기존 라우터는 `onSymbolDetailEntered/Exited`(DETAIL)만 있어 전략 ACTIVE 경로의 호가 구독이 **전무**했다.
 * 라이브(2026-06-12 14:48): 전략 86(후성 093370) BUY가 ask1/bid1/quoteAsOf 전부 null →
 * 전량 `SIGNAL_SKIPPED_NO_QUOTE`(매수 전면 불능). 호가 WS 미구독 → Redis `orderbook:top:{code}` 영구 miss.
 *
 * ## WS 슬롯 회계 (ADR 0123)
 * subscribe/unsubscribe 발사 시 [WsSlotBudgetLedger]에 (H0STASP0, code) 채널을 register/release한다
 * (체결가·NXT·체결통보와 단일 회계 — 40 한도 총 점유 가시화). DETAIL LRU 10 slot은 본 라우터 내부 예산이고,
 * ledger는 전 채널 총량 회계라 책임이 다르다.
 *
 * Thread safety: ReentrantLock.
 */
@Component
class OrderbookSubscriptionRouter(
    private val kisWsPort: KisWsPort,
    private val wsSlotBudgetLedger: WsSlotBudgetLedger,
    @Value("\${magicjar.kis.ws.orderbook.detail-max-slots:10}")
    private val detailMaxSlots: Int = 10,
) {
    private val log = LoggerFactory.getLogger(OrderbookSubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    /** H0STASP0 tr_id — WsSlotBudgetLedger 채널 회계 key (KIS-first: 호가 실시간). */
    private val trId = "H0STASP0"

    // DETAIL LRU Map: code → lastAccessMs (insertion-order LinkedHashMap, access-order=true)
    private val lruMap: LinkedHashMap<String, Long> =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = false
        }

    // STRATEGY demand: code → ownerId 집합 (전략 여러 개가 같은 종목을 요구할 수 있음 — 합집합).
    private val strategyDemands: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // 200ms debounce(DETAIL 전용): code → pendingUntilMs
    private val pendingDebounce: MutableMap<String, Long> = mutableMapOf()

    // 5분 idle timeout 기준 (DETAIL 전용 — STRATEGY는 registrar TTL release가 관리)
    private val idleTimeoutMs = 5L * 60L * 1000L

    /**
     * 종목이 어느 owner로든 호가 구독을 필요로 하는지.
     * 호출자가 lock 보유 전제(Locked 접미사).
     */
    private fun isDemandedLocked(code: String): Boolean = lruMap.containsKey(code) || strategyDemands[code]?.isNotEmpty() == true

    /**
     * 실제 WS subscribe 발사 — 아직 미구독이면 1회. ledger register 동반.
     * 호출자가 lock 보유 전제.
     */
    private fun ensureSubscribedLocked(code: String) {
        if (kisWsPort.subscribedOrderbookCodes().contains(code)) return
        kisWsPort.subscribeOrderbook(listOf(code))
        wsSlotBudgetLedger.register(trId, code)
    }

    /**
     * owner demand가 모두 빠졌을 때만 실제 WS unsubscribe 발사 — ledger release 동반.
     * 호출자가 lock 보유 전제.
     */
    private fun unsubscribeIfUnownedLocked(code: String) {
        if (isDemandedLocked(code)) return
        pendingDebounce.remove(code)
        if (kisWsPort.subscribedOrderbookCodes().contains(code)) {
            kisWsPort.unsubscribeOrderbook(listOf(code))
            wsSlotBudgetLedger.release(trId, code)
        }
    }

    // ── DETAIL owner (사용자 종목 상세) ──────────────────────────────────────

    /**
     * 사용자가 SymbolDetail 페이지에 진입 — DETAIL owner 호가 sub.
     * 200ms debounce(빠른 전환 burst 차단) + LRU 10 slot 예산.
     */
    fun onSymbolDetailEntered(code: String) {
        lock.withLock {
            val now = System.currentTimeMillis()

            // backlog §29 #3 — bounded growth cleanup: stale debounce entry 제거.
            pendingDebounce.entries.removeIf { (_, until) -> now > until }

            // 200ms debounce — 동일 종목 200ms 내 재진입이면 무시.
            val pendingUntil = pendingDebounce[code]
            if (pendingUntil != null && now < pendingUntil) {
                log.debug("orderbook DETAIL sub debounce skip: code={}", code)
                return
            }
            pendingDebounce[code] = now + 200L

            // 이미 DETAIL slot 보유 → accessTime 갱신만.
            if (lruMap.containsKey(code)) {
                lruMap[code] = now
                log.debug("orderbook DETAIL 재진입 (이미 slot 보유): code={}", code)
                return
            }

            // DETAIL slot 가득 → LRU evict (단, evict 대상이 STRATEGY demand도 가지면 WS 유지).
            if (lruMap.size >= detailMaxSlots) {
                val eldest = lruMap.entries.minByOrNull { it.value }
                if (eldest != null) {
                    val evicted = eldest.key
                    lruMap.remove(evicted)
                    // STRATEGY demand가 남아 있으면 WS는 유지, DETAIL slot만 회수.
                    unsubscribeIfUnownedLocked(evicted)
                    log.info(
                        "orderbook DETAIL LRU evict: evicted={} new={} detailSlot={}/{} strategyHeld={}",
                        evicted,
                        code,
                        lruMap.size,
                        detailMaxSlots,
                        strategyDemands[evicted]?.isNotEmpty() == true,
                    )
                }
            }

            lruMap[code] = now
            ensureSubscribedLocked(code)
            log.info("orderbook DETAIL subscribe: code={} detailSlot={}/{}", code, lruMap.size, detailMaxSlots)
        }
    }

    /**
     * 사용자가 SymbolDetail 페이지에서 이탈 — DETAIL owner 회수.
     * STRATEGY demand가 남아 있으면 WS는 유지된다.
     */
    fun onSymbolDetailExited(code: String) {
        lock.withLock {
            if (lruMap.remove(code) != null) {
                log.info("orderbook DETAIL release: code={} detailSlot={}/{}", code, lruMap.size, detailMaxSlots)
                unsubscribeIfUnownedLocked(code)
            }
        }
    }

    // ── STRATEGY owner (전략 ACTIVE 평가/진입 대상) ──────────────────────────

    /**
     * 전략 ACTIVE — 평가/진입 대상 종목 호가 sub (STRATEGY owner).
     *
     * marketable limit 진입가([EntryQuotePort])가 ask1/bid1을 요구하므로 호가 WS가 공급돼야 한다.
     * DETAIL과 달리 debounce/LRU evict 없음(전략 종목은 페이지 전환 무관 — registrar TTL refresh가 갱신,
     * 명시 release로만 해제). 동일 종목 여러 전략(ownerId)이 요구하면 합집합으로 1개 구독 공유.
     *
     * @param code 종목코드(6자리).
     * @param ownerId 전략 식별자(`strategy:<id>`) — 멱등 register + 다중 전략 합집합.
     */
    fun onStrategyActivated(
        code: String,
        ownerId: String,
    ) {
        lock.withLock {
            val owners = strategyDemands.getOrPut(code) { mutableSetOf() }
            val added = owners.add(ownerId)
            ensureSubscribedLocked(code)
            if (added) {
                log.info(
                    "orderbook STRATEGY subscribe: code={} ownerId={} strategyOwners={}",
                    code,
                    ownerId,
                    owners.size,
                )
            } else {
                log.debug("orderbook STRATEGY 재요청(멱등): code={} ownerId={}", code, ownerId)
            }
        }
    }

    /**
     * 전략 비활성/대상 종목 제외 — STRATEGY owner 회수 (STRATEGY owner).
     * 해당 전략 demand만 제거하고, 다른 전략·DETAIL demand가 남으면 WS 유지.
     */
    fun onStrategyDeactivated(
        code: String,
        ownerId: String,
    ) {
        lock.withLock {
            val owners = strategyDemands[code] ?: return
            if (!owners.remove(ownerId)) return
            if (owners.isEmpty()) strategyDemands.remove(code)
            log.info(
                "orderbook STRATEGY release: code={} ownerId={} remainingStrategyOwners={}",
                code,
                ownerId,
                owners.size,
            )
            unsubscribeIfUnownedLocked(code)
        }
    }

    /**
     * 현재 호가 구독 중인 종목코드 목록(DETAIL ∪ STRATEGY).
     */
    fun current(): Set<String> =
        lock.withLock {
            (lruMap.keys + strategyDemands.keys).toSet()
        }

    /**
     * 5분 idle timeout 체크 — @Scheduled 1분마다 (DETAIL slot 전용).
     * STRATEGY demand는 registrar의 TTL release가 관리하므로 idle 대상에서 제외한다
     * (전략 종목을 idle로 끊으면 진입가 호가 공백 = 자본 risk).
     */
    @Scheduled(fixedRate = 60_000)
    fun evictIdleSubscriptions() {
        val now = System.currentTimeMillis()
        lock.withLock {
            val idleCodes =
                lruMap.entries
                    .filter { (_, lastAccessMs) -> (now - lastAccessMs) > idleTimeoutMs }
                    .map { it.key }

            idleCodes.forEach { code ->
                lruMap.remove(code)
                log.info("orderbook DETAIL idle timeout: code={} idle>5min", code)
                // STRATEGY demand가 남아 있으면 WS 유지(전략 호가 공백 차단).
                unsubscribeIfUnownedLocked(code)
            }
        }
    }
}
