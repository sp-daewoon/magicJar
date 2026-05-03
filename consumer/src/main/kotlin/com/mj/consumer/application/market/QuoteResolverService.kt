package com.mj.consumer.application.market

import com.mj.domain.market.cache.port.QuoteCache
import com.mj.domain.market.cache.port.QuoteSource
import com.mj.domain.market.event.TickEvent
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.market.session.port.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 시세 3-tier fallback 서비스. spec §5, ADR 0033.
 *
 * Tier 1 — LIVE  : QuoteCache.getLatest (stale < 10초) → source=LIVE
 * Tier 2 — CACHED: QuoteCache.getLatest (stale ≥ 10초) → source=CACHED, stale=true
 * Tier 3 — LAST_DAY_CLOSE: CandleRepository.findLastClose → source=LAST_DAY_CLOSE, stale=true
 *
 * mock profile: getLatest가 MOCK source로 반환되므로 항상 LIVE 처리.
 *
 * @param quoteCache        시세 캐시 포트 (profile별 Redis/InMemory)
 * @param candleRepository  캔들 저장소 포트 (Tier-3 fallback)
 * @param sessionResolver   장 상태 resolver (mock profile: NoOpMarketSessionResolverImpl 사용)
 */
@Service
class QuoteResolverService(
    private val quoteCache: QuoteCache,
    private val candleRepository: CandleRepository,
    private val sessionResolver: MarketSessionResolver,
) {
    private val log = LoggerFactory.getLogger(QuoteResolverService::class.java)

    /**
     * 종목 코드로 최신 시세를 조회한다. 3-tier fallback 적용.
     *
     * @param code KRX 6자리 종목코드
     * @return ResolvedQuote — 항상 non-null (Tier-3도 없으면 null 반환)
     */
    fun resolve(code: String): ResolvedQuote? {
        // Tier 1 & 2: QuoteCache
        val snapshot = quoteCache.getLatest(code)
        if (snapshot != null) {
            val source = if (snapshot.stale) QuoteSource.CACHED else QuoteSource.LIVE
            log.debug(
                "[QUOTE_RESOLVER] Tier{} code={} price={} stale={}",
                if (snapshot.stale) 2 else 1,
                code,
                snapshot.price,
                snapshot.stale,
            )
            return ResolvedQuote(
                code = code,
                price = snapshot.price,
                volume = snapshot.volume,
                source = source,
                stale = snapshot.stale,
                asOf = snapshot.asOf,
            )
        }

        // Tier 3: last-day-close (DB candle)
        val asOfDate = lastBusinessDate()
        val lastCandle = candleRepository.findLastClose(code, asOfDate)
        if (lastCandle != null) {
            log.debug("[QUOTE_RESOLVER] Tier3 code={} close={} date={}", code, lastCandle.close, asOfDate)
            return ResolvedQuote(
                code = code,
                price = lastCandle.close,
                volume = lastCandle.volume,
                source = QuoteSource.LAST_DAY_CLOSE,
                stale = true,
                asOf = lastCandle.openTime,
            )
        }

        log.debug("[QUOTE_RESOLVER] no data for code={}", code)
        return null
    }

    /**
     * Tick 수신 시 QuoteCache에 저장한다. STOMP fanout 전 캐시 갱신용.
     */
    fun putLatest(tick: TickEvent) {
        quoteCache.putLatest(
            code = tick.code,
            price = tick.price,
            volume = tick.volume,
            asOf = tick.at,
            source = tick.source,
        )
    }

    /**
     * 현재 또는 가장 최근 영업일 날짜를 반환.
     * sessionResolver가 있으면 lastBusinessDate를 사용하고, 없으면 오늘 날짜로 fallback.
     */
    private fun lastBusinessDate(): LocalDate =
        try {
            sessionResolver.resolve().lastBusinessDate
        } catch (e: Exception) {
            log.warn("[QUOTE_RESOLVER] sessionResolver error, using today: {}", e.message)
            LocalDate.now(ZoneId.of("Asia/Seoul"))
        }
}

/**
 * 3-tier fallback 결과.
 *
 * @param code   종목코드
 * @param price  조회된 가격
 * @param volume 거래량 (nullable — 캐시 미수신 시)
 * @param source 출처 (LIVE|CACHED|LAST_DAY_CLOSE)
 * @param stale  true = fallback 데이터
 * @param asOf   실제 데이터 발생 시각
 */
data class ResolvedQuote(
    val code: String,
    val price: java.math.BigDecimal,
    val volume: Long?,
    val source: QuoteSource,
    val stale: Boolean,
    val asOf: Instant,
)
