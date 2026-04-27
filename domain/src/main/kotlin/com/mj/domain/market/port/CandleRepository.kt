package com.mj.domain.market.port

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import java.time.Instant

/**
 * 일/분봉 캔들 저장소 포트. 구현체는 market-data 모듈이 제공한다.
 * 저장은 upsert 의미론(같은 code+interval+openTime이면 덮어쓰기).
 */
interface CandleRepository {
    fun save(candles: List<Candle>)

    fun find(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle>
}
