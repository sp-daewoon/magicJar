package kr.spdaewoon.magicjar.api.service.market

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 평가용 현재가 **읽기 전용** 리더 — api 모듈 (ADR 0014 T2 cache + 종가 폴백).
 *
 * 폴백 체인 (새 KIS 호출 0 — 읽기 전용):
 *  1. **LIVE**  — Redis `market:quote:last:code:{code}` (WS H0UNCNT0 체결 tick / REST polling write-through)
 *  2. **CLOSE** — WS 캐시 miss 시 해당 종목 최신 candle close (`1m` 우선, 없으면 `1d`)
 *  3. **null**  — 둘 다 없으면 가격 없음
 *
 * WS `quote:last` 캐시는 TTL 5분(`RedisQuoteCacheAdapter.QUOTE_TTL`)이라 **마감·장전엔 miss → "시세 대기"** 가
 * 됐다. 이때도 평가손익이 끊기지 않도록 마지막 캔들 종가로 폴백한다(가격 출처를 `PriceSource` 로 표기).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class LivePriceCacheReader(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val candleJpaRepository: CandleJpaRepository,
) {
    private val log = LoggerFactory.getLogger(LivePriceCacheReader::class.java)

    /** 채택된 가격과 그 출처. */
    data class ResolvedPrice(
        val price: BigDecimal,
        val source: PriceSource,
    )

    enum class PriceSource {
        /** 실시간 WS 시세 캐시(quote:last). */
        LIVE,

        /** 마지막 캔들 종가 폴백(1m 우선, 없으면 1d). */
        CLOSE,
    }

    /**
     * 단건 현재가 read (LIVE 만). cache miss·역직렬화 실패 → null (새 KIS 호출 없음).
     * 종가 폴백이 필요하면 [resolvePrice] 사용.
     */
    fun latestPrice(code: String): BigDecimal? {
        val json = redisTemplate.opsForValue().get(RedisKeyRegistry.Market.quoteLast(code)) ?: return null
        return parsePrice(code, json)
    }

    /**
     * 다종목 일괄 LIVE read — 종목당 1 GET. miss 종목은 map 에서 제외(value null 미포함).
     * 종가 폴백이 필요하면 [resolvePrices] 사용.
     */
    fun latestPrices(codes: Collection<String>): Map<String, BigDecimal> {
        val distinct = codes.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return distinct
            .mapNotNull { code ->
                latestPrice(code)?.let { code to it }
            }.toMap()
    }

    /**
     * 단건 폴백 체인 — LIVE → CLOSE → null. 둘 다 없으면 null.
     */
    fun resolvePrice(code: String): ResolvedPrice? {
        latestPrice(code)?.let { return ResolvedPrice(it, PriceSource.LIVE) }
        return latestClose(code)?.let { ResolvedPrice(it, PriceSource.CLOSE) }
    }

    /**
     * 다종목 폴백 체인 — 종목별로 LIVE → CLOSE → (없으면 map 제외).
     * caller 가 `result[code]` 로 조회해 부재 시 가격 없음(null) 처리한다.
     */
    fun resolvePrices(codes: Collection<String>): Map<String, ResolvedPrice> {
        val distinct = codes.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return distinct
            .mapNotNull { code -> resolvePrice(code)?.let { code to it } }
            .toMap()
    }

    /**
     * 최신 캔들 종가(close) 폴백 — `1m` 우선, 없으면 `1d`. 둘 다 없으면 null.
     * 읽기 전용 — 기존 candle repository 재사용, 새 KIS 호출 0.
     */
    private fun latestClose(code: String): BigDecimal? = latestCloseOf(code, INTERVAL_1M) ?: latestCloseOf(code, INTERVAL_1D)

    private fun latestCloseOf(
        code: String,
        interval: String,
    ): BigDecimal? =
        candleJpaRepository
            .findByCodeAndIntervalLatest(code, interval, PageRequest.of(0, 1))
            .firstOrNull()
            ?.closeRaw

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

    private companion object {
        const val INTERVAL_1M = "1m"
        const val INTERVAL_1D = "1d"
    }
}
