package com.mj.domain.ai

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 단일 뉴스/공시에 대한 시장 감성 분류.
 *
 * - [POSITIVE] / [NEUTRAL] / [NEGATIVE]
 * - [confidence] 0.0 ~ 1.0 (LLM 자체 신뢰도)
 * - [rationale] 1~2 문장 한국어 근거 — 사용자 노출용
 *
 * NewsSentimentAnalyzer port의 반환 단위. parsing 실패·LLM 비활성 시 호출 측은 `null`을 받는다.
 */
sealed class SentimentScore(
    open val confidence: BigDecimal,
    open val rationale: String,
) {
    abstract val label: Label

    data class Positive(
        override val confidence: BigDecimal,
        override val rationale: String,
    ) : SentimentScore(confidence, rationale) {
        override val label: Label = Label.POSITIVE
    }

    data class Neutral(
        override val confidence: BigDecimal,
        override val rationale: String,
    ) : SentimentScore(confidence, rationale) {
        override val label: Label = Label.NEUTRAL
    }

    data class Negative(
        override val confidence: BigDecimal,
        override val rationale: String,
    ) : SentimentScore(confidence, rationale) {
        override val label: Label = Label.NEGATIVE
    }

    enum class Label { POSITIVE, NEUTRAL, NEGATIVE }

    companion object {
        /**
         * raw confidence(0.0 ~ 1.0)를 소수 둘째자리로 정규화.
         * 범위 밖 값은 clamp.
         */
        fun normalize(raw: Double): BigDecimal = BigDecimal(raw.coerceIn(0.0, 1.0).toString()).setScale(2, RoundingMode.HALF_UP)

        fun of(
            label: Label,
            confidence: Double,
            rationale: String,
        ): SentimentScore {
            val c = normalize(confidence)
            val r = rationale.trim()
            return when (label) {
                Label.POSITIVE -> Positive(c, r)
                Label.NEUTRAL -> Neutral(c, r)
                Label.NEGATIVE -> Negative(c, r)
            }
        }

        /**
         * 문자열 라벨을 안전 파싱. 알 수 없는 값은 NEUTRAL.
         */
        fun parseLabel(raw: String?): Label =
            when (raw?.uppercase()?.trim()) {
                "POSITIVE", "POS", "+" -> Label.POSITIVE
                "NEGATIVE", "NEG", "-" -> Label.NEGATIVE
                else -> Label.NEUTRAL
            }
    }
}
