package com.mj.domain.strategy.dsl

import com.mj.domain.common.Candle
import java.math.BigDecimal

/**
 * 백테스트/실시간 봉 단위 평가 컨텍스트.
 *
 * 평가기는 `currentIndex`까지의 캔들만 참조한다(lookahead 금지).
 * `extras`는 전략별 추가 변수(포지션 보유 일수 등)를 주입할 때 사용.
 */
data class BarEvaluationContext(
    val candles: List<Candle>,
    val currentIndex: Int,
    val extras: Map<String, BigDecimal> = emptyMap(),
) {
    init {
        require(currentIndex in candles.indices) {
            "currentIndex out of range: $currentIndex / size=${candles.size}"
        }
    }
}
