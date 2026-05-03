package com.mj.domain.strategy.pattern

/**
 * 패턴 매칭 결과의 방향. PatternRecognizer가 실제 캔들 데이터를 보고 산출한다.
 *
 * - BULL: 매수 신호 (+1)
 * - BEAR: 매도 신호 (-1)
 * - NEUTRAL: 방향 미정 (Doji, Spinning Top 등 indecision)
 *
 * KIS Strategy Builder의 +1/-1/0 시그널과 호환.
 */
enum class PatternDirection {
    BULL,
    BEAR,
    NEUTRAL,
    ;

    fun signum(): Int =
        when (this) {
            BULL -> 1
            BEAR -> -1
            NEUTRAL -> 0
        }
}
