package com.mj.domain.market.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Kafka 토픽 `market.index.v1` 메시지 스키마 — KOSPI·KOSDAQ 지수 실시간 체결.
 *
 * KIS 시세 WebSocket trId: H0UPCNT0 (국내지수 실시간체결, 평문 30 columns).
 *
 * @param code        지수코드 ("0001"=KOSPI, "1001"=KOSDAQ)
 * @param at          tick 발생 시각 (KST 영업시간 + epoch fallback)
 * @param value       현재 지수값
 * @param change      전일 대비 (절대값, 부호 포함)
 * @param changeRate  등락률 (%)
 * @param volume      누적 거래량
 * @param turnover    누적 거래대금 (원)
 * @param source      "KIS_WS" | "KIS_REST" (REST polling fallback) | "CACHED"
 * @param stale       true = fallback 데이터 (REST polling 또는 cache)
 * @param asOf        실제 데이터 시각 (stale=true 시 의미 있음)
 *
 * Refs: plan T2-R2-A2 §T12, ADR 0033.
 */
data class IndexTickEvent(
    val code: String,
    val at: Instant,
    val value: BigDecimal,
    val change: BigDecimal,
    val changeRate: BigDecimal,
    val volume: Long,
    val turnover: Long,
    val source: String = "KIS_WS",
    val stale: Boolean = false,
    val asOf: Instant? = null,
) {
    companion object {
        const val KOSPI: String = "0001"
        const val KOSDAQ: String = "1001"
    }
}
