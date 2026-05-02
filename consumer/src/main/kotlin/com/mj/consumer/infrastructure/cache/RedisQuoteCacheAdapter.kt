package com.mj.consumer.infrastructure.cache

import com.mj.domain.market.cache.port.QuoteCache
import com.mj.domain.market.cache.port.QuoteSnapshot
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Redis 기반 QuoteCache 구현체. spec §5.4, ADR 0033 Tier-2.
 *
 * Redis key: `quote:last:{code}`
 * Redis type: Hash (price, volume, asOf, source)
 *
 * TTL: 7일 — 장기 미거래 종목도 마지막 시세 보존.
 * stale 판정: asOf 기준 현재보다 10초 이상 전이면 stale=true.
 *
 * profile: dev | prd (mock은 InMemoryQuoteCacheAdapter 사용)
 */
@Component
@Profile("dev | prd")
class RedisQuoteCacheAdapter(
    private val redis: StringRedisTemplate,
) : QuoteCache {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun putLatest(
        code: String,
        price: BigDecimal,
        volume: Long?,
        asOf: Instant,
        source: String,
    ) {
        val key = key(code)
        val fields =
            mutableMapOf(
                FIELD_PRICE to price.toPlainString(),
                FIELD_AS_OF to asOf.epochSecond.toString(),
                FIELD_SOURCE to source,
            )
        volume?.let { fields[FIELD_VOLUME] = it.toString() }

        redis.opsForHash<String, String>().putAll(key, fields)
        redis.expire(key, TTL)

        log.debug("[QUOTE_CACHE] putLatest code={} price={} asOf={} source={}", code, price, asOf, source)
    }

    override fun getLatest(code: String): QuoteSnapshot? {
        val key = key(code)
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
            log.warn("[QUOTE_CACHE] parse error for code={} raw={} error={}", code, raw, e.message)
            null
        }
    }

    override fun getStale(code: String): QuoteSnapshot? = getLatest(code)

    private fun key(code: String) = "$KEY_PREFIX$code"

    companion object {
        private const val KEY_PREFIX = "quote:last:"
        private const val FIELD_PRICE = "price"
        private const val FIELD_VOLUME = "volume"
        private const val FIELD_AS_OF = "asOf"
        private const val FIELD_SOURCE = "source"

        /** 7일 TTL — 장기 미거래 종목도 마지막 시세 보존. */
        val TTL: Duration = Duration.ofDays(7)

        /** 10초 이상 지연 시 stale 판정 (ADR 0033). */
        val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
    }
}
