package com.mj.domain.market.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Kafka 토픽 market.tick.v1 메시지 스키마.
 *
 * @param code    종목코드
 * @param at      tick 발생 시각
 * @param price   체결 가격
 * @param volume  누적 거래량
 * @param source  tick 출처 ("KIS_WS" | "MOCK" | "CACHED" | "LAST_DAY_CLOSE")
 * @param stale   true = fallback 데이터 (캐시 또는 전일종가). default false (하위호환).
 * @param asOf    실제 데이터 시각 (stale=true 시 캐시/종가 기준 시각). default null (하위호환).
 */
data class TickEvent(
    val code: String,
    val at: Instant,
    val price: BigDecimal,
    val volume: Long,
    val source: String = "KIS_WS",
    val stale: Boolean = false,
    val asOf: Instant? = null,
)
