package com.mj.domain.strategy.pattern

import com.mj.domain.common.Candle

/**
 * 패턴 인식 결과. PatternRecognizer가 캔들 시계열을 sliding window로 훑어 발견한 매칭 1건.
 *
 * @property pattern        매칭된 패턴 메타데이터
 * @property direction      실제 방향 (BULL/BEAR/NEUTRAL). REVERSAL_DIRECTIONAL은 양/음봉 조합으로 결정
 * @property endIndex       매칭이 종료된 캔들의 인덱스 (캔들 리스트의 마지막 캔들 위치)
 * @property startIndex     매칭이 시작된 캔들의 인덱스 (= endIndex - pattern.candleCount + 1)
 * @property strength       0.0~1.0. 패턴 정합도 (캔들 비율의 명확성 등). 1.0=완벽, 0.5=경계 케이스
 * @property endOpenTime    endIndex 캔들의 openTime (UI·로깅 편의)
 */
data class PatternMatch(
    val pattern: CandlePattern,
    val direction: PatternDirection,
    val endIndex: Int,
    val startIndex: Int,
    val strength: Double,
    val endOpenTime: java.time.Instant,
) {
    init {
        require(endIndex >= startIndex) { "endIndex must be >= startIndex" }
        require(endIndex - startIndex + 1 == pattern.candleCount) {
            "window size mismatch: ${endIndex - startIndex + 1} vs ${pattern.candleCount}"
        }
        require(strength in 0.0..1.0) { "strength must be in [0,1]: $strength" }
    }

    companion object {
        fun of(
            pattern: CandlePattern,
            direction: PatternDirection,
            candles: List<Candle>,
            endIndex: Int,
            strength: Double,
        ): PatternMatch =
            PatternMatch(
                pattern = pattern,
                direction = direction,
                endIndex = endIndex,
                startIndex = endIndex - pattern.candleCount + 1,
                strength = strength,
                endOpenTime = candles[endIndex].openTime,
            )
    }
}
