package com.mj.domain.market.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Kafka 토픽 candle.minute.aggregated.v1 메시지 스키마.
 *
 * consumer:TickToMinuteAggregator가 1분 버킷 flush 시 발행.
 * api:CandleMinuteEventListener가 수신 후 candle 테이블에 INSERT (interval=M1).
 *
 * @param code      종목코드
 * @param openTime  1분 버킷 시작 시각 (KST 분 단위 align, e.g. 09:00:00)
 * @param open      첫 tick 가격
 * @param high      버킷 내 최고가
 * @param low       버킷 내 최저가
 * @param close     마지막 tick 가격
 * @param volume    버킷 내 누적 거래량
 * @param tradeCount 버킷 내 tick 수
 */
data class CandleMinuteAggregatedEvent(
    val code: String,
    val openTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val tradeCount: Long,
)
