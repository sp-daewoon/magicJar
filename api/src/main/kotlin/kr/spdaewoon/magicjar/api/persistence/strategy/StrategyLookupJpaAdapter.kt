package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 실시간 시그널 평가용 전략 조회 어댑터 (ADR 0066 §5-3 + §2-5 D-1).
 *
 * ### Redis cache 1차 조회 → miss 시 DB 조회 + cache put
 * key: `strategy:active:by_code:{code}:{interval}` (TTL 5분)
 * value: JSON 배열 [strategyId, strategyId, ...]
 *
 * ### 헌법 §6 (ADR 0051) 준수
 * 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * ### P2-4 진보 페르소나 refactor (ADR 0087, 2026-05-19)
 * - `toDomain`·`safeComplexityClass`·`safeDisabledReason` → [StrategyEntityMapper] 추출
 * - StrategyJpaAdapter와 약 42줄 중복 코드 해소 — schema drift 회귀 risk 영구 차단
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class StrategyLookupJpaAdapter(
    private val strategyJpaRepository: StrategyJpaRepository,
    private val redisStrategyCache: RedisStrategyCache,
    private val objectMapper: ObjectMapper,
) : StrategyLookupPort {
    private val log = LoggerFactory.getLogger(StrategyLookupJpaAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findActiveByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy> {
        val intervalCode = interval.code

        // 1. Redis cache 조회
        val cachedIds = redisStrategyCache.getActive(code, intervalCode)
        if (cachedIds != null) {
            log.debug("cache hit: strategy:active:by_code:{}:{} → {} ids", code, intervalCode, cachedIds.size)
            return fetchByIds(cachedIds)
        }

        // 2. DB 조회 (codes JSONB contains code + intervals JSONB contains interval)
        val codeJson = buildJsonArray(code)
        val intervalJson = buildJsonArray(intervalCode)
        val entities = strategyJpaRepository.findActiveByCodeAndInterval(codeJson, intervalJson)

        // 3. cache put
        val ids = entities.mapNotNull { it.id }
        redisStrategyCache.putActive(code, intervalCode, ids)
        log.debug("cache miss → DB: strategy:active:by_code:{}:{} → {} strategies", code, intervalCode, entities.size)

        return entities.map { StrategyEntityMapper.toDomain(it, objectMapper) }
    }

    // --- private helper ---

    private fun fetchByIds(ids: List<Long>): List<Strategy> {
        if (ids.isEmpty()) return emptyList()
        return strategyJpaRepository.findAllById(ids).map { StrategyEntityMapper.toDomain(it, objectMapper) }
    }

    private fun buildJsonArray(value: String): String = objectMapper.writeValueAsString(listOf(value))
}
