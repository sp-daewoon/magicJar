package com.mj.api.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.domain.market.instrument.InstrumentDetail
import com.mj.domain.market.instrument.port.InstrumentDetailRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * InstrumentDetail Redis 캐시 어댑터 (T20-T4).
 *
 * key: instrument:detail:{code}
 * type: STRING (JSON 직렬화)
 * TTL: 6시간
 */
@Component
class InstrumentDetailRedisAdapter(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : InstrumentDetailRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun find(code: String): InstrumentDetail? {
        val key = key(code)
        return try {
            val json = redis.opsForValue().get(key) ?: return null
            objectMapper.readValue(json, InstrumentDetail::class.java)
        } catch (ex: Exception) {
            log.warn("[instrument-detail-cache] 역직렬화 실패 code={} error={}", code, ex.message)
            null
        }
    }

    override fun save(detail: InstrumentDetail) {
        val key = key(detail.code)
        try {
            val json = objectMapper.writeValueAsString(detail)
            redis.opsForValue().set(key, json, TTL)
        } catch (ex: Exception) {
            log.warn("[instrument-detail-cache] 저장 실패 code={} error={}", detail.code, ex.message)
        }
    }

    override fun evict(code: String) {
        redis.delete(key(code))
    }

    private fun key(code: String) = "$KEY_PREFIX$code"

    companion object {
        private const val KEY_PREFIX = "instrument:detail:"
        val TTL: Duration = Duration.ofHours(6)
    }
}
