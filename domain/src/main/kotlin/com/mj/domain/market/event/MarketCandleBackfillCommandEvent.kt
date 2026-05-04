package com.mj.domain.market.event

/**
 * 일봉 캔들 backfill 명령. api·consumer → batch.
 *
 * Topic: `market.candle.backfill.command.v1`
 *
 * 발행 시점:
 *   1) 사용자 종목 상세 진입 — `QuoteController.quote()`가 ensure-backfill 발행
 *   2) Scheduled 16:30 KST — `DailyCandleBackfillScheduler`가 default5 일괄 발행
 *
 * batch 측 listener는 멱등 처리 (DB max(openTime) ≥ 어제이면 skip + in-memory dedup 60초 TTL).
 *
 * @param code     KRX 6자리 종목코드
 * @param interval 캔들 interval ("D1" 일봉, 후속 분봉 확장 가능)
 * @param fromDate ISO yyyy-MM-dd (선택 — null이면 listener가 max(openTime)+1day 또는 1년 전)
 * @param toDate   ISO yyyy-MM-dd (선택 — null이면 listener가 today)
 * @param requestedAt 요청 시각 (epoch millis)
 *
 * Refs: handoff 2026-05-04 D — 사용자 보고 "4-30 마지막 캔들 + default5 외 candle 0건".
 */
data class MarketCandleBackfillCommandEvent(
    val code: String,
    val interval: String = DEFAULT_INTERVAL,
    val fromDate: String? = null,
    val toDate: String? = null,
    val requestedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_INTERVAL: String = "D1"
    }
}
