package com.mj.api.infrastructure.cache

import com.mj.domain.market.cache.port.QuoteCache
import com.mj.domain.market.cache.port.QuoteSnapshot
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * api 모듈 전용 Redis QuoteCache 읽기 어댑터. ADR 0033, spec §5.4.
 *
 * consumer 모듈의 RedisQuoteCacheAdapter와 동일한 Redis key 규약을 따른다.
 * api는 읽기 전용 — putLatest는 no-op (쓰기는 consumer가 담당).
 *
 * Redis key: `quote:last:{code}` (Hash: price, volume, asOf, source)
 */
@Component
class ApiRedisQuoteCacheAdapter(
    private val redis: StringRedisTemplate,
) : QuoteCache {
    private val log = LoggerFactory.getLogger(javaClass)

    /** api 모듈은 쓰기 금지 — no-op. consumer가 putLatest를 담당. */
    override fun putLatest(
        code: String,
        price: BigDecimal,
        volume: Long?,
        asOf: Instant,
        source: String,
    ) {
        // no-op: api 모듈은 캐시 쓰기 금지
    }

    override fun getLatest(code: String): QuoteSnapshot? {
        val key = "$KEY_PREFIX$code"
        val raw = redis.opsForHash<String, String>().entries(key)
        if (raw.isEmpty()) return null

        val priceStr = raw[FIELD_PRICE] ?: return null
        val asOfStr = raw[FIELD_AS_OF] ?: return null

        return try {
            val price = BigDecimal(priceStr)
            val asOf = Instant.ofEpochSecond(asOfStr.toLong())
            val volume = raw[FIELD_VOLUME]?.toLong()
            val source = raw[FIELD_SOURCE] ?: "cache"
            val stale = Duration.between(asOf, Instant.now()) > STALE_THRESHOLD

            QuoteSnapshot(
                code = code,
                price = price,
                volume = volume,
                asOf = asOf,
                source = source,
                stale = stale,
            )
        } catch (e: Exception) {
            log.warn("[API_QUOTE_CACHE] parse error code={} error={}", code, e.message)
            null
        }
    }

    companion object {
        private const val KEY_PREFIX = "quote:last:"
        private const val FIELD_PRICE = "price"
        private const val FIELD_VOLUME = "volume"
        private const val FIELD_AS_OF = "asOf"
        private const val FIELD_SOURCE = "source"

        val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
    }
}
