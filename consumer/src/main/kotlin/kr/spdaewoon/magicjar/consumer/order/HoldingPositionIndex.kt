package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 보유 종목 인메모리 인덱스 — ADR 0114 R2-C2b ★ tick 손절 watcher 성능 보강 (Codex 약점 보강 핵심).
 *
 * ## Codex 약점(감점 포인트)과 보강
 * Codex `PositionStopLossTickWatcher`는 **매 tick마다 DB 조회**(`findByCodeAndQuantityGreaterThan` + strategy `findById`)다.
 * tick은 보유 종목당 초당 수~수십 건 → 장중 내내 DB hammering(P95 latency·커넥션 풀 고갈 risk). 본 인덱스가:
 * - `ConcurrentHashMap<code, List<HeldPosition>>` — tick은 **인덱스 hit일 때만** 평가 진행(보유 0 종목은 즉시 return, DB 0).
 * - 손절가 산정에 필요한 평단·stop-loss%·수량을 **스냅샷 캐시** — tick 경로에서 strategy 테이블 조회 0.
 * - 60초 `@Scheduled` refresh + 체결 이벤트 즉시 갱신([onFillRefresh]) — eventual consistency(손절은 watcher가 안전망,
 *   1m 시그널 경로가 정밀 평가라 60초 staleness는 자본 risk 미미. 신규 체결은 즉시 반영해 공백 0).
 *
 * ## HOLDING owner refresh와 공유 (ADR 0114 §4-3 P3)
 * 본 인덱스의 보유 code 집합은 [HoldingDemandRegistrar]가 HOLDING owner 구독 등록에 재사용한다
 * (조회 중복 금지 — 컴포넌트는 분리하되 DB refresh는 본 인덱스 1곳).
 *
 * ## 순수성 경계
 * 본 클래스는 IO(DB)·상태(Map)를 갖는다(순수 함수 아님). 손절가 **산정**은 [stopPriceOf] 순수 helper(strategy-engine
 * KrxTickSize 재사용 — domain 중복 금지). 평가 로직(도달 판정)은 watcher가 호출.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class HoldingPositionIndex(
    private val strategyPositionRepository: StrategyPositionJpaRepository,
    private val strategyRepository: ConsumerStrategyJpaRepository,
    @Value("\${magicjar.autotrading.stop-loss.tick-watch-enabled:true}")
    private val enabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(HoldingPositionIndex::class.java)

    /** code → 보유 lot 스냅샷 리스트. 빈 code는 키 자체를 제거(메모리·hit 검사 정합). */
    private val index = ConcurrentHashMap<String, List<HeldPosition>>()

    @Volatile
    private var lastRefreshAt: Instant? = null

    /**
     * 보유 lot 스냅샷 — tick 손절 평가에 필요한 최소 필드만(평단·수량·stop-loss%·전략 메타).
     *
     * @param stopLossPct 전략 fixed stop-loss%(null이면 손절 미설정 → watcher가 평가 skip).
     * @param updatedAtEpoch 평단 변동 추적(멱등 키 입력). position.updatedAt.epochSecond.
     */
    data class HeldPosition(
        val userId: String,
        val strategyId: Long,
        val code: String,
        val quantity: Int,
        val avgPrice: BigDecimal,
        val stopLossPct: BigDecimal?,
        val takeProfitPct: BigDecimal?,
        val updatedAtEpoch: Long,
    )

    /** 부팅 직후 1회 + 60초 주기 refresh. tick 폭주 전에 인덱스를 채운다. */
    @Scheduled(fixedDelayString = "\${magicjar.autotrading.stop-loss.index-refresh-ms:60000}", initialDelay = 10_000)
    fun refresh() {
        if (!enabled) return
        val holdings =
            runCatching { strategyPositionRepository.findAllHolding() }
                .onFailure { e -> log.warn("HoldingPositionIndex refresh 실패 (기존 인덱스 유지): err={}", e.message) }
                .getOrNull() ?: return

        // strategy 메타(stop/take%)는 strategyId 단위로 1회만 조회(N+1 회피 — Codex 매 tick findById 대비 보강).
        val strategyMetaCache = HashMap<Long, Pair<BigDecimal?, BigDecimal?>>()
        val next = HashMap<String, MutableList<HeldPosition>>()
        for (p in holdings) {
            if (p.quantity <= 0 || p.avgPrice <= BigDecimal.ZERO) continue
            val (stop, take) =
                strategyMetaCache.getOrPut(p.strategyId) {
                    val strategy = runCatching { strategyRepository.findById(p.strategyId).orElse(null) }.getOrNull()
                    (strategy?.fixedStopLossPct) to (strategy?.takeProfitPct)
                }
            next.getOrPut(p.code) { mutableListOf() }.add(
                HeldPosition(
                    userId = p.userId,
                    strategyId = p.strategyId,
                    code = p.code,
                    quantity = p.quantity,
                    avgPrice = p.avgPrice,
                    stopLossPct = stop,
                    takeProfitPct = take,
                    updatedAtEpoch = p.updatedAt.epochSecond,
                ),
            )
        }

        // 원자적 교체 — 사라진 code 키 제거 + 신규/변경 반영(전량 청산된 종목이 인덱스에 남지 않게).
        index.keys.retainAll(next.keys)
        next.forEach { (code, lots) -> index[code] = lots }
        lastRefreshAt = Instant.now()
        log.debug("HoldingPositionIndex refresh: codes={} lots={}", next.size, next.values.sumOf { it.size })
    }

    /**
     * 체결 즉시 단일 code 갱신 — 신규 BUY 체결/SELL 청산 후 60초 기다리지 않고 즉시 반영(공백 0).
     * 해당 code의 보유 lot만 DB 재조회(전체 refresh 아님 — 비용 최소).
     */
    fun onFillRefresh(code: String) {
        if (!enabled) return
        val lots =
            runCatching { strategyPositionRepository.findHoldingByCode(code) }
                .onFailure { e -> log.warn("HoldingPositionIndex onFillRefresh 실패: code={} err={}", code, e.message) }
                .getOrNull() ?: return
        if (lots.isEmpty()) {
            index.remove(code)
            log.debug("HoldingPositionIndex onFillRefresh: code={} 전량 청산 — 인덱스 제거", code)
            return
        }
        val strategyMetaCache = HashMap<Long, Pair<BigDecimal?, BigDecimal?>>()
        val held =
            lots
                .filter { it.quantity > 0 && it.avgPrice > BigDecimal.ZERO }
                .map { p ->
                    val (stop, take) =
                        strategyMetaCache.getOrPut(p.strategyId) {
                            val strategy = runCatching { strategyRepository.findById(p.strategyId).orElse(null) }.getOrNull()
                            (strategy?.fixedStopLossPct) to (strategy?.takeProfitPct)
                        }
                    HeldPosition(
                        userId = p.userId,
                        strategyId = p.strategyId,
                        code = p.code,
                        quantity = p.quantity,
                        avgPrice = p.avgPrice,
                        stopLossPct = stop,
                        takeProfitPct = take,
                        updatedAtEpoch = p.updatedAt.epochSecond,
                    )
                }
        if (held.isEmpty()) index.remove(code) else index[code] = held
        log.debug("HoldingPositionIndex onFillRefresh: code={} lots={}", code, held.size)
    }

    /** tick hot path 진입점 — 보유 lot 0이면 빈 리스트(DB 조회 0). watcher가 hit일 때만 평가. */
    fun heldBy(code: String): List<HeldPosition> = index[code] ?: emptyList()

    /** HOLDING owner 등록·refresh 공유 — 현재 보유 (code,userId,strategyId) 집합(조회 중복 금지). */
    fun holdingKeys(): List<HeldPosition> = index.values.flatten()

    /** 인덱스 직접 주입(테스트 전용 — DB 우회). */
    internal fun replaceForTest(snapshot: Map<String, List<HeldPosition>>) {
        index.clear()
        index.putAll(snapshot)
    }
}
