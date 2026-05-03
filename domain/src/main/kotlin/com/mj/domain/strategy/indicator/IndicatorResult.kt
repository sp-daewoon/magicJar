package com.mj.domain.strategy.indicator

import java.math.BigDecimal
import java.time.Instant

/**
 * 지표 계산 결과.
 *
 * series는 시계열. null인 점(기간 부족)은 포함하지 않는다.
 * source는 계산에 사용된 IndicatorSource.name.
 */
data class IndicatorResult(
    val spec: IndicatorSpec,
    val series: List<IndicatorPoint>,
    val source: String,
)

/**
 * 시계열 한 점.
 *
 * values: 지표가 단일 출력이면 Map(id to value), 다중이면 각 key별 BigDecimal.
 * 예) MACD → { "macd": 1.23, "signal": 0.45, "histogram": 0.78 }
 *     RSI  → { "rsi": 65.40 }
 */
data class IndicatorPoint(
    val time: Instant,
    val values: Map<String, BigDecimal>,
)
