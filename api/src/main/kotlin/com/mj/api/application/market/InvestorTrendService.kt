package com.mj.api.application.market

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.infrastructure.kis.KisInvestorTrendAdapter
import com.mj.domain.market.InvestorTrend
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 외국인·기관 동향 application service.
 *
 * Redis 캐시 TTL: 5분 (일별 데이터 — 장중에도 자주 변하지 않음).
 * KIS 키 미설정 시 빈 리스트 반환.
 */
@Service
class InvestorTrendService(
    private val kisAdapter: KisInvestorTrendAdapter,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val typeRef = object : TypeReference<List<InvestorTrend>>() {}

    /**
     * 외국인·기관 동향 조회.
     * Redis 캐시 hit 시 KIS API 호출 없음.
     * @param days 조회 영업일 수 (1~30, 기본 20)
     */
    fun getInvestorTrend(
        code: String,
        days: Int = 20,
    ): List<InvestorTrend> {
        val actualDays = days.coerceIn(1, 30)
        val key = cacheKey(code)

        // 캐시 조회
        val cached =
            runCatching {
                redis.opsForValue().get(key)?.let {
                    objectMapper.readValue(it, typeRef)
                }
            }.getOrElse {
                log.debug("[investor-service] 캐시 역직렬화 실패 code={} error={}", code, it.message)
                null
            }
        if (cached != null) return cached.take(actualDays)

        // KIS API 호출
        val trends = kisAdapter.fetchInvestorTrend(code, actualDays)

        // 캐시 저장 (5분 TTL)
        if (trends.isNotEmpty()) {
            runCatching {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(trends), CACHE_TTL)
            }.onFailure {
                log.debug("[investor-service] 캐시 저장 실패 code={} error={}", code, it.message)
            }
        }

        return trends
    }

    private fun cacheKey(code: String) = "market:investor:$code"

    companion object {
        val CACHE_TTL: Duration = Duration.ofMinutes(5)
    }
}
