package com.mj.domain.market.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Kafka 토픽 market.candle.user.v1 메시지 스키마. ADR 0050 R3-4.
 *
 * api:UserChartCandleService가 DB→Redis warm-up 직후 발행.
 * consumer:UserCandleListener가 수신 후 동일 Redis ZADD 수행 (dual-write 1차).
 *
 * dual-write 배경:
 *   R3-4에서는 api 직접 ZADD를 유지하고 Kafka publish를 추가한다.
 *   consumer 측 ZADD는 idempotent — 같은 score(openTime)는 덮어쓰기로 정합 보장.
 *   R3-5(다음 사이클)에서 api 측 직접 ZADD를 제거해 단일 path로 전환 예정.
 *
 * @param userId      사용자 식별자 (ADR 0049 — "74683223" 기본)
 * @param code        KRX 종목코드 (예: "005930")
 * @param interval    Interval 문자열 (예: "D1", "M1")
 * @param candles     warm-up된 캔들 목록 (epoch second openTime + OHLCV)
 * @param occurredAt  이벤트 발생 시각
 */
data class UserCandleUpdatedEvent(
    val userId: String,
    val code: String,
    val interval: String,
    val candles: List<CandleSnapshot>,
    val occurredAt: Instant,
)

/**
 * Kafka 직렬화용 캔들 스냅샷. domain Candle의 경량 표현.
 *
 * BigDecimal → String 직렬화: JSON 숫자 정밀도 손실 방지.
 */
data class CandleSnapshot(
    val openTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)
