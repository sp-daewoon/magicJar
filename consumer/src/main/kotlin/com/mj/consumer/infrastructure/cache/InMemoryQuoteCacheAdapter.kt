package com.mj.consumer.infrastructure.cache

import com.mj.domain.market.cache.port.QuoteCache
import com.mj.domain.market.cache.port.QuoteSnapshot
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * mock profile용 in-memory QuoteCache. spec §5.4, ADR 0033.
 *
 * 테스트·단위테스트·mock profile에서 Redis 없이 사용.
 * thread-safe (ConcurrentHashMap).
 */
@Component
@Profile("mock")
class InMemoryQuoteCacheAdapter : QuoteCache {
    private data class Entry(
        val code: String,
        val price: BigDecimal,
        val volume: Long?,
        val asOf: Instant,
        val source: String,
    )

    private val store = ConcurrentHashMap<String, Entry>()

    override fun putLatest(
        code: String,
        price: BigDecimal,
        volume: Long?,
        asOf: Instant,
        source: String,
    ) {
        store[code] = Entry(code, price, volume, asOf, source)
    }

    override fun getLatest(code: String): QuoteSnapshot? {
        val e = store[code] ?: return null
        val stale = Duration.between(e.asOf, Instant.now()) > STALE_THRESHOLD
        return QuoteSnapshot(
            code = e.code,
            price = e.price,
            volume = e.volume,
            asOf = e.asOf,
            source = e.source,
            stale = stale,
        )
    }

    override fun getStale(code: String): QuoteSnapshot? = getLatest(code)

    /** 테스트용 초기화 */
    fun clear() = store.clear()

    companion object {
        val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
    }
}
