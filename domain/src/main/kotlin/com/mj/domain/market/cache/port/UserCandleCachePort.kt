package com.mj.domain.market.cache.port

import com.mj.domain.common.Candle
import java.time.Instant

/**
 * 사용자 차트 ZSet 메타정보. frontend가 무작위 backend 호출을 막기 위한 판단 근거.
 *
 * frontend는 응답 헤더로 받은 first/last/count를 추적하고,
 *   - 사용자가 가진 cache 범위 안 → backend 호출 X
 *   - cache 너머 더 과거 필요 → before=first cursor로 backend 호출
 * 이 흐름으로 yfinance 호출 빈도를 자연스럽게 줄인다 (사용자 결정 2026-05-05).
 *
 * @param first  ZSet의 가장 과거 봉 openTime (count=0이면 null)
 * @param last   ZSet의 가장 최근 봉 openTime (count=0이면 null)
 * @param count  ZSet 전체 봉 수 (limit 무관)
 */
data class CacheRangeMeta(
    val first: Instant?,
    val last: Instant?,
    val count: Long,
) {
    companion object {
        val EMPTY: CacheRangeMeta = CacheRangeMeta(null, null, 0L)
    }
}

/**
 * 사용자별 차트 캔들 캐시 포트 (ADR 0050).
 *
 * Redis ZSet 기반 사용자 화면 전용 캐시. DB candle 영속과 별도 파이프라인.
 * - 사용자 5명 한정 (memory `project_kis_key_management.md`)
 * - LRU 10 종목 (TTL 없음)
 * - DB candle은 백테스트·룰 엔진 historical용으로 영속 유지 (영향 0)
 *
 * 키 구조 (Redis impl):
 *   candle:{userId}:{code}:{interval}     — ZSet, score=epoch second, value=JSON OHLCV
 *   candle:recent:{userId}                — ZSet, score=last access ms, value=code, LIMIT 10
 *   candle:lastfetch:{userId}:{code}:{interval} — String, value=lastFetched epoch second
 */
interface UserCandleCachePort {
    /**
     * 단일 candle ZADD upsert. 같은 score(openTime)면 덮어쓰기.
     */
    fun upsert(
        userId: String,
        code: String,
        interval: String,
        candle: Candle,
    )

    /**
     * 다중 candle 일괄 ZADD. backfill 결과 적재용.
     */
    fun upsertAll(
        userId: String,
        code: String,
        interval: String,
        candles: List<Candle>,
    )

    /**
     * ZSet ZRANGE 조회 — `before` epoch second 이전 봉을 limit개 반환 (오름차순).
     *
     * @param before  null이면 최신부터 limit개 (역방향 ZREVRANGE)
     */
    fun fetch(
        userId: String,
        code: String,
        interval: String,
        before: Instant?,
        limit: Int,
    ): List<Candle>

    /**
     * 사용자 최근 진입 종목 LRU 갱신 + 11번째 진입 시 가장 오래된 종목 evict.
     *
     * @return evicted된 종목 코드 목록 (0~N개). 호출자가 SubscriptionEvictedEvent fanout 등 후속 처리.
     */
    fun touchRecent(
        userId: String,
        code: String,
    ): List<String>

    /**
     * 마지막 fetch 시각 기록. 재진입 시 cache freshness 검증용.
     */
    fun setLastFetch(
        userId: String,
        code: String,
        interval: String,
        at: Instant,
    )

    /**
     * 마지막 fetch 시각 조회. 없으면 null.
     */
    fun getLastFetch(
        userId: String,
        code: String,
        interval: String,
    ): Instant?

    /**
     * ZSet의 first openTime, last openTime, count 메타 조회.
     *
     * frontend가 cache 범위를 판단하는 근거. 응답 헤더 X-Cache-First/Last/Count로 노출.
     * count=0이면 first·last는 null.
     */
    fun rangeMeta(
        userId: String,
        code: String,
        interval: String,
    ): CacheRangeMeta
}
