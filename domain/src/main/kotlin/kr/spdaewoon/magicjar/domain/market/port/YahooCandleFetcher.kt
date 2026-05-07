package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval

/**
 * Yahoo Finance v8 chart endpoint 어댑터 port (ADR 0019 정합).
 * 구현은 consumer 모듈 infrastructure/yahoo에서.
 */
interface YahooCandleFetcher {
    /**
     * Yahoo Finance v8 차트 데이터 조회.
     *
     * @param ticker    KIS 종목코드 (6자리). 어댑터 내부에서 .KS/.KQ suffix 결정
     * @param interval  1m·5m·15m·30m·90m·1h·1d
     * @param rangeDays 1m=7, 5m·15m·30m·90m=60, 1h=730, 1d=Max(전체)
     * @return Candle 리스트 (raw 컬럼 채움, adj null — T-CA1)
     */
    fun fetch(
        ticker: String,
        interval: CandleInterval,
        rangeDays: Int,
    ): List<Candle>
}
