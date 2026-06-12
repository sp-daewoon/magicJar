package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 호가 구독 라우터 — KIS WS H0STASP0 (ADR 0048 §2-1 + F11 STRATEGY owner 차원).
 *
 * ## owner = STRATEGY 단일화 (2026-06-12 방향 전환 — anchor `detail-orderbook-removal`)
 * 호가(H0STASP0) 구독은 **전략 ACTIVE 경로(STRATEGY owner) 전용**이다. 종목 상세(DETAIL) 진입 시
 * 호가창을 보여주고 구독하던 경로(`onSymbolDetailEntered/Exited` + LRU 10 slot + 200ms debounce +
 * 5분 idle timeout)는 최고 관리자 결재로 **제거**됐다(frontend도 호가창 UI 제거 병렬). 사유: 사용자가
 * 단순히 보기 위한 호가는 자본 흐름과 무관해 KIS WS 40 slot 예산을 잠식했고, 호가가 실제로 필요한 순간은
 * marketable limit 진입가 산정(전략 ACTIVE 매매)뿐이다.
 *
 * - **STRATEGY** — 전략 ACTIVE 시 평가/진입 대상 종목(`onStrategyActivated`). marketable limit 진입가 산정에
 *   ask1/bid1이 필요하므로 호가 WS가 공급돼야 한다. TTL 기반(refresh 갱신) — debounce/LRU evict 없음
 *   (registrar가 명시 release). 동일 종목 여러 전략(ownerId)이 요구하면 합집합으로 1개 구독 공유.
 *
 * 모든 STRATEGY demand가 빠질 때만 unsubscribe(owner 단위 추적 — 합집합으로 섞이지 않게).
 *
 * ## 배경 (F11 — 2026-06-12 라이브 incident)
 * 전략 ACTIVE 경로의 호가 구독이 전무해(과거엔 DETAIL만 존재) 라이브(14:48)에서 전략 86(후성 093370) BUY가
 * ask1/bid1/quoteAsOf 전부 null → 전량 `SIGNAL_SKIPPED_NO_QUOTE`. 호가 WS 미구독 → Redis
 * `orderbook:top:{code}` 영구 miss. STRATEGY owner 도입으로 전략 매매 경로에 호가를 공급한다(PR #935).
 *
 * ## WS 슬롯 회계 (ADR 0123)
 * subscribe/unsubscribe 발사 시 [WsSlotBudgetLedger]에 (H0STASP0, code) 채널을 register/release한다
 * (체결가·NXT·체결통보와 단일 회계 — 40 한도 총 점유 가시화). DETAIL LRU 10 slot 예산이 제거되며 그만큼
 * 전 채널 총 점유에서 호가 몫이 전략 ACTIVE 종목 수로 수렴 — 사용자 페이지 탐색이 슬롯 예산을 잠식하지 않는다.
 *
 * Thread safety: ReentrantLock.
 */
@Component
class OrderbookSubscriptionRouter(
    private val kisWsPort: KisWsPort,
    private val wsSlotBudgetLedger: WsSlotBudgetLedger,
) {
    private val log = LoggerFactory.getLogger(OrderbookSubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    /** H0STASP0 tr_id — WsSlotBudgetLedger 채널 회계 key (KIS-first: 호가 실시간). */
    private val trId = "H0STASP0"

    // STRATEGY demand: code → ownerId 집합 (전략 여러 개가 같은 종목을 요구할 수 있음 — 합집합).
    private val strategyDemands: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // 관리자 스냅샷용 — code별 최초 호가 구독 시각(epoch ms). 모든 owner가 빠지면 제거.
    private val subscribedSinceMs: MutableMap<String, Long> = mutableMapOf()

    /**
     * 종목이 STRATEGY owner로 호가 구독을 필요로 하는지.
     * 호출자가 lock 보유 전제(Locked 접미사).
     */
    private fun isDemandedLocked(code: String): Boolean = strategyDemands[code]?.isNotEmpty() == true

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
     * STRATEGY demand가 모두 빠졌을 때만 실제 WS unsubscribe 발사 — ledger release 동반.
     * 호출자가 lock 보유 전제.
     */
    private fun unsubscribeIfUnownedLocked(code: String) {
        if (isDemandedLocked(code)) return
        if (kisWsPort.subscribedOrderbookCodes().contains(code)) {
            kisWsPort.unsubscribeOrderbook(listOf(code))
            wsSlotBudgetLedger.release(trId, code)
        }
    }

    // ── STRATEGY owner (전략 ACTIVE 평가/진입 대상) ──────────────────────────

    /**
     * 전략 ACTIVE — 평가/진입 대상 종목 호가 sub (STRATEGY owner).
     *
     * marketable limit 진입가([EntryQuotePort])가 ask1/bid1을 요구하므로 호가 WS가 공급돼야 한다.
     * debounce/LRU evict 없음(전략 종목은 페이지 전환 무관 — registrar TTL refresh가 갱신,
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
            subscribedSinceMs.putIfAbsent(code, System.currentTimeMillis())
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
     * 전략 비활성/대상 종목 제외 — STRATEGY owner 회수.
     * 해당 전략 demand만 제거하고, 다른 전략 demand가 남으면 WS 유지.
     */
    fun onStrategyDeactivated(
        code: String,
        ownerId: String,
    ) {
        lock.withLock {
            val owners = strategyDemands[code] ?: return
            if (!owners.remove(ownerId)) return
            if (owners.isEmpty()) {
                strategyDemands.remove(code)
                subscribedSinceMs.remove(code)
            }
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
     * 현재 호가 구독 중인 종목코드 목록(STRATEGY owner).
     */
    fun current(): Set<String> =
        lock.withLock {
            strategyDemands.keys.toSet()
        }

    /**
     * 관리자 실시간 상태 스냅샷용 — 호가(H0STASP0) 구독 code별 owner 목록 + 최초 구독 시각(read-only).
     *
     * 구독 로직 변경 0 — strategyDemands read만. owner 문자열 = ownerId 그대로(예: `strategy:86`).
     */
    fun snapshotDemands(): List<OrderbookDemandSnapshot> =
        lock.withLock {
            strategyDemands.map { (code, owners) ->
                OrderbookDemandSnapshot(
                    code = code,
                    owners = owners.sorted(),
                    sinceMs = subscribedSinceMs[code],
                )
            }
        }
}

/**
 * 관리자 실시간 상태 스냅샷용 호가(H0STASP0) demand 투영 (read-only).
 */
data class OrderbookDemandSnapshot(
    val code: String,
    val owners: List<String>,
    val sinceMs: Long?,
)
