package com.mj.api.application.market

import com.mj.api.infrastructure.cache.MarketIndexRedisAdapter
import com.mj.api.infrastructure.kis.KisMarketIndexAdapter
import com.mj.domain.market.index.MarketIndex
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 시장 지수 조회 서비스 (T21).
 *
 * 흐름:
 * 1. 요청된 코드 목록 중 Redis 캐시 hit인 것은 즉시 반환
 * 2. miss인 코드만 KIS fetchMarketIndex 호출
 * 3. 결과 Redis 저장 (TTL 1분)
 *
 * KIS 키 미설정 시 빈 리스트 반환.
 */
@Service
class MarketIndexService(
    private val indexCache: MarketIndexRedisAdapter,
    private val kisAdapter: KisMarketIndexAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 지수 코드 목록으로 조회. 빈 리스트도 정상 응답. */
    fun getIndexes(codes: List<String>): List<MarketIndex> {
        if (codes.isEmpty()) return emptyList()

        val cached = mutableListOf<MarketIndex>()
        val missing = mutableListOf<String>()

        for (code in codes) {
            val hit = indexCache.find(code)
            if (hit != null) {
                log.debug("[index-svc] cache hit code={}", code)
                cached.add(hit)
            } else {
                missing.add(code)
            }
        }

        if (missing.isEmpty()) return cached

        // KIS 호출 (미스 코드만)
        val fetched = kisAdapter.fetchMarketIndex(missing)
        fetched.forEach { indexCache.save(it) }

        return cached + fetched
    }

    companion object {
        /** 지원 코드 → 지수명 매핑 */
        val CODE_TO_NAME =
            mapOf(
                "0001" to "KOSPI",
                "1001" to "KOSDAQ",
            )

        /** 이름(KOSPI/KOSDAQ) → 코드 변환 */
        fun nameToCode(name: String): String? =
            when (name.uppercase()) {
                "KOSPI" -> "0001"
                "KOSDAQ" -> "1001"
                else -> null
            }
    }
}
