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
}
