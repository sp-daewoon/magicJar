package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval

/**
 * KIS REST API 포트 (ADR 0019·0046 정합).
 * 구현은 api 모듈 adapter/kis에서 (PR-6 T-ONBOARD).
 */
interface KisRestPort {
    fun fetchCandles(
        code: String,
        interval: CandleInterval,
        count: Int,
    ): List<Candle>
}
