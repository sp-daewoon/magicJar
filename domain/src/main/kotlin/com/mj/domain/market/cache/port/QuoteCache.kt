package com.mj.domain.market.cache.port

import java.math.BigDecimal
import java.time.Instant

/**
 * 시세 마지막 tick 캐시 포트. spec §5.4, ADR 0033 T2 Tier.
 *
 * 3-tier fallback:
 *   T1 live: KIS WebSocket tick (mock: MockTickGenerator)
 *   T2 cache: QuoteCache (Redis `quote:last:{code}`) ← 이 포트
 *   T3 last-day-close: Redis `quote:lastbusinessday:{code}:{yyyyMMdd}` (선택)
 *
 * 구현체:
 *   - consumer dev/prd: RedisQuoteCacheAdapter (Redis hash, TTL 7일)
 *   - consumer mock: InMemoryQuoteCacheAdapter
 */
interface QuoteCache {
    /**
     * 마지막 tick 저장.
     *
     * @param code  종목코드 (예: "005930")
     * @param price 최신 가격
     * @param volume 누적 거래량 (nullable — WS tick에 없을 수 있음)
     * @param asOf  tick 발생 시각
     * @param source tick 출처 ("live" | "mock" | "kis-ws" 등)
     */
    fun putLatest(
        code: String,
        price: BigDecimal,
        volume: Long?,
        asOf: Instant,
        source: String = "live",
    )

    /**
     * 마지막 tick 조회. 없으면 null.
     *
     * @return QuoteSnapshot or null
     */
    fun getLatest(code: String): QuoteSnapshot?

    /**
     * TTL이 아직 살아있지만 last-tick 발생 후 10초 이상 경과한 stale 데이터 조회.
     * (session=Closed 시 fallback에서 사용. TTL 범위 내 항상 반환)
     *
     * @return QuoteSnapshot (stale=true) or null
     */
    fun getStale(code: String): QuoteSnapshot? = getLatest(code)
}

/**
 * 캐시된 시세 스냅샷. spec §5.4.
 *
 * @param code   종목코드
 * @param price  마지막 가격
 * @param volume 마지막 거래량 (nullable)
 * @param asOf   tick 발생 시각
 * @param source tick 출처
 * @param stale  10초 이상 지연된 데이터 여부 (캐시에서 조회 시 계산)
 */
data class QuoteSnapshot(
    val code: String,
    val price: BigDecimal,
    val volume: Long?,
    val asOf: Instant,
    val source: String,
    val stale: Boolean,
)
