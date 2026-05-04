package com.mj.domain.market.cache.port

import com.mj.domain.common.Candle
import java.time.Instant
import java.util.UUID

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
        userId: UUID,
        code: String,
        interval: String,
        candle: Candle,
    )

    /**
     * 다중 candle 일괄 ZADD. backfill 결과 적재용.
     */
    fun upsertAll(
        userId: UUID,
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
        userId: UUID,
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
        userId: UUID,
        code: String,
    ): List<String>

    /**
     * 마지막 fetch 시각 기록. 재진입 시 cache freshness 검증용.
     */
    fun setLastFetch(
        userId: UUID,
        code: String,
        interval: String,
        at: Instant,
    )

    /**
     * 마지막 fetch 시각 조회. 없으면 null.
     */
    fun getLastFetch(
        userId: UUID,
        code: String,
        interval: String,
    ): Instant?
}
