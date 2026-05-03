package com.mj.api.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.domain.market.index.MarketIndex
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * MarketIndex Redis 캐시 어댑터 (T21).
 *
 * key: market:index:{code}
 * type: STRING (JSON 직렬화)
 * TTL: 1분 (장중 지수는 빈번하게 변경됨)
 */
@Component
class MarketIndexRedisAdapter(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun find(code: String): MarketIndex? {
        return try {
            val json = redis.opsForValue().get(key(code)) ?: return null
            objectMapper.readValue(json, MarketIndex::class.java)
        } catch (ex: Exception) {
            log.warn("[market-index-cache] 역직렬화 실패 code={} error={}", code, ex.message)
            null
        }
    }

    fun save(index: MarketIndex) {
        try {
            val json = objectMapper.writeValueAsString(index)
            redis.opsForValue().set(key(index.code), json, TTL)
        } catch (ex: Exception) {
            log.warn("[market-index-cache] 저장 실패 code={} error={}", index.code, ex.message)
        }
    }

    private fun key(code: String) = "$KEY_PREFIX$code"

    companion object {
        private const val KEY_PREFIX = "market:index:"
        val TTL: Duration = Duration.ofMinutes(1)
    }
}
