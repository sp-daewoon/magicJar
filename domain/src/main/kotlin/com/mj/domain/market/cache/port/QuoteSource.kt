package com.mj.domain.market.cache.port

/**
 * 시세 응답의 출처. 3-tier fallback (ADR 0033) 응답에 포함.
 *
 * LIVE         : KIS WebSocket 실시간 tick
 * CACHED       : Redis QuoteCache (마지막 수신 tick — 10초 이상 경과 시 stale=true)
 * LAST_DAY_CLOSE: DB 캔들 테이블의 전일 종가 (캐시 miss 또는 캐시 stale > 임계값)
 */
enum class QuoteSource {
    LIVE,
    CACHED,
    LAST_DAY_CLOSE,
}
