package com.mj.api.application.market

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.infrastructure.kis.KisOrderbookAdapter
import com.mj.domain.market.Orderbook
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 호가창 application service.
 *
 * Redis 캐시 TTL: 5초 (실시간성 중요).
 * KIS 키 미설정 또는 API 실패 시 null 반환 (graceful degradation).
 */
@Service
class OrderbookService(
    private val kisAdapter: KisOrderbookAdapter,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 호가창 조회.
     * Redis 캐시 hit 시 KIS API 호출 없음. miss 시 KIS → Redis 저장.
     */
    fun getOrderbook(code: String): Orderbook? {
        val key = cacheKey(code)

        // 캐시 조회
        val cached =
            runCatching {
                redis.opsForValue().get(key)?.let {
                    objectMapper.readValue(it, Orderbook::class.java)
                }
            }.getOrElse {
                log.debug("[orderbook-service] 캐시 역직렬화 실패 code={} error={}", code, it.message)
                null
            }
        if (cached != null) return cached

        // KIS API 호출
        val orderbook = kisAdapter.fetchOrderbook(code) ?: return null

        // 캐시 저장 (5초 TTL)
        runCatching {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(orderbook), CACHE_TTL)
        }.onFailure {
            log.debug("[orderbook-service] 캐시 저장 실패 code={} error={}", code, it.message)
        }

        return orderbook
    }

    private fun cacheKey(code: String) = "market:orderbook:$code"

    companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(5)
    }
}
