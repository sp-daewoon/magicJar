package com.mj.api.application.market

import com.mj.domain.market.cache.port.QuoteCache
import com.mj.domain.market.cache.port.QuoteSnapshot
import com.mj.domain.market.cache.port.QuoteSource
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.port.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * api 모듈 시세 3-tier fallback 조회 서비스. ADR 0033, spec §5.
 *
 * Tier 1 — LIVE       : QuoteCache (stale < 10초)
 * Tier 2 — CACHED     : QuoteCache (stale ≥ 10초)
 * Tier 3 — LAST_DAY_CLOSE: CandleRepository.findLastClose
 *
 * @param quoteCache       Redis 시세 캐시 (읽기 전용, api 모듈)
 * @param candleRepository 캔들 저장소 포트 (Tier-3 fallback)
 * @param sessionResolver  장 상태 resolver
 */
@Service
class QuoteQueryService(
    private val quoteCache: QuoteCache,
    private val candleRepository: CandleRepository,
    private val sessionResolver: MarketSessionResolver,
) {
    private val log = LoggerFactory.getLogger(QuoteQueryService::class.java)

    /**
     * 종목 시세 3-tier fallback 조회.
     *
     * @param code KRX 6자리 종목코드
     * @return QuoteResult (데이터 없으면 null)
     */
    fun query(code: String): QuoteResult? {
        // Tier 1 & 2: QuoteCache
        val snapshot = quoteCache.getLatest(code)
        if (snapshot != null) {
            val source = if (snapshot.stale) QuoteSource.CACHED else QuoteSource.LIVE
            log.debug("[QUOTE_QUERY] Tier{} code={} price={}", if (snapshot.stale) 2 else 1, code, snapshot.price)
            return buildResult(code, snapshot, source)
        }

        // Tier 3: last-day-close
        val asOfDate = lastBusinessDate()
        val lastCandle = candleRepository.findLastClose(code, asOfDate)
        if (lastCandle != null) {
            log.debug("[QUOTE_QUERY] Tier3 code={} close={} date={}", code, lastCandle.close, asOfDate)
            return QuoteResult(
                code = code,
                price = lastCandle.close,
                volume = lastCandle.volume,
                source = QuoteSource.LAST_DAY_CLOSE,
                stale = true,
                asOf = lastCandle.openTime,
                session = currentSession(),
            )
        }

        log.debug("[QUOTE_QUERY] no data for code={}", code)
        return null
    }

    private fun buildResult(
        code: String,
        snapshot: QuoteSnapshot,
        source: QuoteSource,
    ): QuoteResult =
        QuoteResult(
            code = code,
            price = snapshot.price,
            volume = snapshot.volume,
            source = source,
            stale = snapshot.stale,
            asOf = snapshot.asOf,
            session = currentSession(),
        )

    private fun lastBusinessDate(): LocalDate =
        try {
            sessionResolver.resolve().lastBusinessDate
        } catch (e: Exception) {
            log.warn("[QUOTE_QUERY] sessionResolver error: {}", e.message)
            LocalDate.now(ZoneId.of("Asia/Seoul"))
        }

    private fun currentSession(): MarketSession? =
        try {
            sessionResolver.resolve()
        } catch (e: Exception) {
            log.warn("[QUOTE_QUERY] sessionResolver error for session: {}", e.message)
            null
        }
}

data class QuoteResult(
    val code: String,
    val price: java.math.BigDecimal,
    val volume: Long?,
    val source: QuoteSource,
    val stale: Boolean,
    val asOf: java.time.Instant,
    val session: MarketSession?,
)
