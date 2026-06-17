package kr.spdaewoon.magicjar.api.service.market

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Redis `market:quote:last:code:{code}` 현재가 **읽기 전용** 리더 — api 모듈 (ADR 0014 T2 cache).
 *
 * consumer 의 `KisWebSocketBridge`(H0UNCNT0 체결 tick) 와 `WatchlistPollingWorker`(REST polling)가
 * write-through 한 `quote:last` 캐시에서 **price 1필드만** 동기 read 한다. **새 KIS 호출을 절대 하지 않는다**
 * (cache miss → null). 보유 평가손익(미실현) 같은 화면 보조 표시에 쓰인다.
 *
 * freshness: `quote:last` 자체가 TTL 5분(`RedisQuoteCacheAdapter.QUOTE_TTL`)이라 캐시에 존재하면 5분 이내
 * 신선분이다. 별도 read-side gate 없이 "존재 = 신선"으로 취급한다(키 부재 = stale = null).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class LivePriceCacheReader(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(LivePriceCacheReader::class.java)

    /**
     * 단건 현재가 read. cache miss·역직렬화 실패 → null (새 KIS 호출 없음).
     */
    fun latestPrice(code: String): BigDecimal? {
        val json = redisTemplate.opsForValue().get(RedisKeyRegistry.Market.quoteLast(code)) ?: return null
        return parsePrice(code, json)
    }

    /**
     * 다종목 일괄 read — 종목당 1 GET. miss 종목은 map 에서 제외(value null 미포함).
     * caller 가 `result[code]` 로 조회해 부재 시 null 처리하면 된다.
     */
    fun latestPrices(codes: Collection<String>): Map<String, BigDecimal> {
        val distinct = codes.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return distinct
            .mapNotNull { code ->
                redisTemplate
                    .opsForValue()
                    .get(RedisKeyRegistry.Market.quoteLast(code))
                    ?.let { parsePrice(code, it) }
                    ?.let { code to it }
            }.toMap()
    }

    private fun parsePrice(
        code: String,
        json: String,
    ): BigDecimal? =
        try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
            (map["price"] as? String)?.takeIf { it.isNotBlank() }?.let { BigDecimal(it) }
        } catch (e: Exception) {
            log.warn("quote:last 현재가 파싱 실패: code={} err={}", code, e.message)
            null
        }
}
