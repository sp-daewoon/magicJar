package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval

/**
 * 캔들 영속 포트 (ADR 0019 — UQ candle.code+interval+ts).
 * 구현은 api 모듈 persistence + consumer 모듈 persistence 각각.
 */
interface CandleRepository {
    /**
     * 캔들 일괄 저장 (ON CONFLICT DO NOTHING — idempotent).
     * @return 실제 INSERT된 row 수
     */
    fun saveAll(candles: List<Candle>): Int

    fun countByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Long

    fun countByCodeAndIntervalIn(
        code: String,
        intervals: List<CandleInterval>,
    ): Long

    /**
     * 특정 종목·interval 의 전체 candle list 반환 (ts ASC).
     *
     * ADR 0062 §9 — AggregateBackfillRunner 가 1m 전량 → 5m·15m·30m·90m·1h 합산 시 사용.
     * 30 거래일 1m 기준 ~10k~20k row 예상 (in-memory aggregation 가능 범위).
     */
    fun findByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Candle>
}
