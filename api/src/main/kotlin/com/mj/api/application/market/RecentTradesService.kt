package com.mj.api.application.market

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.infrastructure.kis.KisRecentTradesAdapter
import com.mj.domain.market.RecentTrade
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 실시간 체결 list application service.
 *
 * Redis 캐시 TTL: 10초.
 * KIS 키 미설정 시 빈 리스트 반환.
 */
@Service
class RecentTradesService(
    private val kisAdapter: KisRecentTradesAdapter,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val typeRef = object : TypeReference<List<RecentTrade>>() {}

    /**
     * 최근 체결 리스트 조회.
     * Redis 캐시 hit 시 KIS API 호출 없음.
     * @param limit 반환 건수 (1~30, 기본 30)
     */
    fun getRecentTrades(
        code: String,
        limit: Int = 30,
    ): List<RecentTrade> {
        val actualLimit = limit.coerceIn(1, 30)
        val key = cacheKey(code)

        // 캐시 조회
        val cached =
            runCatching {
                redis.opsForValue().get(key)?.let {
                    objectMapper.readValue(it, typeRef)
                }
            }.getOrElse {
                log.debug("[trades-service] 캐시 역직렬화 실패 code={} error={}", code, it.message)
                null
            }
        if (cached != null) return cached.take(actualLimit)

        // KIS API 호출
        val trades = kisAdapter.fetchRecentTrades(code, actualLimit)

        // 캐시 저장 (10초 TTL)
        if (trades.isNotEmpty()) {
            runCatching {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(trades), CACHE_TTL)
            }.onFailure {
                log.debug("[trades-service] 캐시 저장 실패 code={} error={}", code, it.message)
            }
        }

        return trades
    }

    private fun cacheKey(code: String) = "market:trades:$code"

    companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(10)
    }
}
