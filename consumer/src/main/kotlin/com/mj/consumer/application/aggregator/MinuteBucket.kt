package com.mj.consumer.application.aggregator

import com.mj.domain.market.event.CandleMinuteAggregatedEvent
import java.math.BigDecimal
import java.time.Instant

/**
 * 1분 단위 tick 누적 버킷.
 *
 * 스레드 안전 요구 없음 — TickToMinuteAggregator가 ConcurrentHashMap에서
 * compute / computeIfAbsent 로 접근하므로 개별 인스턴스는 단일 스레드에서만 조작됨.
 *
 * @param code     종목코드
 * @param openTime 버킷 시작 시각 (KST 분 align, seconds=0 nano=0)
 */
internal class MinuteBucket(
    val code: String,
    val openTime: Instant,
) {
    private var open: BigDecimal? = null
    private var high: BigDecimal = BigDecimal.ZERO
    private var low: BigDecimal = BigDecimal(Long.MAX_VALUE)
    private var close: BigDecimal = BigDecimal.ZERO
    private var volume: Long = 0L
    private var tradeCount: Long = 0L

    /** 마지막 tick 수신 시각 (timeout 감지용). */
    var lastTickAt: Instant = openTime

    /** tick 유무 (결측 체크). */
    val hasData: Boolean get() = open != null

    /**
     * tick 데이터를 버킷에 누적한다.
     *
     * @param price     체결 가격
     * @param tickVolume 해당 tick 거래량
     * @param tickAt    tick 발생 시각
     */
    fun add(
        price: BigDecimal,
        tickVolume: Long,
        tickAt: Instant,
    ) {
        if (open == null) open = price
        if (price > high) high = price
        if (price < low) low = price
        close = price
        volume += tickVolume
        tradeCount++
        lastTickAt = tickAt
    }

    /**
     * 버킷을 CandleMinuteAggregatedEvent로 변환한다.
     * hasData == false 인 경우 호출하지 말 것.
     */
    fun toEvent(): CandleMinuteAggregatedEvent =
        CandleMinuteAggregatedEvent(
            code = code,
            openTime = openTime,
            open = requireNotNull(open) { "open 미초기화 — hasData 확인 후 호출할 것" },
            high = high,
            low = if (low == BigDecimal(Long.MAX_VALUE)) close else low,
            close = close,
            volume = volume,
            tradeCount = tradeCount,
        )
}
