package com.mj.domain.market.port

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import java.time.Instant
import java.time.LocalDate

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

    /**
     * 지정 날짜 이하의 가장 최근 일봉(D1) 종가를 반환. Tier-3 fallback 용도 (ADR 0033).
     *
     * @param code     종목코드
     * @param asOfDate 기준일 포함 이하에서 가장 최근 영업일 종가 검색
     * @return 캔들 또는 null (데이터 없음)
     */
    fun findLastClose(
        code: String,
        asOfDate: LocalDate,
    ): Candle? = null // default — 구현체 미제공 시 null 반환 (하위호환)
}
