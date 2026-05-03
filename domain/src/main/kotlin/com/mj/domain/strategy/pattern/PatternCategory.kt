package com.mj.domain.strategy.pattern

/**
 * 캔들스틱 패턴 카테고리 (KIS Strategy Builder 분류).
 *
 * - BULLISH_REVERSAL: 하락 추세에서 상승 반전 신호
 * - BEARISH_REVERSAL: 상승 추세에서 하락 반전 신호
 * - BULLISH_CONTINUATION: 상승 추세 연장 신호
 * - BEARISH_CONTINUATION: 하락 추세 연장 신호
 * - INDECISION: 추세 망설임/박스권
 * - REVERSAL_DIRECTIONAL: 방향성은 패턴 형태(양/음봉)에 따라 결정 (Engulfing 등)
 */
enum class PatternCategory {
    BULLISH_REVERSAL,
    BEARISH_REVERSAL,
    BULLISH_CONTINUATION,
    BEARISH_CONTINUATION,
    INDECISION,
    REVERSAL_DIRECTIONAL,
    ;

    companion object {
        /** 본질적으로 강세 신호로 분류되는 카테고리 (UI 색상·정렬 가이드용) */
        val bullish: Set<PatternCategory> =
            setOf(BULLISH_REVERSAL, BULLISH_CONTINUATION)

        /** 본질적으로 약세 신호로 분류되는 카테고리 */
        val bearish: Set<PatternCategory> =
            setOf(BEARISH_REVERSAL, BEARISH_CONTINUATION)
    }
}
